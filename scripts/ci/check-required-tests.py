"""Fail when mandatory Linux confinement tests are absent, skipped, or unsuccessful."""
import sys
from pathlib import Path
import xml.etree.ElementTree as ET

REQUIRED = {
    ("ProjectFilesBoundsTest", "walkerDoesNotFollowSymbolicLinks"),
    ("ProjectFilesBoundsTest", "aliasesCannotExposeExternalOrInternalFiles"),
    ("ProjectFilesBoundsTest", "explicitResolutionOfOrdinaryInternalAliasRemainsAllowed"),
    ("HopDefinitionMutatorTest", "rejectsSymbolicLinkBackupRoot"),
}


def check(directory):
    passed = set()
    for report in Path(directory).glob("TEST-*.xml"):
        for case in ET.parse(report).getroot().iter("testcase"):
            key = (case.get("classname", "").rsplit(".", 1)[-1], case.get("name"))
            if key in REQUIRED and not any(
                case.find(status) is not None for status in ("skipped", "failure", "error")
            ):
                passed.add(key)
    missing = REQUIRED - passed
    if missing:
        raise SystemExit("Required security tests did not pass: " + str(sorted(missing)))
    print("Required Linux confinement tests executed and passed: " + str(sorted(passed)))


if __name__ == "__main__":
    if len(sys.argv) != 2:
        raise SystemExit("Usage: check-required-tests.py <surefire-reports>")
    check(sys.argv[1])
