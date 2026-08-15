/* Live evidence for every topic page except ECH.
 *
 * Topic prose explains a result; it must not become a second, hand-maintained source of truth.
 * This file selects the relevant suites and observations from the same latest.json used by the
 * status page, then puts that evidence before the documentation.
 */

const TOPIC_STATUS_TEXT = {
  passed: "passing",
  failed: "failing",
  finding: "findings",
  expected: "expected",
  skipped: "not run",
  unknown: "unknown",
};

const TOPICS = {
  dns: {
    suites: /(?:Dns|Doh|HttpsRecord|HappyEyeballs|SvcParam)/i,
    empty: "No DNS suites were present in the latest published result.",
  },
  tls: {
    suites: /(?:Tls|ConnectionSpec|Certificate|Pinning|BadChain|ClientHello|Sni|Alpn|LetsEncrypt|MockServer)/i,
    empty: "No TLS suites were present in the latest published result.",
  },
  proxies: {
    suites: /(?:Proxy)/i,
    empty: "No proxy suites were present in the latest published result.",
  },
  loom: {
    suites: /(?:Loom)/i,
    empty: "No virtual-thread suites were present in the latest published result.",
  },
  "test-servers": {
    suites: /(?:GoHttpbin|HttpSemantics|Hostile|Http2|MockServer|AltSvc|BadChain|ClientCertificate|LetsEncrypt|Proxy)/i,
    empty: "No test-server suites were present in the latest published result.",
  },
};

const topicEl = (tag, props = {}, children = []) => {
  const node = Object.assign(document.createElement(tag), props);
  for (const child of [].concat(children)) if (child != null) node.append(child);
  return node;
};

const topicPill = (status, text = TOPIC_STATUS_TEXT[status] || status) =>
  topicEl("span", { className: `pill ${status}`, textContent: text });

function suiteStatus(suite) {
  if (suite.failed) return suite.reporting && suite.severity !== "critical" ? "finding" : "failed";
  if (suite.expected) return "expected";
  if (suite.passed) return "passed";
  return "skipped";
}

function sourceUrl(suite) {
  const roots = {
    containers: "containers/src/test/kotlin",
    network: "network/src/test/kotlin",
    "android-ech": "android-ech/src/androidTest/kotlin",
  };
  const root = roots[suite.workflow];
  return root && suite.className
    ? `https://github.com/yschimke/okhttp-testbed/blob/main/${root}/${suite.className.replaceAll(".", "/")}.kt`
    : "";
}

function renderRunSummary(snapshot, selected) {
  const target = document.getElementById("topic-run-summary");
  const workflows = new Set(selected.map(({ suite }) => suite.workflow));
  const runs = (snapshot.collectedFrom || []).filter((run) => workflows.has(run.workflow));
  const newest = runs.map((run) => run.finishedAt).filter(Boolean).sort().at(-1);
  const statuses = selected.map(({ suite }) => suiteStatus(suite));
  const status = statuses.includes("failed") ? "failed"
    : statuses.includes("finding") ? "finding"
    : statuses.includes("expected") ? "expected"
    : statuses.includes("passed") ? "passed" : "skipped";

  target.replaceChildren(
    topicPill(status),
    topicEl("span", {}, [
      `${selected.length} relevant suite${selected.length === 1 ? "" : "s"}`,
      newest ? ` · latest evidence ${new Date(newest).toISOString().slice(0, 16).replace("T", " ")} UTC` : "",
    ]),
    ...runs.map((run) => run.runUrl
      ? topicEl("a", { href: run.runUrl, textContent: `${run.workflow} #${run.runNumber || "?"} ↗` })
      : null),
  );
}

function renderSuites(snapshot, config) {
  const selected = snapshot.versions.flatMap((version) =>
    (version.suites || [])
      .filter((suite) => config.suites.test(suite.name))
      .map((suite) => ({ version: version.okhttpVersion, suite })),
  );
  renderRunSummary(snapshot, selected);

  const target = document.getElementById("topic-results");
  if (!selected.length) {
    target.replaceChildren(topicEl("p", { textContent: config.empty }));
    return;
  }

  target.replaceChildren(...selected.map(({ version, suite }) => {
    const status = suiteStatus(suite);
    const source = sourceUrl(suite);
    const total = (suite.cases || []).length;
    const resultId = `result-${`${suite.name}-${version}`.toLowerCase().replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "")}`;
    return topicEl("details", { className: "topic-result", id: resultId, open: status !== "passed" }, [
      topicEl("summary", {}, [
        topicPill(status),
        topicEl("span", { className: "suite-name", textContent: suite.name }),
        topicEl("span", {
          className: "card-label",
          textContent: `${version} · ${suite.passed}/${total} passed · ${suite.timeSeconds}s`,
        }),
      ]),
      topicEl("div", { className: "result-meta" }, [
        topicEl("span", { className: "mono", textContent: `${suite.workflow} · ${suite.task}` }),
        source ? topicEl("a", { href: source, textContent: "test source ↗" }) : null,
        suite.runUrl ? topicEl("a", { href: suite.runUrl, textContent: "workflow run ↗" }) : null,
      ]),
      topicEl("ul", { className: "case-list" }, (suite.cases || []).map((testCase) =>
        topicEl("li", {}, [
          topicPill(testCase.status),
          topicEl("code", { textContent: testCase.name }),
          testCase.expectedReason
            ? topicEl("span", { className: "case-detail", textContent: testCase.expectedReason })
            : testCase.message
              ? topicEl("span", { className: "case-detail", textContent: testCase.message })
              : null,
        ]),
      )),
    ]);
  }));
}

function renderEndpoints(snapshot) {
  const target = document.getElementById("topic-endpoints");
  if (!target) return;
  const rows = snapshot.endpoints || [];
  target.replaceChildren(...(rows.length ? rows.map((endpoint) =>
    topicEl("div", { className: "endpoint-row" }, [
      topicPill(endpoint.state === "up" ? "passed" : "failed", endpoint.state === "up" ? "reachable" : "unreachable"),
      topicEl("code", { textContent: endpoint.server }),
      topicEl("span", { className: "card-label", textContent: endpoint.operator || endpoint.target || "" }),
    ]),
  ) : [topicEl("p", { textContent: "No endpoint probes were recorded in this run." })]));
}

function renderDnsMatrix(snapshot) {
  const target = document.getElementById("topic-observations");
  if (!target) return;
  const version = snapshot.versions.find((item) => item.dohMatrix?.names);
  if (!version) {
    target.replaceChildren(topicEl("p", { textContent: "No resolver observations were recorded in this run." }));
    return;
  }
  const names = Object.entries(version.dohMatrix.names);
  const resolvers = [...new Set(names.flatMap(([, answers]) => Object.keys(answers)))];
  const outcome = {
    resolved: ["passed", "resolved"], sinkholed: ["finding", "sinkholed"],
    unresolved: ["expected", "no answer"], errored: ["finding", "resolver error"],
    unavailable: ["skipped", "unavailable"],
  };
  target.replaceChildren(topicEl("div", { className: "table-scroll" }, topicEl("table", {}, [
    topicEl("thead", {}, topicEl("tr", {}, [topicEl("th", { textContent: "Name" }), ...resolvers.map((r) => topicEl("th", { textContent: r }))])),
    topicEl("tbody", {}, names.map(([name, answers]) => topicEl("tr", {}, [
      topicEl("td", { className: "mono", textContent: name }),
      ...resolvers.map((resolver) => {
        const answer = answers[resolver];
        const display = answer ? (outcome[answer.outcome] || ["unknown", answer.outcome]) : ["unknown", "—"];
        return topicEl("td", {}, topicPill(display[0], display[1]));
      }),
    ]))),
  ])));
}

function renderTlsObservations(snapshot) {
  const target = document.getElementById("topic-observations");
  if (!target) return;
  const cards = [];
  for (const version of snapshot.versions) {
    for (const record of version.tlsPolicy || []) {
      cards.push(topicEl("article", { className: "observation-card" }, [
        topicEl("strong", { textContent: `${version.okhttpVersion} · ${record.platform || record.javaVersion || "platform"}` }),
        ...Object.entries(record.checks || {}).map(([name, check]) => topicEl("p", {}, [
          topicPill(check.accepted ? "expected" : "skipped", check.accepted ? "accepted" : "refused"), ` ${name}`,
        ])),
      ]));
    }
    if (version.clientHello?.observed) {
      for (const [service, seen] of Object.entries(version.clientHello.observed)) {
        cards.push(topicEl("article", { className: "observation-card" }, [
          topicEl("strong", { textContent: `${version.okhttpVersion} · ClientHello` }),
          topicEl("p", {}, [topicPill(seen.rating === "Bad" ? "failed" : "finding", seen.rating || "unrated"), ` ${seen.tls_version || "TLS"} observed by ${service}`]),
        ]));
      }
    }
  }
  target.replaceChildren(...(cards.length ? cards : [topicEl("p", { textContent: "No TLS observations were recorded in this run." })]));
}

(async () => {
  const topic = document.body.dataset.topic;
  const config = TOPICS[topic];
  if (!config) return;
  try {
    const response = await fetch("../data/latest.json", { cache: "no-cache" });
    if (!response.ok) throw new Error(`HTTP ${response.status}`);
    const snapshot = await response.json();
    renderSuites(snapshot, config);
    if (topic === "dns") renderDnsMatrix(snapshot);
    if (topic === "tls") renderTlsObservations(snapshot);
    renderEndpoints(snapshot);
  } catch (error) {
    document.getElementById("topic-run-summary").replaceChildren(
      topicEl("span", {}, ["Published results are not available yet. ", topicEl("a", { href: "../data/latest.json", textContent: "Open latest.json" }), ` (${error.message})`]),
    );
    document.getElementById("topic-results").replaceChildren(topicEl("p", { textContent: "No live evidence to display." }));
  }
})();
