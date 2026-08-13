/*
 * Renders the status page from the JSON the pages workflow generates.
 *
 * data/latest.json  the current picture, with every test case
 * data/history.json one summary per collection, oldest first
 *
 * Both are written by site/tools/collect_results.py, from the most recent run of each test
 * workflow. Everything here is presentation: the pass/fail/finding distinction is decided
 * at collection time, because it depends on which Gradle task ran the suite, and results
 * are keyed by OkHttp version there too, because that is the comparison being made.
 */

const STATUS_TEXT = {
  passed: "passing",
  failed: "failing",
  finding: "findings",
  skipped: "not run",
  unknown: "unknown",
};

const el = (tag, props = {}, children = []) => {
  const node = Object.assign(document.createElement(tag), props);
  for (const child of [].concat(children)) {
    if (child != null) node.append(child);
  }
  return node;
};

const pill = (status) =>
  el("span", { className: `pill ${status}`, textContent: STATUS_TEXT[status] || status });

const plural = (n, word) => `${n} ${word}${n === 1 ? "" : "s"}`;

function formatWhen(iso) {
  if (!iso) return "unknown time";
  const then = new Date(iso);
  if (Number.isNaN(then.getTime())) return iso;
  const hours = (Date.now() - then.getTime()) / 3.6e6;
  const relative =
    hours < 1 ? "less than an hour ago"
    : hours < 48 ? `${plural(Math.round(hours), "hour")} ago`
    : `${plural(Math.round(hours / 24), "day")} ago`;
  return `${then.toISOString().replace("T", " ").slice(0, 16)} UTC (${relative})`;
}

function renderSummary(snapshot) {
  // One line per workflow that contributed. They finish at different times — the container
  // suites daily, the emulator suite an hour later — so the page says how old each half of
  // the picture is rather than pretending there was a single run.
  const runs = snapshot.collectedFrom.map((run) =>
    el("span", {}, [
      el("strong", { className: "mono", textContent: run.workflow }),
      ` #${run.runNumber || "?"} · ${formatWhen(run.finishedAt)}`,
      run.event ? ` · ${run.event}` : "",
      // A run that uploaded no results at all: the job died before its tests could report.
      run.suiteCount === 0 ? ` · no results (job ${run.jobStatus || "failed"})` : "",
      " ",
      run.runUrl ? el("a", { href: run.runUrl, textContent: "run ↗" }) : null,
    ]),
  );

  document.getElementById("run-summary").replaceChildren(pill(snapshot.status), ...runs);
}

function renderVersionCards(snapshot) {
  const cards = snapshot.versions.map((version) => {
    const counts = el("div", { className: "counts" }, [
      el("div", {}, [
        el("div", { className: "n-passed", textContent: version.passed }),
        el("div", { className: "count-label", textContent: "passed" }),
      ]),
      el("div", {}, [
        el("div", { className: "n-failed", textContent: version.failed }),
        el("div", { className: "count-label", textContent: "failed" }),
      ]),
      el("div", {}, [
        el("div", { className: "n-skipped", textContent: version.skipped }),
        el("div", { className: "count-label", textContent: "skipped" }),
      ]),
    ]);

    return el("section", { className: "card" }, [
      el("div", { className: "card-head" }, [
        el("h3", { textContent: version.okhttpVersion }),
        pill(version.status),
      ]),
      el("div", {
        className: "card-label",
        textContent:
          version.workflows.join(", ") +
          ` · ${plural(version.suites.length, "suite")} in ${version.timeSeconds}s`,
      }),
      counts,
    ]);
  });

  document.getElementById("version-cards").replaceChildren(...cards);
}

function renderSuiteTable(snapshot) {
  const versions = snapshot.versions;
  const suiteNames = [
    ...new Set(versions.flatMap((v) => v.suites.map((s) => s.name))),
  ].sort();

  const head = el("tr", {}, [
    el("th", { textContent: "Suite" }),
    el("th", { textContent: "Workflow" }),
    el("th", { textContent: "Gradle task" }),
    ...versions.map((v) => el("th", { textContent: v.okhttpVersion })),
  ]);

  const rows = suiteNames.map((name) => {
    const any = versions.flatMap((v) => v.suites).find((s) => s.name === name);
    return el("tr", {}, [
      el("td", { className: "suite", textContent: name }),
      el("td", { className: "mono", textContent: any ? any.workflow : "" }),
      el("td", { className: "mono", textContent: any ? any.task : "" }),
      ...versions.map((version) => {
        const suite = version.suites.find((s) => s.name === name);
        if (!suite) return el("td", {}, el("span", { className: "pill unknown", textContent: "—" }));
        const status = suite.failed
          ? suite.reporting ? "finding" : "failed"
          : suite.passed ? "passed" : "skipped";
        return el("td", {}, [
          pill(status),
          el("span", {
            className: "card-label",
            textContent: ` ${suite.passed}/${suite.passed + suite.failed} in ${suite.timeSeconds}s`,
          }),
        ]);
      }),
    ]);
  });

  document.getElementById("suite-table").replaceChildren(
    el("thead", {}, head),
    el("tbody", {}, rows),
  );
}

function renderFailures(snapshot) {
  const items = [];
  for (const version of snapshot.versions) {
    for (const suite of version.suites) {
      for (const testCase of suite.cases) {
        if (testCase.status !== "failed") continue;
        const kind = suite.reporting ? "finding" : "failed";
        const detail = el("details", { className: "finding-detail" }, [
          el("summary", {}, [
            pill(kind),
            ` ${suite.name}.${testCase.name} — ${version.okhttpVersion}`,
          ]),
          el("pre", {
            textContent: [testCase.message, testCase.detail].filter(Boolean).join("\n\n"),
          }),
        ]);
        detail.dataset.kind = kind;
        items.push(detail);
      }
    }
  }

  const section = document.getElementById("failures");
  const body = document.getElementById("failure-list");
  if (!items.length) {
    body.replaceChildren(
      el("p", { textContent: "No failures or findings in this run." }),
    );
  } else {
    body.replaceChildren(...items);
  }
  section.hidden = false;
}

function renderHistory(history) {
  const entries = history.runs || [];
  const okhttpVersions = [
    ...new Set(entries.flatMap((e) => e.versions.map((v) => v.okhttpVersion))),
  ];

  const rows = okhttpVersions.map((okhttpVersion) => {
    const blocks = entries.map((entry) => {
      const version = entry.versions.find((v) => v.okhttpVersion === okhttpVersion);
      const status = version ? version.status : "unknown";
      const when = (entry.finishedAt || "").slice(0, 10);
      const title = version
        ? `${when} ${okhttpVersion}: ${STATUS_TEXT[status]} — ` +
          `${version.passed} passed, ${version.failed} failed`
        : `${when} ${okhttpVersion}: not tested`;
      const source = (entry.collectedFrom || [])[0];
      return el("a", { className: `${status}`, href: source ? source.runUrl : "#", title });
    });

    return el("div", { className: "history-row" }, [
      el("span", { className: "history-label", textContent: okhttpVersion }),
      el("div", { className: "history-strip" }, blocks),
    ]);
  });

  const target = document.getElementById("history");
  if (!rows.length) {
    target.replaceChildren(el("p", { textContent: "No history recorded yet." }));
  } else {
    target.replaceChildren(...rows);
  }
}

async function loadJson(path) {
  const response = await fetch(path, { cache: "no-cache" });
  if (!response.ok) throw new Error(`${path}: HTTP ${response.status}`);
  return response.json();
}

(async () => {
  try {
    const snapshot = await loadJson("data/latest.json");
    renderSummary(snapshot);
    renderVersionCards(snapshot);
    renderSuiteTable(snapshot);
    renderFailures(snapshot);
  } catch (e) {
    document.getElementById("run-summary").replaceChildren(
      el("span", {}, [
        "No published results yet. They appear here after the ",
        el("a", {
          href: "https://github.com/yschimke/okhttp-testbed/actions/workflows/containers.yml",
          textContent: "containers workflow",
        }),
        ` runs. (${e.message})`,
      ]),
    );
  }

  try {
    renderHistory(await loadJson("data/history.json"));
  } catch {
    renderHistory({ runs: [] });
  }
})();
