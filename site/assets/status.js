/*
 * Renders the status page from the JSON the pages workflow generates.
 *
 * data/latest.json  the most recent run, with every test case
 * data/history.json one summary per run, oldest first
 *
 * Both are written by site/tools/collect_results.py. Everything here is presentation:
 * the pass/fail/finding distinction is decided at collection time, because it depends on
 * which Gradle task ran the suite.
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

function renderSummary(run) {
  const target = document.getElementById("run-summary");
  target.replaceChildren(
    pill(run.status),
    el("span", {}, [
      el("strong", { textContent: `Run #${run.runNumber || "?"}` }),
      ` · ${formatWhen(run.finishedAt)}`,
    ]),
    run.event ? el("span", { textContent: `triggered by ${run.event}` }) : null,
    run.commit
      ? el("a", {
          className: "mono",
          href: `https://github.com/yschimke/okhttp-testbed/commit/${run.commit}`,
          textContent: run.commit.slice(0, 7),
        })
      : null,
    run.runUrl ? el("a", { href: run.runUrl, textContent: "workflow run ↗" }) : null,
  );
}

function renderVersionCards(run) {
  const cards = run.versions.map((version) => {
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
          `${version.label === "pinned" ? "pinned release" : version.label}` +
          (version.javaVersion ? ` · JDK ${version.javaVersion}` : "") +
          ` · ${plural(version.suites.length, "suite")} in ${version.timeSeconds}s`,
      }),
      counts,
    ]);
  });

  document.getElementById("version-cards").replaceChildren(...cards);
}

function renderSuiteTable(run) {
  const versions = run.versions;
  const suiteNames = [
    ...new Set(versions.flatMap((v) => v.suites.map((s) => s.name))),
  ].sort();

  const head = el("tr", {}, [
    el("th", { textContent: "Suite" }),
    el("th", { textContent: "Gradle task" }),
    ...versions.map((v) => el("th", { textContent: v.okhttpVersion })),
  ]);

  const rows = suiteNames.map((name) => {
    const any = versions.flatMap((v) => v.suites).find((s) => s.name === name);
    return el("tr", {}, [
      el("td", { className: "suite", textContent: name }),
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

function renderFailures(run) {
  const items = [];
  for (const version of run.versions) {
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
  const runs = history.runs || [];
  const labels = [...new Set(runs.flatMap((r) => r.versions.map((v) => v.label)))];

  const rows = labels.map((label) => {
    const blocks = runs.map((run) => {
      const version = run.versions.find((v) => v.label === label);
      const status = version ? version.status : "unknown";
      const title = version
        ? `#${run.runNumber} ${version.okhttpVersion}: ${STATUS_TEXT[status]} — ` +
          `${version.passed} passed, ${version.failed} failed`
        : `#${run.runNumber}: not run`;
      return el("a", { className: `${status}`, href: run.runUrl || "#", title });
    });

    return el("div", { className: "history-row" }, [
      el("span", { className: "history-label", textContent: label === "pinned" ? "pinned release" : label }),
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
    const run = await loadJson("data/latest.json");
    renderSummary(run);
    renderVersionCards(run);
    renderSuiteTable(run);
    renderFailures(run);
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
