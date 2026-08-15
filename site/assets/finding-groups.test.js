const test = require("node:test");
const assert = require("node:assert/strict");

require("./finding-groups.js");
const { cluster, similarity, topicFor } = globalThis.FindingGroups;

function result({ suite = "PublicEncryptedClientHelloTest", method, message, detail, className }) {
  return {
    kind: "failed",
    version: { okhttpVersion: "5.5.0-SNAPSHOT" },
    suite: { name: `${suite} · API 37`, className: className || `example.${suite}` },
    testCase: { name: method, message, detail, className: className || `example.${suite}` },
  };
}

const networkTrace = (method, line = 285) => `java.net.ConnectException: Failed to connect to /1.1.1.1:443
  at okhttp3.internal.connection.ConnectPlan.connectSocket(ConnectPlan.kt:${line})
  at okhttp3.internal.connection.ConnectPlan.connectTcp(ConnectPlan.kt:144)
  at java.lang.Thread.run(Thread.java:1572)`;

test("groups different methods in one class when the exception and trace agree", () => {
  const a = result({ method: "cloudflareUsesEch", message: "java.net.ConnectException: Failed to connect to /1.1.1.1:443", detail: networkTrace("cloudflareUsesEch") });
  const b = result({ method: "echIsAcceptedOnDefoIe", message: "java.net.ConnectException: Failed to connect to /1.1.1.1:443", detail: networkTrace("echIsAcceptedOnDefoIe", 291) });
  assert.ok(similarity(a, b).score >= 65);
  assert.equal(cluster([a, b]).length, 1);
});

test("keeps unrelated assertions separate even when their class matches", () => {
  const a = result({ suite: "DnsFailureTest", method: "returnsUnknownHost", message: "expected UnknownHostException but was IOException", detail: "org.opentest4j.AssertionFailedError: expected UnknownHostException\n  at example.DnsFailureTest.returnsUnknownHost(DnsFailureTest.kt:40)" });
  const b = result({ suite: "DnsFailureTest", method: "preservesCause", message: "expected cause to be present", detail: "org.opentest4j.AssertionFailedError: expected cause\n  at example.DnsFailureTest.preservesCause(DnsFailureTest.kt:90)" });
  assert.ok(similarity(a, b).score < 65);
  assert.equal(cluster([a, b]).length, 2);
});

test("links a result to its suite on the relevant secondary page", () => {
  const dns = result({ suite: "DnsFailureTest", method: "returnsUnknownHost", message: "failure", detail: "" });
  assert.deepEqual(topicFor(dns), {
    label: "DNS",
    href: "topics/dns.html#result-dnsfailuretest-api-37-5-5-0-snapshot",
  });
});

test("links ECH findings to the complete ECH evidence matrix", () => {
  const ech = result({ method: "cloudflareUsesEch", message: "failure", detail: "" });
  assert.deepEqual(topicFor(ech), {
    label: "Encrypted Client Hello",
    href: "topics/ech.html#all-ech-results",
  });
});
