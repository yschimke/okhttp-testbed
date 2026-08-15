#!/usr/bin/env python3

import importlib.util
import pathlib
import sys
import unittest


SCRIPT = pathlib.Path(__file__).with_name("job_reruns.py")
SPEC = importlib.util.spec_from_file_location("job_reruns", SCRIPT)
job_reruns = importlib.util.module_from_spec(SPEC)
assert SPEC.loader
sys.modules[SPEC.name] = job_reruns
SPEC.loader.exec_module(job_reruns)


class JobRerunsTest(unittest.TestCase):
    def test_only_known_checked_workflows_are_selected(self):
        body = """\
- [x] `network`
- [X] `containers`
- [ ] `android-ech`
- [x] `not-a-workflow`
"""

        self.assertEqual(
            ["containers", "network"],
            [workflow.name for workflow in job_reruns.selected_workflows(body)],
        )

    def test_checked_workflow_is_dispatched_only_once(self):
        body = "- [x] `network`\n- [x] `network`\n"

        self.assertEqual(
            ["network"],
            [workflow.name for workflow in job_reruns.selected_workflows(body)],
        )

    def test_latest_run_ignores_pushes_and_uses_newest_full_run(self):
        requests = []

        class FakeApi(job_reruns.GitHubApi):
            def __init__(self):
                pass

            def request(self, method, path, payload=None):
                requests.append((method, path, payload))
                if "event=schedule" in path:
                    return {
                        "workflow_runs": [
                            {"event": "schedule", "created_at": "2026-08-14T10:00:00Z"}
                        ]
                    }
                return {
                    "workflow_runs": [
                        {
                            "event": "workflow_dispatch",
                            "created_at": "2026-08-15T10:00:00Z",
                        }
                    ]
                }

        run = FakeApi().latest_run(job_reruns.WORKFLOWS_BY_NAME["network"])

        self.assertEqual("workflow_dispatch", run["event"])
        self.assertEqual(2, len(requests))
        self.assertTrue(all(method == "GET" for method, _, _ in requests))
        self.assertTrue(all("branch=main" in path for _, path, _ in requests))
        self.assertTrue(all(payload is None for _, _, payload in requests))

    def test_dashboard_requires_title_label_and_marker(self):
        valid = {
            "title": job_reruns.ISSUE_TITLE,
            "labels": [{"name": job_reruns.ISSUE_LABEL}],
            "body": job_reruns.MARKER,
        }

        self.assertTrue(job_reruns.is_dashboard_issue(valid))
        self.assertFalse(job_reruns.is_dashboard_issue({**valid, "labels": []}))
        self.assertFalse(job_reruns.is_dashboard_issue({**valid, "body": "copied issue"}))

    def test_render_clears_checkboxes_and_links_completed_run(self):
        runs = {
            "network": {
                "status": "completed",
                "conclusion": "success",
                "updated_at": "2026-08-15T10:50:00Z",
                "html_url": "https://example.test/runs/123",
            }
        }

        body = job_reruns.render_body(runs, {"containers": "⏳ dispatch requested"})

        self.assertNotIn("- [x]", body.lower())
        self.assertIn("[2026-08-15 10:50 UTC](https://example.test/runs/123)", body)
        self.assertIn("| `network` | Daily at 10:43 UTC", body)
        self.assertIn("| `containers` | Daily at 02:17 UTC | — | ⏳ dispatch requested |", body)


if __name__ == "__main__":
    unittest.main()
