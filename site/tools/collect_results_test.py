import importlib.util
import json
import pathlib
import tempfile
import unittest


MODULE_PATH = pathlib.Path(__file__).with_name("collect_results.py")
SPEC = importlib.util.spec_from_file_location("collect_results", MODULE_PATH)
assert SPEC and SPEC.loader
collect_results = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(collect_results)


class CollectResultsTest(unittest.TestCase):
    def test_metadata_only_artifact_is_rejected(self):
        with tempfile.TemporaryDirectory() as temporary_dir:
            artifact = pathlib.Path(temporary_dir)
            (artifact / "run-metadata.json").write_text(
                json.dumps({"workflow": "android-ech", "okhttpVersion": "snapshot"})
            )

            self.assertIsNone(collect_results.parse_artifact(artifact))

    def test_tracked_android_37_1_ct_skip_is_expected(self):
        with tempfile.TemporaryDirectory() as temporary_dir:
            xml = pathlib.Path(temporary_dir) / "TEST-ct.xml"
            xml.write_text(
                """<testsuite name="okhttp.testbed.android.ech.CertificateTransparencyTest">
                <testcase name="unloggedCertificateIsRejectedWhenCtIsEnforced[emulator-5554 - 37]">
                  <skipped message="Known API 37.1 x86_64 emulator CT issue" />
                </testcase>
                </testsuite>"""
            )

            suite = collect_results.parse_suite(
                xml,
                "connectedAndroidTest",
                "android-ech",
                "https://example.test/run",
                "Android emulator API 37.1 · x86_64",
                "API 37.1",
            )

            self.assertEqual(1, suite["expected"])
            self.assertEqual(0, suite["skipped"])
            self.assertIn("issues/93", suite["cases"][0]["expectedReason"])

    def test_other_ct_variants_are_not_suppressed(self):
        self.assertEqual(
            "",
            collect_results.expected_reason(
                "CertificateTransparencyTest",
                "unloggedCertificateIsRejectedWhenCtIsEnforced",
                "Android emulator API 36 · x86_64",
                "API 36",
            ),
        )


if __name__ == "__main__":
    unittest.main()
