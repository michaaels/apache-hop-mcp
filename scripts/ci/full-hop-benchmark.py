import argparse
import hashlib
import io
import json
import os
import statistics
import subprocess
import sys
import xml.etree.ElementTree as ET
from pathlib import Path
from zipfile import ZipFile


# Test tooling only: production remains native Java and STDIO.
parser = argparse.ArgumentParser(description="Compare published/current connector ZIPs in an isolated full Hop 2.19 runtime (Windows).")
parser.add_argument("--runtime", type=Path, required=True)
parser.add_argument("--fixture", type=Path, required=True)
parser.add_argument("--generate-fixture", action="store_true", help="Create the fixed 5000-definition fixture in a new directory")
parser.add_argument("--java-home", type=Path, required=True)
parser.add_argument("--baseline", type=Path, required=True)
parser.add_argument("--candidate", type=Path, required=True)
parser.add_argument("--baseline-version", default="2.2.2")
parser.add_argument("--candidate-version", default="2.2.3-SNAPSHOT")
parser.add_argument("--output", type=Path, required=True)
parser.add_argument("--trials", type=int, default=5)
args = parser.parse_args()
if not 1 <= args.trials <= 20:
    parser.error("--trials must be between 1 and 20")
BASE = args.output.resolve()
RUNTIME = args.runtime.resolve()
FIXTURE = args.fixture.resolve()
HARNESS = Path(__file__).with_name("full-hop-measure.mjs")
JAVA_HOME = args.java_home.resolve()
BASELINE_ZIP = args.baseline.resolve()
CANDIDATE_ZIP = args.candidate.resolve()
PLUGIN = RUNTIME / "plugins" / "misc" / "hop-mcp-connector"
PREFIX = "plugins/misc/hop-mcp-connector/"
HOP_VERSION = "2.19.0"
EXPECTED_FIXTURE_HASH = "54a2edfdd7ac3e0ff35190e537421a51ec126f0ef83d3005e0bcde21fb7b2334"
RESULTS = BASE / "results.json"
REPORT = BASE / "report.md"


def generate_fixture():
    FIXTURE.mkdir(parents=True, exist_ok=False)
    pipeline = (
        "<pipeline><transform><name>Table Input</name><type>TableInput</type>"
        "<sql>select * from DWH.{table}</sql></transform><transform><name>Filter Rows</name>"
        "<type>FilterRows</type></transform><hop><from>Table Input</from><to>Filter Rows</to>"
        "</hop></pipeline>"
    )
    (FIXTURE / "target.hpl").write_bytes(pipeline.format(table="DIM_SITE").encode())
    for index in range(1, 64):
        previous = "target.hpl" if index == 1 else f"chain-{index - 1:05d}.hwf"
        content = (
            "<workflow><action><name>Run pipeline</name><type>Pipeline</type></action>"
            f"<note>{previous}</note></workflow>"
        )
        (FIXTURE / f"chain-{index:05d}.hwf").write_bytes(content.encode())
    for index in range(64, 5000):
        (FIXTURE / f"filler-{index:05d}.hpl").write_bytes(
            pipeline.format(table=f"UNRELATED_{index}").encode()
        )


def fixture_digest():
    names = sorted(p.name for p in FIXTURE.iterdir() if p.suffix.lower() in {".hpl", ".hwf"})
    if len(names) != 5000 or not (FIXTURE / "target.hpl").is_file():
        raise RuntimeError(f"Expected the 5000-definition fixture; found {len(names)} definitions")
    digest = hashlib.sha256()
    total = 0
    for name in names:
        data = (FIXTURE / name).read_bytes()
        total += len(data)
        digest.update(name.encode())
        digest.update(b"\0")
        digest.update(len(data).to_bytes(8, "big"))
        digest.update(data)
    if total != 1_284_756 or digest.hexdigest() != EXPECTED_FIXTURE_HASH:
        raise RuntimeError("Fixture bytes/hash differ from the verified 5000-definition baseline")
    return digest.hexdigest()


def root_version(text):
    root = ET.fromstring(text)
    return root.text.strip() if root.tag == "version" and root.text else root.findtext("version")


def verify_runtime():
    for artifact in ("hop-core", "hop-engine"):
        jar = RUNTIME / "lib" / "core" / f"{artifact}-{HOP_VERSION}.jar"
        with ZipFile(jar) as archive:
            manifest = archive.read("META-INF/MANIFEST.MF").decode("utf-8")
        values = dict(line.split(": ", 1) for line in manifest.splitlines() if ": " in line)
        if values.get("Implementation-Version") != HOP_VERSION:
            raise RuntimeError(f"{artifact} manifest version is not {HOP_VERSION}")


def verify_zip(path, version):
    with ZipFile(path) as archive:
        bad = archive.testzip()
        if bad:
            raise RuntimeError(f"Corrupt ZIP member: {bad}")
        names = set(archive.namelist())
        manifest = PREFIX + "version.xml"
        main_jar = PREFIX + f"hop-mcp-connector-{version}.jar"
        if manifest not in names or main_jar not in names:
            raise RuntimeError(f"ZIP missing manifest/main JAR for {version}")
        if root_version(archive.read(manifest).decode()) != version:
            raise RuntimeError(f"ZIP version does not match {version}")
        if any(Path(name).name.startswith(("hop-core-", "hop-engine-", "hop-ui-")) and name.endswith(".jar") for name in names):
            raise RuntimeError("ZIP must not bundle Hop runtime JARs")
        with ZipFile(io.BytesIO(archive.read(main_jar))) as plugin_jar:
            if "META-INF/jandex.idx" not in plugin_jar.namelist():
                raise RuntimeError("Plugin JAR missing Jandex index")
        for legal in ("LICENSE", "NOTICE"):
            if PREFIX + legal not in names:
                raise RuntimeError(f"ZIP missing {legal}")


def install(path, version):
    verify_zip(path, version)
    expected_root = RUNTIME / "plugins" / "misc" / "hop-mcp-connector"
    if PLUGIN.is_symlink() or PLUGIN.resolve() != expected_root or expected_root.parent.name != "misc":
        raise RuntimeError("Refusing to install outside the isolated Hop connector directory")
    if PLUGIN.exists():
        import shutil

        shutil.rmtree(PLUGIN)
    with ZipFile(path) as archive:
        for name in archive.namelist():
            normalized = name.replace("\\", "/")
            if normalized.startswith("/") or ":" in normalized or ".." in Path(normalized).parts:
                raise RuntimeError(f"Unsafe ZIP path: {name}")
            if normalized.startswith(PREFIX):
                destination = (RUNTIME / normalized).resolve()
                if not destination.is_relative_to(PLUGIN.resolve()):
                    raise RuntimeError("ZIP member escapes connector directory")
                archive.extract(name, RUNTIME)
    if root_version((PLUGIN / "version.xml").read_text(encoding="utf-8")) != version:
        raise RuntimeError(f"Installed plugin version mismatch: {version}")


def stats(values):
    center = statistics.median(values)
    return {
        "median_ms": round(center, 1),
        "min_ms": round(min(values), 1),
        "max_ms": round(max(values), 1),
        "mad_ms": round(statistics.median(abs(v - center) for v in values), 1),
    }


def main():
    if BASE.exists():
        raise RuntimeError("Refusing to overwrite benchmark output directory")
    verify_runtime()
    if not HARNESS.is_file() or not (RUNTIME / "hop.bat").is_file():
        raise RuntimeError("Missing verified full-Hop MCP benchmark harness")
    if args.generate_fixture:
        generate_fixture()
    fixture_hash = fixture_digest()
    verify_zip(BASELINE_ZIP, args.baseline_version)
    verify_zip(CANDIDATE_ZIP, args.candidate_version)

    env = os.environ.copy()
    env.update({
        "JAVA_HOME": str(JAVA_HOME),
        "HOP_JAVA_HOME": str(JAVA_HOME),
        "HOP_OPTIONS": "-Xmx2g",
        "PATH": str(JAVA_HOME / "bin") + os.pathsep + os.environ.get("PATH", ""),
    })
    versions = [(args.baseline_version, BASELINE_ZIP), (args.candidate_version, CANDIDATE_ZIP)]
    if PLUGIN.exists() or PLUGIN.is_symlink():
        raise RuntimeError("Use an isolated runtime without an installed MCP connector; existing plugins are never overwritten")
    BASE.mkdir(parents=True)
    expected_impact_hash = None
    rows = []
    try:
        for trial in range(1, args.trials + 1):
            order = versions if trial % 2 else list(reversed(versions))
            for version, package in order:
                install(package, version)
                config = BASE / f"config-{version.replace('.', '-')}-{trial}"
                run = subprocess.run(
                    ["node", str(HARNESS), str(RUNTIME), str(FIXTURE), str(config), version, str(trial)],
                    capture_output=True,
                    text=True,
                    env=env,
                    timeout=240,
                )
                if run.returncode:
                    raise RuntimeError(f"{version} trial {trial} failed: {run.stderr[-4000:]} {run.stdout[-1000:]}")
                row = json.loads(run.stdout)
                if row.get("version") != version or row.get("hop_version") != HOP_VERSION:
                    raise RuntimeError(f"Version check failed in {version} trial {trial}")
                if row.get("node_count") != 64 or row.get("results_truncated") is not False or row.get("count_complete") is not True:
                    raise RuntimeError(f"Impact completeness check failed in {version} trial {trial}")
                if row.get("validation", {}).get("valid") is not True or row["validation"].get("errors"):
                    raise RuntimeError(f"hop_validate failed in {version} trial {trial}")
                if {item["id"] for item in row.get("plugin_checks", [])} != {"TableInput", "FilterRows"}:
                    raise RuntimeError(f"Required plugin check failed in {version} trial {trial}")
                if expected_impact_hash is None:
                    expected_impact_hash = row["impact_sha256"]
                if row["impact_sha256"] != expected_impact_hash:
                    raise RuntimeError("Impact result hash differs across versions or processes")
                row["fixture_sha256"] = fixture_hash
                rows.append(row)
                RESULTS.write_text(json.dumps(rows, indent=2) + "\n", encoding="utf-8")
                print(f"{version}, trial {trial}: startup={row['startup_ms']:.1f} ms, cold={row['cold_ms']:.1f} ms", flush=True)

        report = [
            "# Full-Hop performance comparison (current tree)",
            "",
            f"Apache Hop 2.19.0, Java 21, {args.trials} fresh JVMs per version, alternating order, 5,000 definitions.",
            f"Fixture SHA-256: `{fixture_hash}` (1,284,756 bytes). Plugins TableInput and FilterRows checked; hop_validate passed each run.",
            "All impact results: 64 unique nodes, 63 edges, complete counts, no truncation, identical canonical hashes.",
            "",
            "| Connector | Startup median (min-max; MAD) | First-impact median (min-max; MAD) | Warm median (min-max; MAD) |",
            "|---|---:|---:|---:|",
        ]
        for version, _ in versions:
            group = [r for r in rows if r["version"] == version]
            startup = stats([r["startup_ms"] for r in group])
            cold = stats([r["cold_ms"] for r in group])
            warm_values = [v for r in group for v in r["warm_ms"]]
            warm = stats(warm_values)
            fmt = lambda s: f"{s['median_ms']} ms ({s['min_ms']}-{s['max_ms']}; MAD {s['mad_ms']})"
            report.append(f"| {version} | {fmt(startup)} | {fmt(cold)} | {fmt(warm)} |")
        REPORT.write_text("\n".join(report) + "\n", encoding="utf-8")
        print(json.dumps({"results": str(RESULTS), "report": str(REPORT), "rows": len(rows)}, indent=2))
    finally:
        import shutil

        if PLUGIN.exists():
            if PLUGIN.is_symlink() or PLUGIN.resolve() != RUNTIME / "plugins" / "misc" / "hop-mcp-connector":
                raise RuntimeError("Refusing to remove unexpected plugin path")
            shutil.rmtree(PLUGIN)


if __name__ == "__main__":
    try:
        main()
    except Exception as error:
        print(f"ERROR: {error}", file=sys.stderr)
        sys.exit(1)
