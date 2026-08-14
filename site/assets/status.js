/*
 * Renders the status page from the JSON the pages workflow generates.
 *
 * data/latest.json  the current picture, with every test case and every endpoint probed
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
  expected: "expected",
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

/*
 * Where a suite's source lives, from the workflow that ran it and its fully qualified name.
 *
 * Every suite here follows one layout, so the link can be derived rather than recorded: the
 * workflow names the module, and the class name is the path under it. Deriving it means a new
 * suite is linked the day it lands, with nothing to keep in step; the cost is that moving a
 * module without updating this map produces a 404 rather than a missing link.
 */
const REPO = "https://github.com/yschimke/okhttp-testbed";

const SOURCE_ROOTS = {
  containers: "containers/src/test/kotlin",
  network: "network/src/test/kotlin",
  "android-ech": "android-ech/src/androidTest/kotlin",
};

function sourceUrl(suite) {
  const root = SOURCE_ROOTS[suite.workflow];
  if (!root || !suite.className) return null;
  return `${REPO}/blob/main/${root}/${suite.className.replaceAll(".", "/")}.kt`;
}

/** A suite's name, linked to the test that produced it where we can work out where it lives. */
function suiteLink(suite, className = "suite-name") {
  const href = sourceUrl(suite);
  return href
    ? el("a", { className, href, textContent: suite.name, title: suite.className })
    : el("span", { className, textContent: suite.name });
}

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
        el("div", { className: "count-label", textContent: "unexpected" }),
      ]),
      el("div", {}, [
        el("div", { className: "n-expected", textContent: version.expected ?? 0 }),
        el("div", { className: "count-label", textContent: "expected" }),
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
    el("th", { textContent: "Severity" }),
    ...versions.map((v) => el("th", { textContent: v.okhttpVersion })),
  ]);

  const rows = suiteNames.map((name) => {
    const any = versions.flatMap((v) => v.suites).find((s) => s.name === name);
    return el("tr", {}, [
      el("td", { className: "suite" }, any ? suiteLink(any) : name),
      el("td", { className: "mono", textContent: any ? any.workflow : "" }),
      el("td", { className: "mono", textContent: any ? any.task : "" }),
      el("td", {
        className: "card-label",
        textContent: any ? (any.reporting ? (any.severity ?? "watch") : "gates") : "",
      }),
      ...versions.map((version) => {
        const suite = version.suites.find((s) => s.name === name);
        if (!suite) return el("td", {}, el("span", { className: "pill unknown", textContent: "—" }));
        const expected = suite.expected ?? 0;
        const status = suite.failed
          ? suite.reporting && suite.severity !== "critical" ? "finding" : "failed"
          : expected ? "expected"
          : suite.passed ? "passed" : "skipped";
        return el("td", {}, [
          pill(status),
          el("span", {
            className: "card-label",
            textContent:
              ` ${suite.passed}/${suite.passed + suite.failed + expected}` +
              (expected ? ` (+${expected} expected)` : "") +
              ` in ${suite.timeSeconds}s`,
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
  // Unexpected first, then findings, then the failures that are the point, then the tests that
  // never ran. A skip belongs here rather than only in a count: "this didn't run because defo.ie
  // is down" is the answer to the question a red-looking number would otherwise raise.
  const rank = { failed: 0, finding: 1, expected: 2, skipped: 3 };
  const items = [];

  for (const version of snapshot.versions) {
    for (const suite of version.suites) {
      for (const testCase of suite.cases) {
        if (testCase.status === "passed") continue;
        // Severity decides how loudly an unexpected failure is shown. A critical suite is one
        // this repository is currently for, so its surprises are red even though it reports
        // rather than gates; a watch suite stays amber, because the far end is as likely a
        // cause as the client. Expected failures and skips are unaffected by either.
        const kind =
          testCase.status === "skipped" ? "skipped"
          : testCase.status === "expected" ? "expected"
          : suite.reporting && suite.severity !== "critical" ? "finding"
          : "failed";

        const source = sourceUrl(suite);
        // Open, because the assertion is the reason to come back to this page — a triangle to
        // click before you can read it is one more step for the thing you came for. Skips are
        // the exception: the reason is one line, and the Endpoints table already carries it.
        // Open, because the assertion is the reason to come back to this page. Two exceptions,
        // both cases where the one line that matters is already in the summary: a skip, and a
        // failure that was predicted — those are folded so the unpredicted ones stand out.
        const detail = el("details", {
          className: "finding-detail",
          open: kind !== "skipped" && kind !== "expected",
        }, [
          el("summary", {}, [
            pill(kind),
            ` ${suite.name}.${testCase.name} — ${version.okhttpVersion}`,
          ]),
          // The trace usually opens with the message verbatim, so printing both repeats the
          // one line that matters. Show the message alone only when it isn't already there.
          testCase.expectedReason
            ? el("p", { className: "expected-reason", textContent: testCase.expectedReason })
            : null,
          el("pre", {
            textContent:
              (testCase.detail?.startsWith(testCase.message)
                ? testCase.detail
                : [testCase.message, testCase.detail].filter(Boolean).join("\n\n")) ||
              "No detail recorded.",
          }),
          source
            ? el("p", { className: "card-label" }, [
                el("a", { href: source, textContent: `${suite.className} ↗` }),
              ])
            : null,
        ]);
        detail.dataset.kind = kind;
        items.push({ kind, detail });
      }
    }
  }

  items.sort((a, b) => rank[a.kind] - rank[b.kind]);

  const body = document.getElementById("failure-list");
  if (!items.length) {
    body.replaceChildren(
      el("p", { textContent: "Everything ran, and everything passed." }),
    );
  } else {
    body.replaceChildren(...items.map((item) => item.detail));
  }
}

function renderEndpoints(snapshot) {
  const endpoints = snapshot.endpoints || [];

  const head = el("tr", {}, [
    el("th", { textContent: "Server" }),
    el("th", { textContent: "Operator" }),
    el("th", { textContent: "State" }),
    el("th", { textContent: "Last reachable" }),
    el("th", { textContent: "Probe" }),
  ]);

  const rows = endpoints.map((endpoint) => {
    // An endpoint's state is not a test result, so it borrows the pills rather than owning a
    // vocabulary of its own: up reads as passing, down as failing. The consequence — which
    // suites got skipped — is in the table above.
    const up = endpoint.state === "up";
    return el("tr", {}, [
      el("td", { className: "suite", textContent: endpoint.server }),
      el("td", { textContent: endpoint.operator || "" }),
      el("td", {}, [
        el("span", {
          className: `pill ${up ? "passed" : "failed"}`,
          textContent: up ? "reachable" : "unreachable",
        }),
        endpoint.detail
          ? el("span", { className: "card-label", textContent: ` ${endpoint.detail}` })
          : null,
      ]),
      el("td", {
        className: "card-label",
        textContent: up
          ? "now"
          : endpoint.lastReachableAt
            ? formatWhen(endpoint.lastReachableAt)
            : "not since records began",
      }),
      el("td", { className: "mono", textContent: endpoint.target || "" }),
    ]);
  });

  const table = document.getElementById("endpoint-table");
  if (!rows.length) {
    table.replaceChildren(
      el("tbody", {}, el("tr", {}, el("td", { textContent: "No endpoints probed in this run." }))),
    );
  } else {
    table.replaceChildren(el("thead", {}, head), el("tbody", {}, rows));
  }
}

/*
 * What OkHttp's handshake offered, per version under test.
 *
 * Recorded rather than judged: the suite list is mostly the platform's decision, so this is a
 * table to read across releases rather than a thing to pass or fail. The one number worth
 * scanning for is a change.
 */
function renderClientHello(snapshot) {
  const recorded = snapshot.versions.filter((v) => v.clientHello && v.clientHello.observed);

  const rows = recorded.flatMap((version) =>
    Object.entries(version.clientHello.observed).map(([service, seen]) => {
      const suites = seen.given_cipher_suites || [];
      const groups = seen.given_named_groups || [];
      return el("tr", {}, [
        el("td", { className: "suite", textContent: version.okhttpVersion }),
        el("td", { className: "mono", textContent: version.clientHello.javaVersion || "" }),
        el("td", { className: "mono", textContent: seen.tls_version || "" }),
        el("td", {}, [
          // The service's own word, not ours. "Improvable" is what a client offering CBC suites
          // for compatibility gets, and that is worth showing rather than flattening to a tick.
          el("span", {
            className: `pill ${seen.rating === "Bad" ? "failed" : seen.rating === "Probably Okay" ? "passed" : "finding"}`,
            textContent: seen.rating || "unrated",
          }),
        ]),
        el("td", {}, [
          el("details", {}, [
            el("summary", { textContent: `${plural(suites.length, "suite")}, ${plural(groups.length, "group")}` }),
            el("pre", { textContent: suites.join("\n") }),
          ]),
        ]),
        el("td", { className: "card-label", textContent: service }),
      ]);
    }),
  );

  const table = document.getElementById("clienthello-table");
  if (!rows.length) {
    table.replaceChildren(
      el("tbody", {}, el("tr", {}, el("td", { textContent: "No handshake recorded in this run." }))),
    );
    return;
  }

  table.replaceChildren(
    el(
      "thead",
      {},
      el("tr", {}, [
        el("th", { textContent: "OkHttp" }),
        el("th", { textContent: "Java" }),
        el("th", { textContent: "Negotiated" }),
        el("th", { textContent: "Rating" }),
        el("th", { textContent: "Offered" }),
        el("th", { textContent: "Observed by" }),
      ]),
    ),
    el("tbody", {}, rows),
  );
}

/*
 * What each DoH resolver said about each name.
 *
 * The disagreement is the content. Quad9 and AdGuard filter, by design and by different rules,
 * so a row where they differ from Cloudflare and Google is a DNS policy result rather than a
 * defect — which is why this is a table and not a set of red cases. `sinkholed` is the one worth
 * looking for: an answer of 0.0.0.0 reaches a caller as a *successful* lookup that then fails to
 * connect, which is a much quieter failure than a resolution error.
 */
const DOH_OUTCOMES = {
  resolved: { status: "passed", text: "resolved" },
  sinkholed: { status: "finding", text: "sinkholed" },
  unresolved: { status: "expected", text: "no answer" },
  unavailable: { status: "skipped", text: "unavailable" },
};

function renderDohMatrix(snapshot) {
  // Recorded per run, so the newest version card that carries one is the current picture.
  const recorded = snapshot.versions.find((v) => v.dohMatrix && v.dohMatrix.names);
  const table = document.getElementById("doh-matrix-table");
  if (!table) return;

  const names = recorded ? Object.entries(recorded.dohMatrix.names) : [];
  if (!names.length) {
    table.replaceChildren(
      el("tbody", {}, el("tr", {}, el("td", { textContent: "No resolver matrix recorded in this run." }))),
    );
    return;
  }

  // Union rather than the first row's keys: a resolver added to the matrix mid-file still gets
  // a column, and one that answered nothing at all is still visibly in the table.
  const resolvers = [...new Set(names.flatMap(([, answers]) => Object.keys(answers)))];

  const rows = names.map(([hostname, answers]) =>
    el("tr", {}, [
      el("td", { className: "mono", textContent: hostname }),
      ...resolvers.map((resolver) => {
        const answer = answers[resolver];
        if (!answer) return el("td", { textContent: "—" });
        const outcome = DOH_OUTCOMES[answer.outcome] || { status: "unknown", text: answer.outcome };
        const addresses = answer.addresses || [];
        return el("td", {}, [
          el("span", {
            className: `pill ${outcome.status}`,
            textContent: outcome.text,
            // The addresses are what tell a withheld answer from an absent one by eye, but they
            // are far too wide for a cell — so they hang off the pill rather than off the table.
            title: addresses.length ? addresses.join("\n") : answer.detail || "",
          }),
        ]);
      }),
    ]),
  );

  table.replaceChildren(
    el(
      "thead",
      {},
      el("tr", {}, [
        el("th", { textContent: "Name" }),
        ...resolvers.map((resolver) => el("th", { textContent: resolver })),
      ]),
    ),
    el("tbody", {}, rows),
  );
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
      // `skipped` is guarded because history accumulates on the deployed site: rows published
      // before the field existed are still in the file and must not render "undefined skipped".
      const title = version
        ? `${when} ${okhttpVersion}: ${STATUS_TEXT[status]} — ` +
          `${version.passed} passed, ${version.failed} failed, ${version.skipped ?? 0} skipped`
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

/*
 * The open issues, grouped by their `area:` label.
 *
 * Area comes from the label rather than from a table here, so a new issue appears under the
 * right heading the moment it is filed and labelled — nothing to keep in step. An issue with no
 * `area:` label still shows up, under "unfiled", because silently dropping it would make this
 * section quietly wrong rather than visibly incomplete.
 */
const AREA_ORDER = ["infrastructure", "http", "tls", "dns", "ech"];

const AREA_TITLES = {
  infrastructure: "Infrastructure",
  http: "HTTP",
  tls: "TLS",
  dns: "DNS",
  ech: "Encrypted Client Hello",
  unfiled: "Unfiled",
};

function renderIssues(issues) {
  const labelsOf = (issue) => (issue.labels || []).map((l) => l.name);

  // The tracking issue is the roadmap itself, not an item on it.
  const tracking = issues.filter((i) => labelsOf(i).includes("tracking"));
  const items = issues.filter((i) => !labelsOf(i).includes("tracking"));

  const areaOf = (issue) => {
    const label = labelsOf(issue).find((name) => name.startsWith("area:"));
    return label ? label.slice("area:".length) : "unfiled";
  };

  const areas = [...new Set(items.map(areaOf))].sort((a, b) => {
    const rank = (x) => {
      const i = AREA_ORDER.indexOf(x);
      return i === -1 ? AREA_ORDER.length : i;
    };
    return rank(a) - rank(b) || a.localeCompare(b);
  });

  const groups = areas.map((area) => {
    const inArea = items.filter((i) => areaOf(i) === area).sort((a, b) => a.number - b.number);
    return el("section", { className: "issue-group" }, [
      el("h3", {}, [
        AREA_TITLES[area] || area,
        el("span", { className: "card-label", textContent: ` · ${plural(inArea.length, "issue")}` }),
      ]),
      el(
        "ul",
        { className: "issue-list" },
        inArea.map((issue) =>
          el("li", {}, [
            el("a", { href: issue.url, textContent: issue.title }),
            el("span", { className: "card-label mono", textContent: ` #${issue.number}` }),
          ]),
        ),
      ),
    ]);
  });

  const target = document.getElementById("roadmap");
  if (!items.length && !tracking.length) {
    target.replaceChildren(el("p", { textContent: "No open issues." }));
    return;
  }

  target.replaceChildren(
    ...tracking.map((issue) =>
      el("p", { className: "note" }, [
        "Tracked as a whole in ",
        el("a", { href: issue.url, textContent: issue.title }),
        ` (#${issue.number}).`,
      ]),
    ),
    el("div", { className: "issue-groups" }, groups),
  );
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
    renderEndpoints(snapshot);
    renderClientHello(snapshot);
    renderDohMatrix(snapshot);
  } catch (e) {
    document.getElementById("run-summary").replaceChildren(
      el("span", {}, [
        "No published results yet. They appear here after the ",
        el("a", {
          href: `${REPO}/actions/workflows/containers.yml`,
          textContent: "containers workflow",
        }),
        ` runs. (${e.message})`,
      ]),
    );
    // The sections are in the page unconditionally now, so they have to say something when
    // there is nothing to say. An empty table under a heading reads as a broken page.
    renderFailures({ versions: [] });
    renderEndpoints({ endpoints: [] });
    renderClientHello({ versions: [] });
    renderDohMatrix({ versions: [] });
  }

  try {
    renderHistory(await loadJson("data/history.json"));
  } catch {
    renderHistory({ runs: [] });
  }

  try {
    renderIssues(await loadJson("data/issues.json"));
  } catch {
    renderIssues([]);
  }
})();
