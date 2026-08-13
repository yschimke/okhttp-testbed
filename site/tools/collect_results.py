#!/usr/bin/env python3
"""Turn the JUnit XML the test workflow publishes into the JSON the status site reads.

Input is a directory of downloaded artifacts, one per version under test, laid out as the
`containers` workflow uploads them:

    <artifacts>/container-test-results-pinned/run-metadata.json
    <artifacts>/container-test-results-pinned/test/TEST-*.xml
    <artifacts>/container-test-results-pinned/loomTest/TEST-*.xml

Output is two files:

    <out>/latest.json   the run just finished, with every test case
    <out>/history.json  a summary per version per run, oldest first, capped

The Gradle task a suite ran under decides whether its result gates. `test` failing means
this repository is red; `loomTest` failing is a recorded finding about OkHttp, which is
why the build stays green — see "Suites that report rather than gate" in the README.
"""

from __future__ import annotations

import argparse
import json
import pathlib
import sys
import xml.etree.ElementTree as ElementTree

# Gradle test tasks whose failures are findings about OkHttp rather than breakage here.
REPORTING_TASKS = {"loomTest"}

# How many runs the history keeps. Enough for the trend strip to show a few weeks of
# daily runs without the file growing without bound.
HISTORY_LIMIT = 120


def parse_suite(path: pathlib.Path, task: str) -> dict:
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
        "task": task,
        "reporting": task in REPORTING_TASKS,
        "timeSeconds": float(root.get("time") or 0.0),
        "passed": sum(1 for c in cases if c["status"] == "passed"),
        "failed": sum(1 for c in cases if c["status"] == "failed"),
        "skipped": sum(1 for c in cases if c["status"] == "skipped"),
        "cases": sorted(cases, key=lambda c: c["name"]),
    }


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


def parse_version(directory: pathlib.Path) -> dict | None:
    """Read one downloaded artifact directory into a version record."""
    suites = []
    for xml in sorted(directory.glob("*/*.xml")) or sorted(directory.glob("*.xml")):
        task = xml.parent.name if xml.parent != directory else "test"
        try:
            suites.append(parse_suite(xml, task))
        except ElementTree.ParseError as e:
            print(f"skipping unreadable {xml}: {e}", file=sys.stderr)

    metadata_file = directory / "run-metadata.json"
    metadata = json.loads(metadata_file.read_text()) if metadata_file.exists() else {}

    # Fall back to the artifact name when a run predates run-metadata.json.
    label = metadata.get("label") or directory.name.replace("container-test-results-", "")

    if not suites and not metadata:
        return None

    suites.sort(key=lambda s: (s["reporting"], s["name"]))
    return {
        "label": label,
        "okhttpVersion": metadata.get("okhttpVersion", label),
        "javaVersion": metadata.get("javaVersion", ""),
        "jobStatus": metadata.get("jobStatus", ""),
        "status": roll_up([suite_status(s) for s in suites]),
        "passed": sum(s["passed"] for s in suites),
        "failed": sum(s["failed"] for s in suites),
        "skipped": sum(s["skipped"] for s in suites),
        "timeSeconds": round(sum(s["timeSeconds"] for s in suites), 1),
        "suites": suites,
    }


def summarise(run: dict) -> dict:
    """The compact form kept in history: counts and per-suite status, no case detail."""
    return {
        "runId": run["runId"],
        "runNumber": run["runNumber"],
        "commit": run["commit"],
        "finishedAt": run["finishedAt"],
        "runUrl": run["runUrl"],
        "event": run["event"],
        "status": run["status"],
        "versions": [
            {
                "label": v["label"],
                "okhttpVersion": v["okhttpVersion"],
                "status": v["status"],
                "passed": v["passed"],
                "failed": v["failed"],
                "skipped": v["skipped"],
                "suites": {s["name"]: suite_status(s) for s in v["suites"]},
            }
            for v in run["versions"]
        ],
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
    parser.add_argument("--run-id", default="")
    parser.add_argument("--run-number", default="0")
    parser.add_argument("--run-url", default="")
    parser.add_argument("--commit", default="")
    parser.add_argument("--event", default="")
    parser.add_argument("--finished-at", default="")
    args = parser.parse_args()

    versions = []
    for directory in sorted(p for p in args.artifacts.iterdir() if p.is_dir()):
        version = parse_version(directory)
        if version:
            versions.append(version)

    if not versions:
        print(f"no test results under {args.artifacts}", file=sys.stderr)
        return 1

    # The pinned release first, then snapshots, so the page reads release-to-snapshot.
    versions.sort(key=lambda v: ("SNAPSHOT" in v["okhttpVersion"], v["label"]))

    run = {
        "runId": args.run_id,
        "runNumber": int(args.run_number or 0),
        "runUrl": args.run_url,
        "commit": args.commit,
        "event": args.event,
        "finishedAt": args.finished_at,
        "status": roll_up([v["status"] for v in versions]),
        "versions": versions,
    }

    history = []
    if args.previous_history and args.previous_history.exists():
        try:
            history = json.loads(args.previous_history.read_text()).get("runs", [])
        except json.JSONDecodeError as e:
            print(f"ignoring unreadable history: {e}", file=sys.stderr)

    # A re-run of the same workflow run replaces its entry rather than adding one.
    history = [r for r in history if r.get("runId") != run["runId"]]
    history.append(summarise(run))
    history = history[-HISTORY_LIMIT:]

    args.out.mkdir(parents=True, exist_ok=True)
    (args.out / "latest.json").write_text(json.dumps(run, indent=2) + "\n")
    (args.out / "history.json").write_text(json.dumps({"runs": history}, indent=2) + "\n")

    print(f"{len(versions)} version(s), {len(history)} run(s) of history, status {run['status']}")
    return 0


if __name__ == "__main__":
    sys.exit(main())
