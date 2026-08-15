#!/usr/bin/env python3
"""Maintain the scheduled-job dashboard issue and dispatch checked workflows."""

from __future__ import annotations

import json
import os
import re
import sys
import urllib.error
import urllib.parse
import urllib.request
from dataclasses import dataclass
from datetime import datetime, timezone
from typing import Any


ISSUE_TITLE = "Job re-runs"
ISSUE_LABEL = "job-reruns"
MARKER = "<!-- job-reruns-dashboard -->"


@dataclass(frozen=True)
class ScheduledWorkflow:
    name: str
    filename: str
    schedule: str


WORKFLOWS = (
    ScheduledWorkflow("containers", "containers.yml", "Daily at 02:17 UTC"),
    ScheduledWorkflow("test-server", "test-server.yml", "Daily at 06:41 UTC"),
    ScheduledWorkflow("network", "network.yml", "Daily at 10:43 UTC"),
    ScheduledWorkflow("android-ech", "android-ech.yml", "Daily at 14:47 UTC"),
)
WORKFLOWS_BY_NAME = {workflow.name: workflow for workflow in WORKFLOWS}


class ApiError(RuntimeError):
    def __init__(self, status: int, message: str):
        super().__init__(f"GitHub API returned {status}: {message}")
        self.status = status


class GitHubApi:
    def __init__(self, token: str, repository: str):
        self.repository = repository
        self.base_url = f"https://api.github.com/repos/{repository}"
        self.headers = {
            "Accept": "application/vnd.github+json",
            "Authorization": f"Bearer {token}",
            "User-Agent": "okhttp-testbed-job-reruns",
            "X-GitHub-Api-Version": "2022-11-28",
        }

    def request(
        self, method: str, path: str, payload: dict[str, Any] | None = None
    ) -> Any:
        data = json.dumps(payload).encode() if payload is not None else None
        request = urllib.request.Request(
            f"{self.base_url}{path}", data=data, headers=self.headers, method=method
        )
        try:
            with urllib.request.urlopen(request, timeout=30) as response:
                body = response.read()
                return json.loads(body) if body else None
        except urllib.error.HTTPError as error:
            message = error.read().decode(errors="replace")
            raise ApiError(error.code, message) from error

    def ensure_label(self) -> None:
        try:
            self.request(
                "POST",
                "/labels",
                {
                    "name": ISSUE_LABEL,
                    "color": "1d76db",
                    "description": "Interactive dashboard for scheduled job re-runs",
                },
            )
        except ApiError as error:
            if error.status != 422:  # The label already exists.
                raise

    def dashboard_issue(self) -> dict[str, Any] | None:
        label = urllib.parse.quote(ISSUE_LABEL)
        issues = self.request("GET", f"/issues?state=open&labels={label}&per_page=100")
        return next(
            (
                issue
                for issue in issues
                if "pull_request" not in issue
                and issue.get("title") == ISSUE_TITLE
                and MARKER in issue.get("body", "")
            ),
            None,
        )

    def create_issue(self, body: str) -> dict[str, Any]:
        return self.request(
            "POST",
            "/issues",
            {"title": ISSUE_TITLE, "body": body, "labels": [ISSUE_LABEL]},
        )

    def update_issue(self, number: int, body: str) -> None:
        self.request("PATCH", f"/issues/{number}", {"body": body})

    def dispatch(self, workflow: ScheduledWorkflow) -> None:
        filename = urllib.parse.quote(workflow.filename, safe="")
        self.request(
            "POST", f"/actions/workflows/{filename}/dispatches", {"ref": "main"}
        )

    def latest_run(self, workflow: ScheduledWorkflow) -> dict[str, Any] | None:
        filename = urllib.parse.quote(workflow.filename, safe="")
        # A push to main can create a smaller per-commit run. This dashboard is specifically
        # about the full scheduled matrix, so show only scheduled and on-demand executions.
        runs = []
        for event in ("schedule", "workflow_dispatch"):
            response = self.request(
                "GET",
                f"/actions/workflows/{filename}/runs?branch=main&event={event}&per_page=1",
            )
            runs.extend(response.get("workflow_runs", []))
        return max(runs, key=lambda run: run.get("created_at", ""), default=None)


def selected_workflows(body: str) -> list[ScheduledWorkflow]:
    """Return only known workflow names from checked dashboard task-list items."""
    checked = set(re.findall(r"^- \[[xX]\] `([^`]+)`\s*$", body, flags=re.MULTILINE))
    return [workflow for workflow in WORKFLOWS if workflow.name in checked]


def is_dashboard_issue(issue: dict[str, Any]) -> bool:
    labels = {
        label["name"] if isinstance(label, dict) else label
        for label in issue.get("labels", [])
    }
    return (
        issue.get("title") == ISSUE_TITLE
        and ISSUE_LABEL in labels
        and MARKER in issue.get("body", "")
    )


def timestamp(value: str | None) -> str:
    if not value:
        return "—"
    parsed = datetime.fromisoformat(value.replace("Z", "+00:00"))
    return parsed.astimezone(timezone.utc).strftime("%Y-%m-%d %H:%M UTC")


def run_result(run: dict[str, Any] | None) -> tuple[str, str]:
    if not run:
        return "—", "Never run"

    status = run.get("status", "unknown")
    conclusion = run.get("conclusion")
    updated = run.get("updated_at") or run.get("created_at")
    url = run.get("html_url", "")
    when = timestamp(updated)
    linked_when = f"[{when}]({url})" if url else when

    if status != "completed":
        return linked_when, f"⏳ {status.replace('_', ' ')}"

    icons = {
        "success": "✅",
        "failure": "❌",
        "cancelled": "⏹️",
        "skipped": "⏭️",
        "timed_out": "⌛",
        "action_required": "⚠️",
        "neutral": "➖",
    }
    label = (conclusion or "unknown").replace("_", " ")
    return linked_when, f"{icons.get(conclusion, '❔')} {label}"


def render_body(
    runs: dict[str, dict[str, Any] | None],
    dispatch_results: dict[str, str] | None = None,
) -> str:
    dispatch_results = dispatch_results or {}
    rows = []
    for workflow in WORKFLOWS:
        last_run, result = run_result(runs.get(workflow.name))
        if workflow.name in dispatch_results:
            result = dispatch_results[workflow.name]
        rows.append(
            f"| `{workflow.name}` | {workflow.schedule} | {last_run} | {result} |"
        )

    checkboxes = "\n".join(f"- [ ] `{workflow.name}`" for workflow in WORKFLOWS)
    return "\n".join(
        [
            MARKER,
            "This issue shows the latest run of each scheduled job. To run one now, "
            "tick its checkbox below.",
            "",
            "## Last runs",
            "",
            "| Job | Schedule | Last run | Result |",
            "| --- | --- | --- | --- |",
            *rows,
            "",
            "## Run now",
            "",
            "The checked jobs are queued independently. The boxes are cleared when the request "
            "is accepted, and this table is updated as each job completes.",
            "",
            checkboxes,
            "",
            "---",
            "_This issue is maintained automatically by the "
            "[job-reruns workflow](../actions/workflows/job-reruns.yml). Do not edit its title, "
            "label, or generated sections._",
            "",
        ]
    )


def load_event() -> dict[str, Any]:
    path = os.environ.get("GITHUB_EVENT_PATH")
    if not path:
        return {}
    with open(path, encoding="utf-8") as event_file:
        return json.load(event_file)


def main() -> int:
    token = os.environ["GH_TOKEN"]
    repository = os.environ["GH_REPO"]
    event_name = os.environ.get("GITHUB_EVENT_NAME", "")
    event = load_event()
    api = GitHubApi(token, repository)

    # Ignore edits to every other issue. In particular, a copied marker in an ordinary issue
    # must not become a way for an untrusted issue author to dispatch Actions.
    event_issue = event.get("issue")
    if event_name == "issues" and (
        not event_issue or not is_dashboard_issue(event_issue)
    ):
        print("Ignoring edit to an issue other than the job re-runs dashboard")
        return 0

    api.ensure_label()
    issue = api.dashboard_issue()
    if event_name == "issues" and (
        not issue or issue.get("number") != event_issue.get("number")
    ):
        print("Ignoring edit to a non-canonical job re-runs issue")
        return 0
    selected = selected_workflows(issue.get("body", "")) if issue else []

    dispatch_results: dict[str, str] = {}
    failures: list[str] = []
    for workflow in selected:
        try:
            api.dispatch(workflow)
            dispatch_results[workflow.name] = "⏳ dispatch requested"
            print(f"Dispatched {workflow.filename}")
        except ApiError as error:
            dispatch_results[workflow.name] = "❌ dispatch failed"
            failures.append(f"{workflow.name}: {error}")

    runs = {workflow.name: api.latest_run(workflow) for workflow in WORKFLOWS}
    body = render_body(runs, dispatch_results)
    if issue:
        if body != issue.get("body", ""):
            api.update_issue(issue["number"], body)
            print(f"Updated issue #{issue['number']}")
        else:
            print(f"Issue #{issue['number']} is already current")
    else:
        issue = api.create_issue(body)
        print(f"Created issue #{issue['number']}")

    if failures:
        raise RuntimeError("; ".join(failures))
    return 0


if __name__ == "__main__":
    sys.exit(main())
