#!/usr/bin/env python3
"""Turn the JUnit XML the test workflows publish into the JSON the status site reads.

Input is a directory of downloaded artifacts, one per version per workflow, each carrying
the `run-metadata.json` its workflow wrote:

    <artifacts>/container-test-results-pinned-jdk21/run-metadata.json
    <artifacts>/container-test-results-pinned-jdk21/test/TEST-*.xml
    <artifacts>/container-test-results-pinned-jdk21/loomTest/TEST-*.xml
    <artifacts>/android-ech-test-results-pinned-snapshot-api37.0/run-metadata.json
    <artifacts>/android-ech-test-results-pinned-snapshot-api37.0/outputs/androidTest-results/…/*.xml

One artifact per version *per variant*: the suites run across several JDKs and several emulator
API levels, and each of those is its own job and its own artifact. The variant a job ran on is
in its `run-metadata.json` and becomes part of every suite name it contributes, so a version
card carries one row per suite per variant rather than one row that keeps changing its mind.

The network suite adds two more files per Gradle task, written by the suites themselves
rather than by JUnit — what it found reachable, and what its handshake offered:

    <artifacts>/network-test-results-pinned/endpoints-networkTest.json
    <artifacts>/network-test-results-pinned/clienthello-networkTest.json
    <artifacts>/network-test-results-pinned/doh-matrix-networkTest.json
    <artifacts>/network-test-results-pinned/altsvc-networkTest.json
    <artifacts>/network-test-results-pinned/tlspolicy-networkTest.json
    <artifacts>/network-test-results-pinned/ech-echTest.json

Output is two files:

    <out>/latest.json   the current picture, with every test case
    <out>/history.json  a summary per collection, oldest first, capped

Results are keyed by the OkHttp version under test, not by workflow: a container suite and
an Android suite both testing 5.5.0-SNAPSHOT belong on one card, because comparing versions
is what this repository is for.

The Gradle task a suite ran under decides whether its result gates. `test` failing means
this repository is red; `loomTest`, `hostileTest`, `echTest` and `networkTest` failing are
recorded findings — about OkHttp, about the platform, or about a server someone else operates —
which is why the build stays green. See "Suites that report rather than gate" in the README.

Not gating is not the same as not mattering, and the two used to be conflated: every suite
calling a server somebody else runs reported in amber, so an ECH regression and a badssl.com
outage looked alike. Severity separates them. A `critical` suite is one this repository is
currently *for* — an unexpected failure there is the headline, and turns the page red — while a
`watch` suite stays amber because a failure is as likely to be the far end as the client. Both
still record rather than gate: the build's colour is not what changes, the page's is.

Endpoint availability is collected separately from results, and deliberately so: a public
test server that has gone away should read as *unavailable* rather than as OkHttp failing.
The preflight reports each endpoint's state, the tests that need a down endpoint skip
themselves, and the page can then be read as "the DNS suite is amber" against "Quad9 has
been unreachable for three days".
"""

from __future__ import annotations

import argparse
import json
import pathlib
import re
import sys
import xml.etree.ElementTree as ElementTree

# Gradle test tasks whose failures are findings about OkHttp rather than breakage here.
REPORTING_TASKS = {
    "loomTest",
    "hostileTest",
    "echTest",
    "echConscryptTest",
    "echPlatformTest",
    "networkTest",
}

# The same distinction for suites that can't make it with a task name. Android instrumentation
# runs under one task whatever it is testing, so the Android suite that calls tls-ech.dev and
# defo.ie has no way to say it reports rather than gates except by being named here. Everything
# else in the Android module runs against containers this repository starts.
REPORTING_CLASSES = {"PublicEncryptedClientHelloTest"}

# What this repository is currently trying to find out. Everything here reports rather than
# gates, exactly as before; severity decides only how loudly an *unexpected* failure is shown.
#
# ECH is the work in flight, so its suites are critical: a case that passed yesterday and fails
# today is the most interesting thing on the page, and burying it in the same amber as a flaky
# third-party server is how a real regression goes unnoticed for a week. Move a suite back to
# `watch` when its question has been answered and it is only being kept honest.
#
# Expected failures are unaffected — a predicted failure is amber whatever its suite's severity,
# because it is not news. So is a skip: an endpoint that has gone away reads as unavailable, not
# as a client that broke.
CRITICAL_SUITES = {
    "EchTest",
    "EchConscryptTest",
    "EchClientHelloTest",
    "PublicEncryptedClientHelloTest",
}


def severity_of(suite_name: str) -> str:
    return "critical" if suite_name in CRITICAL_SUITES else "watch"

# Failures that are the point rather than the problem.
#
# A suite here is asking a question whose answer is currently "no", and saying so is why it
# exists — EchTest reports that OkHttp can't do ECH on the JVM, and a green EchTest would mean
# the finding had been lost, not that the bug was fixed. Those failures are shown, and shown in
# amber, but folded away: the page's red is reserved for a result nobody predicted.
#
# The reason is required, and is what the page shows instead of a stack trace. Cases are named
# individually rather than by wildcard on purpose — a new case in one of these suites should
# arrive as an unexpected failure and be looked at, not inherit an excuse written for its
# neighbours.
EXPECTED_FAILURES = {
    "EchTest": {
        f"{case}{platform_suffix}": (
            "OkHttp's ConscryptPlatform takes the ECH config list and drops it, and no released "
            "Conscrypt has the method for it to call — so this cannot pass until a Conscrypt "
            "after 2.7.0 ships and OkHttp can compile against it. EchPlatformTest runs the same "
            "request with that one call added."
        )
        for case in (
            "cloudflareUsesEch",
            "echIsAcceptedOnTlsEchDev",
            "echIsAcceptedOnDefoIe",
            "echIsRetriedOnStaleTlsEchDev",
            "tlsIsNotUsedOnTls12TlsEchDev",
        )
        # Older OkHttp versions ran EchTest only on the ordinary JVM platform, before the suite
        # became parameterised. Their JUnit names therefore have no `JDK` suffix, but describe
        # the same expected limitation. Only CONSCRYPT_ECH exercises EchConscryptPlatform.
        for platform_suffix in ("", " JDK")
    }
    | {
        # The retry cases can't pass on either platform: falling back after a rejection needs
        # SSL_get0_ech_retry_configs, which Conscrypt exposes on Android and discards on the JVM.
        # Keyed separately from the JDK ones because the reason is a different one.
        f"{case} CONSCRYPT_ECH": (
            "Falling back after a server rejects ECH needs the retry configs read back, which "
            "takes SSL_get0_ech_retry_configs. Conscrypt exposes that on Android and discards it "
            "on the JVM, so no platform written here can fall back rather than fail."
        )
        for case in (
            "echIsRetriedOnStaleTlsEchDev",
            "tlsIsNotUsedOnTls12TlsEchDev",
        )
    },
    "EchConscryptTest": {
        "tls12IsReachedWithoutEch": (
            "Falling back after a server rejects ECH needs the retry configs read back, which "
            "takes SSL_get0_ech_retry_configs. Conscrypt exposes that on Android and discards it "
            "on the JVM, so no client here can fall back rather than fail."
        ),
    },
}


def normalise_case(name: str) -> str:
    """The case name with the machinery stripped off, for matching and for display.

    Two dialects arrive here. A parameterised JVM case is `cloudflareUsesEch(TlsPlatform) JDK`,
    where the parameter list is noise and the trailing argument is the thing that identifies it.
    An Android case is `cloudflareUsesEch[emulator-5554 - 17]`, where the device is noise too —
    the platform is already recorded per suite, and putting the emulator's serial number in a
    test's name only makes two runs of the same test look like different tests.
    """
    name = re.sub(r"\[[^\]]*\]\s*$", "", name)
    name = re.sub(r"\([^)]*\)", "", name)
    return " ".join(name.split())


def expected_reason(suite_name: str, case_name: str) -> str:
    """Why this case failing is the expected answer, or empty if it isn't."""
    return EXPECTED_FAILURES.get(suite_name, {}).get(case_name, "")


# How many collections the history keeps. Enough for the trend strip to show a few weeks of
# daily runs without the file growing without bound.
HISTORY_LIMIT = 120


def parse_suite(
    path: pathlib.Path,
    task: str,
    workflow: str,
    run_url: str,
    platform: str,
    variant: str = "",
) -> dict:
    """Read one JUnit XML file into a suite record.

    `variant` is what the run was testing *on* rather than what it was testing — a JDK for the
    container and network suites, an emulator API level for the Android one. It becomes part of
    the suite's name because the status page keys its rows by that name, and one version card
    now merges several runs of the same suite: without it, `GoHttpbinTest` on JDK 17 and on JDK
    25 are one row, and whichever was read last silently wins.
    """
    root = ElementTree.parse(path).getroot()
    # Gradle writes a single <testsuite> per file, but a <testsuites> wrapper is legal.
    if root.tag == "testsuites":
        root = root.find("testsuite") or root

    simple_name = root.get("name", path.stem).rsplit(".", 1)[-1]

    cases = []
    for case in root.iter("testcase"):
        failure = case.find("failure")
        error = case.find("error")
        skipped = case.find("skipped")

        if failure is not None or error is not None:
            detail = failure if failure is not None else error
            status = "failed"
            message = detail.get("message") or ""
            trace = (detail.text or "").strip()
        elif skipped is not None:
            status = "skipped"
            message = skipped.get("message") or ""
            trace = ""
        else:
            status = "passed"
            message = ""
            trace = ""

        raw_name = case.get("name", "")
        case_name = normalise_case(raw_name)
        reason = expected_reason(simple_name, case_name) if status == "failed" else ""
        if reason:
            status = "expected"

        cases.append(
            {
                "name": case_name,
                # Kept so a name on the page can still be found in the XML it came from.
                "rawName": raw_name,
                "expectedReason": reason,
                "className": case.get("classname", ""),
                "status": status,
                "timeSeconds": float(case.get("time") or 0.0),
                "message": message,
                # The first lines carry the assertion; the rest is JUnit's own frames.
                "detail": "\n".join(trace.splitlines()[:20]),
            }
        )

    return {
        "name": f"{simple_name} · {variant}" if variant else simple_name,
        "className": root.get("name", ""),
        "workflow": workflow,
        "runUrl": run_url,
        "task": task,
        # Carried per suite rather than per version: a version card merges an Android artifact
        # and a JVM one, and "which platform" is then a property of the suite, not of the card.
        "platform": platform,
        "reporting": task in REPORTING_TASKS or simple_name in REPORTING_CLASSES,
        "severity": severity_of(simple_name),
        "timeSeconds": float(root.get("time") or 0.0),
        "passed": sum(1 for c in cases if c["status"] == "passed"),
        # `failed` stays the count of failures nobody predicted, so every existing reader — the
        # roll-up, the history, the cards — keeps treating it as the number that matters.
        "failed": sum(1 for c in cases if c["status"] == "failed"),
        "expected": sum(1 for c in cases if c["status"] == "expected"),
        "skipped": sum(1 for c in cases if c["status"] == "skipped"),
        "cases": sorted(cases, key=lambda c: c["name"]),
    }


def parse_endpoints(directory: pathlib.Path) -> list[dict]:
    """Read every endpoints-<task>.json an artifact carries.

    One file per Gradle task, because two tasks in the same module would otherwise write
    over each other. An endpoint probed by both is reported once; a `down` reading wins over
    an `up` one, since a server that failed anybody's probe is not one to trust a result to.
    """
    merged: dict[str, dict] = {}
    for report in sorted(directory.glob("endpoints-*.json")):
        try:
            probed = json.loads(report.read_text())
        except json.JSONDecodeError as e:
            print(f"skipping unreadable {report}: {e}", file=sys.stderr)
            continue

        for endpoint in probed.get("endpoints", []):
            existing = merged.get(endpoint["id"])
            if existing is None or (existing["state"] == "up" and endpoint["state"] != "up"):
                merged[endpoint["id"]] = dict(endpoint, probedAt=probed.get("probedAt", ""))

    return sorted(merged.values(), key=lambda e: e["id"])


def merge_endpoints(artifacts: list[dict], history: list[dict], finished_at: str) -> list[dict]:
    """One entry per endpoint: its state now, and when it was last seen reachable.

    "Last reachable" can only come from the runs before this one, so it is read back out of
    the published history rather than kept anywhere else — the same trick the history itself
    uses. An endpoint that has never been up in the window the history covers reports an
    empty `lastReachableAt`, which the page shows as "not since records began" rather than
    inventing a time for it.
    """
    endpoints: dict[str, dict] = {}
    for artifact in artifacts:
        for endpoint in artifact["endpoints"]:
            # Same rule as within an artifact: any probe that failed makes the endpoint down.
            existing = endpoints.get(endpoint["id"])
            if existing is None or (existing["state"] == "up" and endpoint["state"] != "up"):
                endpoints[endpoint["id"]] = dict(endpoint)

    for endpoint in endpoints.values():
        if endpoint["state"] == "up":
            endpoint["lastReachableAt"] = finished_at
            continue

        endpoint["lastReachableAt"] = next(
            (
                run["finishedAt"]
                for run in reversed(history)
                if run.get("endpoints", {}).get(endpoint["id"]) == "up"
            ),
            "",
        )

    return sorted(endpoints.values(), key=lambda e: (e["state"] == "up", e["id"]))


def parse_client_hello(directory: pathlib.Path) -> dict | None:
    """Read the ClientHello record an artifact carries, if it has one.

    One file per Gradle task, as with the endpoint report, but unlike endpoints these do not
    merge: each says what a handshake offered, and two tasks in one module offer the same
    thing. The first readable one wins.
    """
    for report in sorted(directory.glob("clienthello-*.json")):
        try:
            return json.loads(report.read_text())
        except json.JSONDecodeError as e:
            print(f"skipping unreadable {report}: {e}", file=sys.stderr)
    return None


def parse_doh_matrix(directory: pathlib.Path) -> dict | None:
    """Read what each DoH resolver said about each name, if the artifact carries it.

    Same one-file-per-task rule and same first-readable-wins as the ClientHello record: the
    resolvers answer the same way whichever task asked them.
    """
    for report in sorted(directory.glob("doh-matrix-*.json")):
        try:
            return json.loads(report.read_text())
        except json.JSONDecodeError as e:
            print(f"skipping unreadable {report}: {e}", file=sys.stderr)
    return None


def parse_alt_svc(directory: pathlib.Path) -> dict | None:
    """Read which origins offered HTTP/3 and what OkHttp used instead, if recorded.

    Same one-file-per-task rule and same first-readable-wins as the other records.
    """
    for report in sorted(directory.glob("altsvc-*.json")):
        try:
            return json.loads(report.read_text())
        except json.JSONDecodeError as e:
            print(f"skipping unreadable {report}: {e}", file=sys.stderr)
    return None


def parse_tls_policy(directory: pathlib.Path) -> dict | None:
    """Read what this platform did about revocation, pinning and CT, if recorded.

    Per platform rather than per version, which is the axis these answers vary on — but stored
    per artifact like the rest, since the platform is in the artifact's metadata.
    """
    for report in sorted(directory.glob("tlspolicy-*.json")):
        try:
            return json.loads(report.read_text())
        except json.JSONDecodeError as e:
            print(f"skipping unreadable {report}: {e}", file=sys.stderr)
    return None


def parse_ech_results(directory: pathlib.Path, platform: str, variant: str) -> list[dict]:
    """Read ECHConfigLists captured during connection attempts.

    Android writes one file per instrumentation suite and the JVM writes one per Gradle task,
    so all readable files are merged. Platform and variant come from run metadata rather than
    from test code; that keeps an emulator/JDK image change visible without changing the suite.
    """
    observations = []
    for report in sorted(directory.rglob("ech-*.json")):
        try:
            parsed = json.loads(report.read_text())
        except json.JSONDecodeError as e:
            print(f"skipping unreadable {report}: {e}", file=sys.stderr)
            continue
        for observation in parsed.get("observations", []):
            observations.append(
                {
                    **observation,
                    "platformDescription": platform,
                    "variant": variant,
                    "task": parsed.get("task", ""),
                }
            )
    return observations


def suite_status(suite: dict) -> str:
    if suite["failed"]:
        # Red for a suite that gates, and for one this repository is currently about. Amber for
        # a suite being kept honest, where the far end is as likely a cause as the client.
        if not suite["reporting"] or suite.get("severity") == "critical":
            return "failed"
        return "finding"
    if suite.get("expected"):
        return "expected"
    if suite["passed"]:
        return "passed"
    return "skipped"


def roll_up(statuses: list[str]) -> str:
    """The worst status in a set, with findings ranked below outright failure."""
    for candidate in ("failed", "finding", "expected", "passed", "skipped"):
        if candidate in statuses:
            return candidate
    return "unknown"


def parse_artifact(directory: pathlib.Path) -> dict | None:
    """Read one downloaded artifact directory: its metadata and every suite in it."""
    metadata_file = directory / "run-metadata.json"
    metadata = json.loads(metadata_file.read_text()) if metadata_file.exists() else {}

    workflow = metadata.get("workflow", "unknown")
    run_url = metadata.get("runUrl", "")
    # Runs from before this was recorded fall back to the Java version they did carry.
    platform = metadata.get("platform") or (
        f"JDK {metadata['javaVersion']}" if metadata.get("javaVersion") else ""
    )

    # The container workflow lays its XML out by Gradle task, which is the distinction the
    # page needs. The Android suite lays it out by device instead, so its task comes from
    # the metadata rather than from the path.
    default_task = metadata.get("task", "test")
    task_from_path = metadata.get("taskFromPath", False)
    # Runs from before the matrices went wide carry no variant, and read as they always did.
    variant = metadata.get("variant", "")

    suites = []
    for xml in sorted(directory.rglob("*.xml")):
        task = xml.parent.name if task_from_path and xml.parent != directory else default_task
        try:
            suites.append(parse_suite(xml, task, workflow, run_url, platform, variant))
        except ElementTree.ParseError as e:
            print(f"skipping unreadable {xml}: {e}", file=sys.stderr)

    if not suites and not metadata:
        return None

    # Fall back to the artifact name when a run predates run-metadata.json.
    label = metadata.get("label") or directory.name.split("test-results-")[-1]

    return {
        "workflow": workflow,
        "label": label,
        "okhttpVersion": metadata.get("okhttpVersion", label),
        "platform": platform,
        "variant": variant,
        "jobStatus": metadata.get("jobStatus", ""),
        "runNumber": metadata.get("runNumber", 0),
        "runUrl": run_url,
        "commit": metadata.get("commit", ""),
        "event": metadata.get("event", ""),
        "finishedAt": metadata.get("finishedAt", ""),
        "suites": suites,
        "endpoints": parse_endpoints(directory),
        "clientHello": parse_client_hello(directory),
        "dohMatrix": parse_doh_matrix(directory),
        "altSvc": parse_alt_svc(directory),
        "tlsPolicy": parse_tls_policy(directory),
        "echResults": parse_ech_results(directory, platform, variant),
    }


def group_by_version(artifacts: list[dict]) -> list[dict]:
    """Merge every artifact's suites under the OkHttp version it was testing."""
    versions: dict[str, dict] = {}
    for artifact in artifacts:
        version = versions.setdefault(
            artifact["okhttpVersion"],
            {
                "okhttpVersion": artifact["okhttpVersion"],
                "label": artifact["label"],
                "platforms": [],
                "workflows": [],
                "suites": [],
                "clientHello": None,
                "dohMatrix": None,
                "altSvc": None,
                "tlsPolicy": [],
                "echResults": [],
            },
        )
        if artifact["workflow"] not in version["workflows"]:
            version["workflows"].append(artifact["workflow"])
        if artifact["platform"] and artifact["platform"] not in version["platforms"]:
            version["platforms"].append(artifact["platform"])
        version["suites"].extend(artifact["suites"])
        # Most of a ClientHello is the platform's rather than OkHttp's, so the record is only
        # meaningful next to the version that produced it.
        if artifact["clientHello"] and not version.get("clientHello"):
            version["clientHello"] = artifact["clientHello"]
        # What the resolvers said is about the resolvers, but it is recorded per run, so it
        # belongs to the version whose run recorded it rather than to the page as a whole.
        if artifact["dohMatrix"] and not version.get("dohMatrix"):
            version["dohMatrix"] = artifact["dohMatrix"]
        # What the origin offered is the origin's, but what was negotiated is the version's.
        if artifact["altSvc"] and not version.get("altSvc"):
            version["altSvc"] = artifact["altSvc"]
        # Kept per platform rather than first-wins: revocation and CT answers differ by JDK and by
        # Android release, and that difference is the entire point of recording them.
        if artifact["tlsPolicy"]:
            version["tlsPolicy"].append(
                {"platform": artifact["platform"] or "unknown", **artifact["tlsPolicy"]}
            )
        version["echResults"].extend(artifact["echResults"])

    for version in versions.values():
        suites = version["suites"]
        # Gating suites first, then findings, alphabetically within each.
        suites.sort(key=lambda s: (s["reporting"], s["name"]))
        version["status"] = roll_up([suite_status(s) for s in suites])
        version["passed"] = sum(s["passed"] for s in suites)
        version["failed"] = sum(s["failed"] for s in suites)
        version["expected"] = sum(s["expected"] for s in suites)
        version["skipped"] = sum(s["skipped"] for s in suites)
        version["timeSeconds"] = round(sum(s["timeSeconds"] for s in suites), 1)

    # The pinned release first, then snapshots, so the page reads release-to-snapshot.
    return sorted(
        versions.values(),
        key=lambda v: ("SNAPSHOT" in v["okhttpVersion"], v["okhttpVersion"]),
    )


def summarise(snapshot: dict) -> dict:
    """The compact form kept in history: counts and per-suite status, no case detail."""
    return {
        "key": snapshot["key"],
        "collectedFrom": snapshot["collectedFrom"],
        "finishedAt": snapshot["finishedAt"],
        "status": snapshot["status"],
        "versions": [
            {
                "okhttpVersion": v["okhttpVersion"],
                "status": v["status"],
                "passed": v["passed"],
                "failed": v["failed"],
                "expected": v["expected"],
                "skipped": v["skipped"],
                "suites": {s["name"]: suite_status(s) for s in v["suites"]},
            }
            for v in snapshot["versions"]
        ],
        # Just the state, keyed by id: this is what the next run reads back to work out when an
        # endpoint was last reachable, so it has to survive in history even though the page
        # renders the richer form out of latest.json.
        "endpoints": {e["id"]: e["state"] for e in snapshot["endpoints"]},
    }


def main() -> int:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--artifacts", required=True, type=pathlib.Path)
    parser.add_argument("--out", required=True, type=pathlib.Path)
    parser.add_argument(
        "--previous-history",
        type=pathlib.Path,
        help="history.json from the deployed site, if it could be fetched",
    )
    args = parser.parse_args()

    artifacts = []
    for directory in sorted(p for p in args.artifacts.iterdir() if p.is_dir()):
        artifact = parse_artifact(directory)
        if artifact:
            artifacts.append(artifact)

    if not artifacts:
        print(f"no test results under {args.artifacts}", file=sys.stderr)
        return 1

    versions = group_by_version(artifacts)

    # One entry per workflow that contributed, so the page can say where each half of the
    # picture came from and how old it is.
    collected_from = []
    for artifact in artifacts:
        if any(r["runUrl"] == artifact["runUrl"] for r in collected_from):
            continue
        collected_from.append(
            {
                "workflow": artifact["workflow"],
                "runNumber": artifact["runNumber"],
                "runUrl": artifact["runUrl"],
                "commit": artifact["commit"],
                "event": artifact["event"],
                "finishedAt": artifact["finishedAt"],
                # A job can fail before its tests produce any XML at all — an emulator that
                # never boots, a container daemon that never starts. Saying so is more
                # honest than a card with no suites and no explanation.
                "jobStatus": artifact["jobStatus"],
                "suiteCount": len(artifact["suites"]),
            }
        )
    collected_from.sort(key=lambda r: r["workflow"])

    # Read before the snapshot is built, not after: an endpoint that is down now was last
    # reachable at some point in the published history, and there is nowhere else to learn it.
    history = []
    if args.previous_history and args.previous_history.exists():
        try:
            history = json.loads(args.previous_history.read_text()).get("runs", [])
        except json.JSONDecodeError as e:
            print(f"ignoring unreadable history: {e}", file=sys.stderr)

    finished_at = max((r["finishedAt"] for r in collected_from), default="")

    snapshot = {
        # Identifies this combination of runs, so republishing the same one replaces its
        # history entry rather than adding a second.
        "key": "+".join(f"{r['workflow']}#{r['runNumber']}" for r in collected_from),
        "collectedFrom": collected_from,
        "finishedAt": finished_at,
        "status": roll_up([v["status"] for v in versions]),
        "versions": versions,
        "endpoints": merge_endpoints(artifacts, history, finished_at),
    }

    history = [r for r in history if r.get("key") != snapshot["key"]]
    history.append(summarise(snapshot))
    history = history[-HISTORY_LIMIT:]

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "latest.json").write_text(json.dumps(snapshot, indent=2) + "\n")
    (args.out / "history.json").write_text(json.dumps({"runs": history}, indent=2) + "\n")

    down = [e["id"] for e in snapshot["endpoints"] if e["state"] != "up"]

    print(
        f"{len(versions)} version(s) from {len(collected_from)} run(s), "
        f"{len(history)} entries of history, status {snapshot['status']}, "
        f"{len(snapshot['endpoints'])} endpoint(s) probed"
        + (f", down: {', '.join(down)}" if down else "")
    )
    return 0


if __name__ == "__main__":
    sys.exit(main())
