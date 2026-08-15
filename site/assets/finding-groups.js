/* Pure helpers for relating findings. Kept separate from status.js so the scoring can be
 * exercised with Node without inventing a browser test harness. */
(function (root) {
  "use strict";

  const WEIGHTS = { testClass: 25, testMethod: 15, exception: 15, message: 20, stacktrace: 25 };
  const DEFAULT_THRESHOLD = 65;

  const baseSuiteName = (suite) => (suite.name || "").split(" · ")[0];

  function words(value) {
    return new Set((value || "")
      .replace(/([a-z])([A-Z])/g, "$1 $2")
      .toLowerCase()
      .replace(/0x[0-9a-f]+/g, " <hex> ")
      .replace(/\b\d+(?:\.\d+)*\b/g, " <n> ")
      .match(/[a-z_$<>]+/g) || []);
  }

  function jaccard(left, right) {
    if (!left.size && !right.size) return 0;
    let intersection = 0;
    for (const value of left) if (right.has(value)) intersection += 1;
    return intersection / (left.size + right.size - intersection);
  }

  function exceptionName(item) {
    const text = `${item.testCase.message || ""}\n${item.testCase.detail || ""}`;
    const match = text.match(/(?:^|\n|Caused by:\s+|Suppressed:\s+)([\w.$]+(?:Exception|Error))(?::|\s|$)/);
    return match ? match[1] : "";
  }

  function stackFrames(item) {
    const frames = new Set();
    for (const line of (item.testCase.detail || "").split("\n")) {
      const match = line.match(/^\s*at\s+([^\s(]+)/);
      if (match) frames.add(match[1].replace(/\$\d+/g, () => "$<n>"));
    }
    return frames;
  }

  function testClass(item) {
    return item.testCase.className || item.suite.className || baseSuiteName(item.suite);
  }

  function similarity(left, right) {
    const leftException = exceptionName(left);
    const rightException = exceptionName(right);
    const components = {
      testClass: testClass(left) && testClass(left) === testClass(right) ? 1 : 0,
      testMethod: jaccard(words(left.testCase.name), words(right.testCase.name)),
      exception: leftException && leftException === rightException ? 1 : 0,
      message: jaccard(words(left.testCase.message), words(right.testCase.message)),
      stacktrace: jaccard(stackFrames(left), stackFrames(right)),
    };
    const score = Math.round(Object.entries(WEIGHTS)
      .reduce((total, [name, weight]) => total + components[name] * weight, 0));
    return { score, components };
  }

  /* Complete-link clustering: a new result must clear the threshold against every member.
   * That deliberately avoids a chain of weak similarities turning into one giant incident. */
  function cluster(items, threshold = DEFAULT_THRESHOLD) {
    const groups = [];
    for (const item of items) {
      let best = null;
      for (const group of groups) {
        const matches = group.items.map((member) => similarity(item, member).score);
        const minimum = Math.min(...matches);
        const average = matches.reduce((sum, score) => sum + score, 0) / matches.length;
        if (minimum >= threshold && (!best || average > best.average)) {
          best = { group, average };
        }
      }
      if (best) best.group.items.push(item);
      else groups.push({ items: [item] });
    }

    for (const group of groups) {
      group.representative = group.items
        .map((item) => ({
          item,
          average: group.items.reduce((sum, other) => sum + similarity(item, other).score, 0) / group.items.length,
        }))
        .sort((a, b) => b.average - a.average)[0].item;
      group.matches = new Map(group.items.map((item) => [item, similarity(group.representative, item)]));
    }
    return groups;
  }

  const ECH_SUITES = /^(?:EncryptedClientHello|PublicEncryptedClientHello|Ech(?:Conscrypt|ClientHello|Grease)?|ClientHelloExtensions)Test$/i;
  const TOPICS = [
    [ECH_SUITES, "ech", "Encrypted Client Hello", "all-ech-results"],
    [/(?:Dns|Doh|HttpsRecord|HappyEyeballs|SvcParam)/i, "dns", "DNS", "current-results"],
    [/(?:Loom)/i, "loom", "Virtual threads", "current-results"],
    [/(?:Proxy)/i, "proxies", "Proxies", "current-results"],
    [/(?:Tls|ConnectionSpec|Certificate|Pinning|BadChain|ClientHello|Sni|Alpn|LetsEncrypt|MockServer)/i, "tls", "TLS", "current-results"],
    [/(?:GoHttpbin|HttpSemantics|Hostile|Http2|AltSvc|TestServer)/i, "test-servers", "Test servers", "current-results"],
  ];

  function topicFor(item) {
    const name = baseSuiteName(item.suite);
    const match = TOPICS.find(([pattern]) => pattern.test(name));
    if (!match) return null;
    const [, slug, label, defaultAnchor] = match;
    const anchor = slug === "ech"
      ? defaultAnchor
      : `result-${`${item.suite.name}-${item.version.okhttpVersion}`.toLowerCase()
        .replace(/[^a-z0-9]+/g, "-").replace(/^-|-$/g, "")}`;
    return { label, href: `topics/${slug}.html#${anchor}` };
  }

  root.FindingGroups = {
    DEFAULT_THRESHOLD,
    WEIGHTS,
    cluster,
    exceptionName,
    similarity,
    topicFor,
  };
})(typeof globalThis === "undefined" ? window : globalThis);
