#!/usr/bin/env python3
"""Validate the six installable version JARs before upload; generate download checksums."""
import argparse
import hashlib
import io
import json
from pathlib import Path
import zipfile

VERSIONS = ("1.21.8", "1.21.9", "1.21.10", "1.21.11", "26.1", "26.2")
ROOT = Path(__file__).resolve().parents[1]


def verify(version, write_checksums=False):
    properties = dict(line.split("=", 1) for line in (ROOT / "gradle.properties").read_text().splitlines()
                      if "=" in line and not line.startswith("#"))
    project_version = ".".join(properties["version" + part] for part in ("Major", "Minor", "Patch"))
    version = version or project_version
    if version != project_version:
        raise ValueError(f"Release {version} does not match project version {project_version}")
    directory = ROOT / "build" / "libs" / version
    expected = {f"MC-Inspector-mc{mc}-{version}.jar" for mc in VERSIONS}
    actual = {p.name for p in directory.glob("*.jar")}
    if actual != expected:
        raise ValueError(f"Incorrect release files: missing={sorted(expected - actual)}, extra={sorted(actual - expected)}")
    checksums = []
    for mc in VERSIONS:
        path = directory / f"MC-Inspector-mc{mc}-{version}.jar"
        with zipfile.ZipFile(path) as jar:
            mod = json.loads(jar.read("fabric.mod.json"))
            if mod["id"] != "mcinspector" or mod["version"] != version:
                raise ValueError(f"Incorrect mod metadata: {path.name}")
            expected_mc = f"~{mc}" if mc.startswith("26.") else mc
            if mod["depends"]["minecraft"] != expected_mc:
                raise ValueError(f"Incorrect Minecraft compatibility: {path.name}")
            if mod["depends"]["java"] != (">=25" if mc.startswith("26.") else ">=21"):
                raise ValueError(f"Incorrect Java requirement: {path.name}")
            if mod["depends"]["fabric-language-kotlin"] != ">=" + properties["deps.flk_min"]:
                raise ValueError(f"Incorrect Kotlin requirement: {path.name}")
            nested = [entry["file"] for entry in mod.get("jars", [])]
            if not any("rhino" in name for name in nested):
                raise ValueError(f"Missing bundled JavaScript engine: {path.name}")
            panels = [name for name in nested if "panel-lib" in name]
            if len(panels) != 1:
                raise ValueError(f"Expected exactly one bundled panel-lib: {path.name}")
            with zipfile.ZipFile(io.BytesIO(jar.read(panels[0]))) as panel:
                panel_mod = json.loads(panel.read("fabric.mod.json"))
                if panel_mod["version"] != properties["panellib_version"]:
                    raise ValueError(f"Wrong bundled panel-lib version: {path.name}")
        checksums.append(f"{hashlib.sha256(path.read_bytes()).hexdigest()}  {path.name}")
        print(f"PASS {path.name}")
    if write_checksums:
        (directory / "SHA256SUMS.txt").write_text("\n".join(checksums) + "\n")
    return checksums


if __name__ == "__main__":
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--version")
    parser.add_argument("--checksums", action="store_true")
    args = parser.parse_args()
    verify(args.version.removeprefix("v") if args.version else None, args.checksums)
