"""Standard-library regression tests for fail-closed CI report guards."""
import contextlib
import importlib.util
import io
import json
from pathlib import Path
import tempfile
import unittest


def module(filename):
    spec = importlib.util.spec_from_file_location(filename, Path(__file__).with_name(filename))
    result = importlib.util.module_from_spec(spec)
    spec.loader.exec_module(result)
    return result


conformance = module("check-conformance-results.py")
security = module("check-required-tests.py")


class VerificationGatesTest(unittest.TestCase):
    def setUp(self):
        self.directory = tempfile.TemporaryDirectory()
        self.addCleanup(self.directory.cleanup)
        self.root = Path(self.directory.name)
        self.baseline = self.root / "baseline.yml"
        self.baseline.write_text("server:\n  - fixture-tool\n", encoding="utf-8")
        for scenario in ("server-initialize", "ping", "tools-list", "logging-set-level",
                         "dns-rebinding-protection", "server-session-lifecycle"):
            self.report(scenario, "SUCCESS")
        self.report("fixture-tool", "FAILURE")

    def report(self, scenario, status):
        directory = self.root / ("server-" + scenario + "-2026-09-29T00-00-00Z")
        directory.mkdir(exist_ok=True)
        (directory / "checks.json").write_text(json.dumps([{"status": status}]), encoding="utf-8")

    def check(self):
        with contextlib.redirect_stdout(io.StringIO()):
            conformance.check(self.root, self.baseline)

    def test_explicit_expected_failures_pass(self):
        self.check()

    def test_unlisted_failure_fails_even_if_runner_returned_zero(self):
        self.report("unexpected", "FAILURE")
        with self.assertRaises(SystemExit):
            self.check()

    def test_stale_baseline_fails(self):
        self.report("fixture-tool", "SUCCESS")
        with self.assertRaises(SystemExit):
            self.check()

    def test_wire_schema_failure_is_never_excused_by_fixture_baseline(self):
        report = self.root / "server-fixture-tool-2026-09-29T00-00-00Z" / "checks.json"
        report.write_text(json.dumps([{"id": "wire-schema-valid", "status": "FAILURE"}]), encoding="utf-8")
        with self.assertRaises(SystemExit):
            self.check()

    def test_unexecuted_required_control_fails(self):
        self.report("ping", "SKIPPED")
        with self.assertRaises(SystemExit):
            self.check()

    def test_empty_report_directory_fails(self):
        with tempfile.TemporaryDirectory() as empty:
            with self.assertRaises(SystemExit), contextlib.redirect_stdout(io.StringIO()):
                conformance.check(empty, self.baseline)

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


if __name__ == "__main__":
    unittest.main()
