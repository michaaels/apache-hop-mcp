"""Fail closed on raw scenario failures not explicitly declared in the server baseline."""
import json
import re
import sys
from pathlib import Path


def server_baseline(path):
    expected = set()
    in_server = False
    for line in Path(path).read_text(encoding="utf-8").splitlines():
        text = line.strip()
        if not text or text.startswith("#"):
            continue
        if text == "server:":
            in_server = True
            continue
        if not in_server:
            raise ValueError("Expected a server-only baseline")
        match = re.fullmatch(r"- ([a-z0-9-]+)", text)
        if not match:
            raise ValueError("Unsupported baseline entry: " + text)
        expected.add(match.group(1))
    if not expected:
        raise ValueError("Empty server baseline")
    return expected


def check(directory, baseline):
    expected = server_baseline(baseline)
    failed = set()
    scenarios = set()
    successful = set()
    for report in Path(directory).glob("server-*/checks.json"):
        match = re.fullmatch(r"server-(.+)-\d{4}-\d{2}-\d{2}T.+", report.parent.name)
        if not match:
            raise ValueError("Unrecognized scenario report: " + str(report))
        scenario = match.group(1)
        if scenario in scenarios:
            raise ValueError("Duplicate scenario report: " + scenario)
        scenarios.add(scenario)
        checks = json.loads(report.read_text(encoding="utf-8"))
        if not isinstance(checks, list) or not checks:
            raise ValueError("Missing checks in " + str(report))
        if any(item.get("status") == "FAILURE" for item in checks):
            failed.add(scenario)
        if any(item.get("id") == "wire-schema-valid" and item.get("status") == "FAILURE"
               for item in checks):
            failed.add(scenario + ":wire-schema-valid")
        if any(item.get("status") == "SUCCESS" for item in checks):
            successful.add(scenario)
    required = {"server-initialize", "ping", "tools-list", "logging-set-level",
                "dns-rebinding-protection", "server-session-lifecycle"}
    missing = required - successful
    unexpected = failed - expected
    stale = expected - failed
    print(json.dumps({"scenarios": len(scenarios), "unexpected_failures": sorted(unexpected),
                      "missing_required_scenarios": sorted(missing), "stale_baseline": sorted(stale)}))
    if missing or unexpected or stale:
        raise SystemExit(1)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: check-conformance-results.py <results-dir> <server-baseline.yml>")
    check(sys.argv[1], sys.argv[2])
