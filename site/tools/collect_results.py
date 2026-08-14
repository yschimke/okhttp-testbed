#!/usr/bin/env python3
"""Turn the JUnit XML the test workflows publish into the JSON the status site reads.

Input is a directory of downloaded artifacts, one per version per workflow, each carrying
the `run-metadata.json` its workflow wrote:

    <artifacts>/container-test-results-pinned/run-metadata.json
    <artifacts>/container-test-results-pinned/test/TEST-*.xml
    <artifacts>/container-test-results-pinned/loomTest/TEST-*.xml
    <artifacts>/android-ech-test-results-pinned-snapshot/run-metadata.json
    <artifacts>/android-ech-test-results-pinned-snapshot/outputs/androidTest-results/…/*.xml

The network suite adds one more file per Gradle task, written by its reachability preflight
rather than by JUnit:

    <artifacts>/network-test-results-pinned/endpoints-networkTest.json

Output is two files:

    <out>/latest.json   the current picture, with every test case
    <out>/history.json  a summary per collection, oldest first, capped

Results are keyed by the OkHttp version under test, not by workflow: a container suite and
an Android suite both testing 5.5.0-SNAPSHOT belong on one card, because comparing versions
is what this repository is for.

The Gradle task a suite ran under decides whether its result gates. `test` failing means
this repository is red; `loomTest`, `echTest` and `networkTest` failing are recorded
findings — about OkHttp, about the platform, or about a server someone else operates — which
is why the build stays green. See "Suites that report rather than gate" in the README.

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
import sys
import xml.etree.ElementTree as ElementTree

# Gradle test tasks whose failures are findings about OkHttp rather than breakage here.
REPORTING_TASKS = {"loomTest", "echTest", "echConscryptTest", "networkTest"}

# The same distinction for suites that can't make it with a task name. Android instrumentation
# runs under one task whatever it is testing, so the Android suite that calls tls-ech.dev and
# defo.ie has no way to say it reports rather than gates except by being named here. Everything
# else in the Android module runs against containers this repository starts.
REPORTING_CLASSES = {"PublicEncryptedClientHelloTest"}

# How many collections the history keeps. Enough for the trend strip to show a few weeks of
# daily runs without the file growing without bound.
HISTORY_LIMIT = 120


def parse_suite(path: pathlib.Path, task: str, workflow: str, run_url: str) -> dict:
    """Read one JUnit XML file into a suite record."""
    root = ElementTree.parse(path).getroot()
    # Gradle writes a single <testsuite> per file, but a <testsuites> wrapper is legal.
    if root.tag == "testsuites":
        root = root.find("testsuite") or root

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

        cases.append(
            {
                "name": case.get("name", ""),
                "className": case.get("classname", ""),
                "status": status,
                "timeSeconds": float(case.get("time") or 0.0),
                "message": message,
                # The first lines carry the assertion; the rest is JUnit's own frames.
                "detail": "\n".join(trace.splitlines()[:20]),
            }
        )

    simple_name = root.get("name", path.stem).rsplit(".", 1)[-1]
    return {
        "name": simple_name,
        "className": root.get("name", ""),
        "workflow": workflow,
        "runUrl": run_url,
        "task": task,
        "reporting": task in REPORTING_TASKS or simple_name in REPORTING_CLASSES,
        "timeSeconds": float(root.get("time") or 0.0),
        "passed": sum(1 for c in cases if c["status"] == "passed"),
        "failed": sum(1 for c in cases if c["status"] == "failed"),
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


def suite_status(suite: dict) -> str:
    if suite["failed"]:
        return "finding" if suite["reporting"] else "failed"
    if suite["passed"]:
        return "passed"
    return "skipped"


def roll_up(statuses: list[str]) -> str:
    """The worst status in a set, with findings ranked below outright failure."""
    for candidate in ("failed", "finding", "passed", "skipped"):
        if candidate in statuses:
            return candidate
    return "unknown"


def parse_artifact(directory: pathlib.Path) -> dict | None:
    """Read one downloaded artifact directory: its metadata and every suite in it."""
    metadata_file = directory / "run-metadata.json"
    metadata = json.loads(metadata_file.read_text()) if metadata_file.exists() else {}

    workflow = metadata.get("workflow", "unknown")
    run_url = metadata.get("runUrl", "")

    # The container workflow lays its XML out by Gradle task, which is the distinction the
    # page needs. The Android suite lays it out by device instead, so its task comes from
    # the metadata rather than from the path.
    default_task = metadata.get("task", "test")
    task_from_path = metadata.get("taskFromPath", False)

    suites = []
    for xml in sorted(directory.rglob("*.xml")):
        task = xml.parent.name if task_from_path and xml.parent != directory else default_task
        try:
            suites.append(parse_suite(xml, task, workflow, run_url))
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
        "javaVersion": metadata.get("javaVersion", ""),
        "jobStatus": metadata.get("jobStatus", ""),
        "runNumber": metadata.get("runNumber", 0),
        "runUrl": run_url,
        "commit": metadata.get("commit", ""),
        "event": metadata.get("event", ""),
        "finishedAt": metadata.get("finishedAt", ""),
        "suites": suites,
        "endpoints": parse_endpoints(directory),
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
                "javaVersion": artifact["javaVersion"],
                "workflows": [],
                "suites": [],
            },
        )
        if artifact["workflow"] not in version["workflows"]:
            version["workflows"].append(artifact["workflow"])
        version["suites"].extend(artifact["suites"])

    for version in versions.values():
        suites = version["suites"]
        # Gating suites first, then findings, alphabetically within each.
        suites.sort(key=lambda s: (s["reporting"], s["name"]))
        version["status"] = roll_up([suite_status(s) for s in suites])
        version["passed"] = sum(s["passed"] for s in suites)
        version["failed"] = sum(s["failed"] for s in suites)
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
