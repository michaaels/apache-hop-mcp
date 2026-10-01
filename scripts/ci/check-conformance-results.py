"""Fail closed on raw scenario failures not explicitly declared in the server baseline."""
import json
import re
import sys
from pathlib import Path

# Frozen inventory for runner 0.2.0-alpha.11 and requirements 2025-11-25.
BASELINE_SCENARIOS = {
    "json-schema-2020-12", "completion-complete", "tools-call-simple-text",
    "tools-call-image", "tools-call-audio", "tools-call-embedded-resource",
    "tools-call-mixed-content", "tools-call-with-logging", "tools-call-error",
    "tools-call-with-progress", "tools-call-sampling", "tools-call-elicitation",
    "elicitation-sep1034-defaults", "elicitation-sep1330-enums", "resources-list",
    "resources-read-text", "resources-read-binary", "resources-templates-read",
    "resources-subscribe", "resources-unsubscribe", "prompts-list", "prompts-get-simple",
    "prompts-get-with-args", "prompts-get-embedded-resource", "prompts-get-with-image",
}
REQUIRED_CHECKS = {
    "server-initialize": {"server-initialize", "server-session-id-visible-ascii", "wire-schema-valid"},
    "ping": {"ping", "wire-schema-valid"},
    "tools-list": {"tools-list", "tools-name-format", "wire-schema-valid"},
    "logging-set-level": {"logging-set-level", "wire-schema-valid"},
    "dns-rebinding-protection": {"localhost-host-rebinding-rejected", "localhost-host-valid-accepted"},
    "server-session-lifecycle": {"server-session-initialized-accepted", "server-session-delete-accepted",
                                 "server-session-terminated-returns-404"},
    "server-sse-multiple-streams": {"server-accepts-multiple-post-streams", "server-sse-streams-functional"},
}
POLLING_CHECKS = {"server-sse-priming-event", "server-sse-retry-field", "server-sse-disconnect-resume"}
SCENARIOS = BASELINE_SCENARIOS | REQUIRED_CHECKS.keys() | {"server-sse-polling"}
VALID_STATUSES = {"SUCCESS", "FAILURE", "WARNING", "SKIPPED", "INFO"}


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
    if expected != BASELINE_SCENARIOS:
        raise ValueError("Baseline differs from frozen runner inventory")
    failed = set()
    scenarios = set()
    warnings = {}
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
        ids = {}
        for item in checks:
            if not isinstance(item, dict) or not isinstance(item.get("id"), str) or not item["id"]:
                raise ValueError("Malformed check in " + str(report))
            if item.get("status") not in VALID_STATUSES:
                raise ValueError("Invalid check status in " + str(report))
            # The runner emits repeatable transport events alongside scored checks.
            # They cannot satisfy or overwrite any required check result.
            if item["status"] == "INFO":
                continue
            if item["id"] in ids:
                raise ValueError("Invalid or duplicate check in " + str(report))
            ids[item["id"]] = item["status"]
        for check_id in REQUIRED_CHECKS.get(scenario, ()):
            if ids.get(check_id) != "SUCCESS":
                raise ValueError("Required check did not succeed: " + scenario + ":" + check_id)
        if scenario == "server-sse-polling":
            for check_id in POLLING_CHECKS:
                if ids.get(check_id) not in {"SUCCESS", "WARNING"}:
                    raise ValueError("Polling check missing or unsuccessful: " + check_id)
            warnings[scenario] = sorted(k for k in POLLING_CHECKS if ids[k] == "WARNING")
        if any(item.get("status") == "FAILURE" for item in checks):
            failed.add(scenario)
        if any(item.get("id") == "wire-schema-valid" and item.get("status") == "FAILURE"
               for item in checks):
            failed.add(scenario + ":wire-schema-valid")
    missing = SCENARIOS - scenarios
    extra = scenarios - SCENARIOS
    unexpected = failed - expected
    stale = expected - failed
    print(json.dumps({"scenarios": len(scenarios), "unexpected_failures": sorted(unexpected),
                      "missing_required_scenarios": sorted(missing), "extra_scenarios": sorted(extra),
                      "stale_baseline": sorted(stale), "polling_warnings": warnings}))
    if missing or extra or unexpected or stale:
        raise SystemExit(1)


if __name__ == "__main__":
    if len(sys.argv) != 3:
        raise SystemExit("Usage: check-conformance-results.py <results-dir> <server-baseline.yml>")
    check(sys.argv[1], sys.argv[2])
