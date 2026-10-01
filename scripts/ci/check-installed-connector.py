"""Verify the installed connector JAR is the exact one in a Marketplace ZIP."""
import argparse
import hashlib
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def check(package_path, installed_jar, version):
    member = f"plugins/misc/hop-mcp-connector/hop-mcp-connector-{version}.jar"
    with ZipFile(package_path) as package:
        if ET.fromstring(package.read("plugins/misc/hop-mcp-connector/version.xml")).text.strip() != version:
            raise ValueError("ZIP version does not match expected version")
        expected = hashlib.sha256(package.read(member)).hexdigest()
    installed_jar = Path(installed_jar)
    if not installed_jar.is_file() or installed_jar.suffix.lower() != ".jar":
        raise ValueError("Expected installed connector JAR is missing")
    actual = hashlib.sha256(installed_jar.read_bytes()).hexdigest()
    if actual != expected:
        raise ValueError("Installed connector JAR differs from verified ZIP")
    return expected


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("package", type=Path)
    parser.add_argument("installed_jar", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    print("Installed connector JAR SHA-256: " + check(args.package, args.installed_jar, args.version))
