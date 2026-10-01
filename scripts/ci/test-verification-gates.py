"""Regression tests for fail-closed CI report guards."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import subprocess
import sys
import tempfile
import unittest
from zipfile import ZipFile


def module(filename):
    spec = importlib.util.spec_from_file_location(filename, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


conformance = module("check-conformance-results.py")
security = module("check-required-tests.py")
selector = module("select-benchmark-baseline.py")
comparison = module("compare-project-index-benchmarks.py")
installed = module("check-installed-connector.py")


class VerificationGatesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.baseline = self.root / "baseline.yml"
        self.baseline.write_text("server:\n" + "".join(
            "  - " + name + "\n" for name in sorted(conformance.BASELINE_SCENARIOS)), encoding="utf-8")
        for scenario in conformance.SCENARIOS:
            if scenario in conformance.BASELINE_SCENARIOS:
                checks = [{"id": "fixture-behavior", "status": "FAILURE"}]
            elif scenario == "server-sse-polling":
                checks = [{"id": check, "status": "WARNING"}
                          for check in sorted(conformance.POLLING_CHECKS)]
            else:
                checks = [{"id": check, "status": "SUCCESS"}
                          for check in sorted(conformance.REQUIRED_CHECKS[scenario])]
            self.report(scenario, checks)

    def path(self, scenario):
        return self.root / ("server-" + scenario + "-2026-09-29T00-00-00Z") / "checks.json"

    def report(self, scenario, checks):
        path = self.path(scenario)
        path.parent.mkdir(exist_ok=True)
        path.write_text(json.dumps(checks), encoding="utf-8")

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()):
            conformance.check(self.root, self.baseline)

    def assert_rejected(self):
        with self.assertRaises((SystemExit, ValueError)):
            self.check()

    def test_valid_reports_with_three_polling_warnings_pass(self):
        self.check()

    def test_real_polling_event_sequence_passes(self):
        # IDs/statuses from runner 0.2.0-alpha.11's retained SSE polling report.
        events = [
            ("outgoing-request", "INFO"),
            ("incoming-response", "INFO"),
            ("incoming-sse-event", "INFO"),
            ("stream-closed", "INFO"),
            ("server-sse-priming-event", "WARNING"),
            ("server-sse-retry-field", "WARNING"),
            ("outgoing-request", "INFO"),
            ("incoming-response", "INFO"),
            ("server-sse-disconnect-resume", "WARNING"),
        ]
        self.report("server-sse-polling", [{"id": key, "status": status}
                                           for key, status in events])
        self.check()

    def test_info_events_cannot_hide_failed_or_duplicate_checks(self):
        for status in ("FAILURE", "SUCCESS"):
            with self.subTest(status=status):
                self.report("ping", [
                    {"id": "ping", "status": status},
                    {"id": "ping", "status": "INFO"},
                    {"id": "ping", "status": "SUCCESS"},
                    {"id": "wire-schema-valid", "status": "SUCCESS"},
                ])
                self.assert_rejected()

    def test_missing_and_duplicate_scenario_fail(self):
        self.path("ping").unlink()
        self.assert_rejected()
        self.report("ping", [{"id": check, "status": "SUCCESS"}
                             for check in conformance.REQUIRED_CHECKS["ping"]])
        duplicate = self.root / "server-ping-2026-09-30T00-00-00Z"
        duplicate.mkdir()
        (duplicate / "checks.json").write_bytes(self.path("ping").read_bytes())
        self.assert_rejected()

    def test_auxiliary_success_cannot_replace_behavior(self):
        self.report("ping", [{"id": "auxiliary", "status": "SUCCESS"},
                             {"id": "wire-schema-valid", "status": "SUCCESS"}])
        self.assert_rejected()

    def test_required_check_bad_status_fails(self):
        for status in ("WARNING", "SKIPPED", "FAILURE", "UNKNOWN", "INFO"):
            with self.subTest(status=status):
                self.report("tools-list", [{"id": check, "status": status if check == "tools-list" else "SUCCESS"}
                                           for check in conformance.REQUIRED_CHECKS["tools-list"]])
                self.assert_rejected()

    def test_polling_absent_or_bad_status_fails(self):
        for status in (None, "SKIPPED", "FAILURE", "UNKNOWN", "INFO"):
            with self.subTest(status=status):
                self.report("server-sse-polling", [] if status is None else
                            [{"id": check, "status": status if check == "server-sse-retry-field" else "SUCCESS"}
                             for check in conformance.POLLING_CHECKS])
                self.assert_rejected()

    def test_unlisted_failure_and_stale_baseline_fail(self):
        self.report("ping", [{"id": check, "status": "SUCCESS"}
                             for check in conformance.REQUIRED_CHECKS["ping"]] +
                    [{"id": "extra", "status": "FAILURE"}])
        self.assert_rejected()
        self.report("ping", [{"id": check, "status": "SUCCESS"}
                             for check in conformance.REQUIRED_CHECKS["ping"]])
        self.report("json-schema-2020-12", [{"id": "fixture", "status": "SUCCESS"}])
        self.assert_rejected()

    def test_wire_schema_failure_is_never_excused(self):
        self.report("json-schema-2020-12", [{"id": "fixture", "status": "FAILURE"},
                                             {"id": "wire-schema-valid", "status": "FAILURE"}])
        self.assert_rejected()

    def test_malformed_and_empty_reports_fail(self):
        for contents in ("[]", "{}", "not-json", '[{"status":"SUCCESS"}]',
                         '[{"id":"x","status":"SUCCESS"},{"id":"x","status":"SUCCESS"}]'):
            with self.subTest(contents=contents):
                self.path("ping").write_text(contents, encoding="utf-8")
                self.assert_rejected()

    def test_empty_report_directory_fails(self):
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(SystemExit), contextlib.redirect_stdout(io.StringIO()):
                conformance.check(empty, self.baseline)

    def test_benchmark_rejection_preserves_existing_evidence(self):
        script = Path(__file__).with_name("full-hop-benchmark.py")
        for existing_error in (False, True):
            with self.subTest(existing_error=existing_error):
                output = self.root / ("benchmark-" + str(existing_error))
                output.mkdir()
                (output / "report.md").write_bytes(b"original report\n")
                if existing_error:
                    (output / "error.json").write_bytes(b'{"error":"original failure"}\n')
                before = {p.name: p.read_bytes() for p in output.iterdir()}
                result = subprocess.run(
                    [sys.executable, str(script), "--output", str(output),
                     "--runtime", str(self.root / "unused-runtime"),
                     "--fixture", str(self.root / "unused-fixture"),
                     "--java-home", str(self.root / "unused-java"),
                     "--baseline", str(self.root / "unused-baseline.zip"),
                     "--candidate", str(self.root / "unused-candidate.zip")],
                    capture_output=True, text=True, timeout=15,
                )
                self.assertEqual(1, result.returncode)
                self.assertIn("Refusing to overwrite benchmark output directory", result.stderr)
                self.assertEqual(before, {p.name: p.read_bytes() for p in output.iterdir()})

    def test_security_skips_do_not_count_as_passes(self):
        xml = "<testsuite>"
        for classname, name in security.REQUIRED:
            xml += '<testcase classname="' + classname + '" name="' + name + '"><skipped/></testcase>'
        xml += "</testsuite>"
        (self.root / "TEST-security.xml").write_text(xml, encoding="utf-8")
        with self.assertRaises(SystemExit):
            security.check(self.root)
        (self.root / "TEST-security.xml").write_text(xml.replace("<skipped/>", ""), encoding="utf-8")
        with contextlib.redirect_stdout(io.StringIO()):
            security.check(self.root)

    def test_baseline_selector_uses_newest_published_ancestor(self):
        releases = [{"tagName": tag, "isDraft": draft, "isPrerelease": pre}
                    for tag, draft, pre in (("v2.2.2", False, False),
                                            ("v2.2.3", False, False),
                                            ("v2.2.4-rc1", False, True),
                                            ("v2.2.5", True, False),
                                            ("v2.2.1", False, False))]
        resolve = lambda tag: tag
        ancestor = lambda old, candidate: old in {"v2.2.2", "v2.2.3"}
        self.assertEqual(("v2.2.3", "v2.2.3"),
                         selector.select(releases, "new-commit", "", resolve, ancestor))
        self.assertEqual(("v2.2.2", "v2.2.2"),
                         selector.select(releases, "v2.2.3", "", resolve, ancestor))
        self.assertEqual(("v2.2.2", "v2.2.2"),
                         selector.select(releases, "new-commit", "v2.2.2", resolve, ancestor))
        for tag in ("v2.2.1", "v2.2.4-rc1", "v2.2.5", "bad"):
            with self.subTest(tag=tag), self.assertRaises(ValueError):
                selector.select(releases, "new-commit", tag, resolve, ancestor)
        with self.assertRaises(ValueError):
            selector.select(releases + [releases[0]], "new-commit", "", resolve, ancestor)

    def test_modern_mixed_baseline_must_be_complete(self):
        rows = {}
        for scenario, phase in comparison.EXPECTED_ROWS:
            if scenario.startswith("definitions-"):
                size = int(scenario.split("-")[1])
                rows[(scenario, phase)] = {
                    "files_available": size, "definitions": size, "files_examined": size,
                    "truncated": False, "node_count": 64,
                    "refreshes": 5 if phase == "warm_median_x5" else 1,
                    "cache_hits": size if phase == "warm_median_x5" else size - 1 if phase == "one_file_changed" else 0,
                    "cache_misses": 0 if phase == "warm_median_x5" else 1 if phase == "one_file_changed" else size,
                }
            else:
                size = 20_000 if scenario.startswith("mixed") or scenario.endswith("20000") else 10_000
                rows[(scenario, phase)] = {"files_available": size, "definitions": 5_000,
                                           "files_examined": size, "truncated": not scenario.startswith("mixed")}
        comparison.validate_run({"rows_by_key": rows}, "baseline")
        rows[("mixed-20k-files-5k-definitions", "cold_bounds")]["truncated"] = True
        with self.assertRaises(ValueError):
            comparison.validate_run({"rows_by_key": rows}, "baseline")

    def test_installed_jar_requires_exact_zip_version_and_hash(self):
        package = self.root / "connector.zip"
        jar = self.root / "connector.jar"
        with ZipFile(package, "w") as archive:
            archive.writestr("plugins/misc/hop-mcp-connector/version.xml", "<version>2.2.3</version>")
            archive.writestr("plugins/misc/hop-mcp-connector/hop-mcp-connector-2.2.3.jar", b"product")
        with self.assertRaises(ValueError):
            installed.check(package, jar, "2.2.3")
        jar.write_bytes(b"different")
        with self.assertRaises(ValueError):
            installed.check(package, jar, "2.2.3")
        jar.write_bytes(b"product")
        with self.assertRaises(ValueError):
            installed.check(package, jar, "2.2.4")
        self.assertEqual(64, len(installed.check(package, jar, "2.2.3")))


if __name__ == "__main__":
    unittest.main()
