"""Inspect the built Marketplace ZIP and its product JAR."""
import argparse
import io
from pathlib import Path
import xml.etree.ElementTree as ET
from zipfile import ZipFile


def check(zip_path, version):
    root = "plugins/misc/hop-mcp-connector/"
    with ZipFile(zip_path) as package:
        if package.testzip() is not None:
            raise ValueError("Corrupt Marketplace ZIP")
        names = set(package.namelist())
        jar_name = root + f"hop-mcp-connector-{version}.jar"
        for required in (jar_name, root + "version.xml", root + "LICENSE", root + "NOTICE"):
            if required not in names:
                raise ValueError("Missing package member: " + required)
        if ET.fromstring(package.read(root + "version.xml")).text.strip() != version:
            raise ValueError("Plugin version mismatch")
        for legal in ("LICENSE", "NOTICE"):
            if package.read(root + legal) != Path(legal).read_bytes():
                raise ValueError("Package legal file differs: " + legal)
        forbidden = ("hop-core-", "hop-engine-", "hop-ui-", "tomcat-embed-")
        if any(Path(name).name.startswith(forbidden) for name in names):
            raise ValueError("Marketplace ZIP contains runtime or test-only JAR")
        if any(name.endswith("-tests.jar") for name in names):
            raise ValueError("Marketplace ZIP contains test helper JAR")
        with ZipFile(io.BytesIO(package.read(jar_name))) as plugin:
            if "META-INF/jandex.idx" not in plugin.namelist() or not plugin.read("META-INF/jandex.idx"):
                raise ValueError("Product JAR lacks Jandex index")
            if any(name.endswith(("HopNativeReferenceAcceptance.class", "HopOperationalFixture.class"))
                   for name in plugin.namelist()):
                raise ValueError("Product JAR contains test helper")
    for bom in ("target/bom.json", "target/bom.xml"):
        if not Path(bom).is_file():
            raise ValueError("Missing CycloneDX file: " + bom)
    print(f"Marketplace ZIP verified: {zip_path} ({version})")


if __name__ == "__main__":
    parser = argparse.ArgumentParser()
    parser.add_argument("zip", type=Path)
    parser.add_argument("version")
    args = parser.parse_args()
    check(args.zip, args.version)
