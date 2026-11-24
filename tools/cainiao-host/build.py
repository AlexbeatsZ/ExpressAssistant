#!/usr/bin/env python3
"""Compose one signed APK from the local runtime and the built ExpressAssistant module."""

import argparse
import json
import os
from pathlib import Path
import re
import subprocess
import tempfile
import urllib.request
import zipfile

REPO = Path(__file__).resolve().parents[2]
SOURCE = Path(__file__).resolve().parent
LSPATCH = "https://github.com/JingMatrix/LSPatch/releases/download/v1.2/lspatch-v1.2-487-release.jar"
ARSCLIB_RELEASE = "https://api.github.com/repos/REAndroid/ARSCLib/releases/tags/V1.4.0"


def fetch(url, destination):
    req = urllib.request.Request(url, headers={"User-Agent": "ExpressAssistant-build"})
    with urllib.request.urlopen(req, timeout=60) as response:
        data = response.read()
    destination.write_bytes(data)


def run(arguments, **kwargs):
    subprocess.run([str(value) for value in arguments], check=True, **kwargs)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--runtime", type=Path, default=REPO / "outputs/cainiao-8.11.1023.apk")
    parser.add_argument("--module", type=Path, default=REPO / "app/build/outputs/apk/release/app-release.apk")
    parser.add_argument("--output", type=Path)
    parser.add_argument("--java-home", type=Path, default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--android-sdk", type=Path, default=os.environ.get("ANDROID_HOME") or os.environ.get("ANDROID_SDK_ROOT"))
    parser.add_argument("--build-tools", default="36.1.0")
    parser.add_argument("--tool-cache", type=Path, default=REPO / "outputs/.private/cainiao-tools")
    args = parser.parse_args()
    if not args.runtime.is_file():
        parser.error("Supply an existing local Cainiao runtime APK with --runtime; this builder never downloads Cainiao.")
    if not args.module.is_file():
        parser.error("Build :app:assembleRelease first.")
    if args.java_home is None:
        parser.error("Supply an existing Java 21+ installation with --java-home.")
    if args.android_sdk is None:
        local = REPO / "local.properties"
        sdk = next((line.split("=", 1)[1] for line in local.read_text().splitlines() if line.startswith("sdk.dir=")), None)
        if sdk is None:
            parser.error("Supply the existing Android SDK with --android-sdk.")
        args.android_sdk = Path(sdk)
    java = args.java_home / "bin/java"
    javac = args.java_home / "bin/javac"
    tools = args.android_sdk / "build-tools" / args.build_tools
    for executable in [java, javac, tools / "apksigner", tools / "zipalign"]:
        if not executable.is_file():
            parser.error(f"Required existing tool is missing: {executable}")
    version = re.search(r'versionName\s+"([^"]+)"', (REPO / "app/build.gradle").read_text()).group(1)
    output = args.output or REPO / f"outputs/ExpressAssistant-v{version}.apk"
    output.parent.mkdir(parents=True, exist_ok=True)
    args.tool_cache.mkdir(parents=True, exist_ok=True)
    lspatch = args.tool_cache / "lspatch-v1.2-487.jar"
    arsc = args.tool_cache / "arsclib-v1.4.0.jar"
    if not lspatch.exists():
        fetch(LSPATCH, lspatch)
    if not arsc.exists():
        req = urllib.request.Request(ARSCLIB_RELEASE, headers={"User-Agent": "ExpressAssistant-build"})
        with urllib.request.urlopen(req, timeout=30) as response:
            release = json.load(response)
        asset = next(value for value in release["assets"] if value["name"].endswith(".jar"))
        fetch(asset["browser_download_url"], arsc)
    temporary_root = Path(os.environ["LOCALAPPDATA"]) / "Temp/.agents" if os.name == "nt" else Path("/tmp/.agents")
    temporary_root.mkdir(parents=True, exist_ok=True)
    with tempfile.TemporaryDirectory(prefix="expressassistant-host-", dir=temporary_root) as temporary:
        stage = Path(temporary)
        patched_dir = stage / "patched"
        patched_dir.mkdir()
        with (stage / "patch.log").open("w") as log:
            run([java, "-jar", lspatch, "-f", "-l", "2", "--name", "快递", "-m", args.module,
                 "-o", patched_dir, args.runtime], stdout=log, stderr=subprocess.STDOUT)
        patched = next(patched_dir.glob("*.apk"))
        with zipfile.ZipFile(patched) as original, zipfile.ZipFile(args.module) as module:
            for name, archive, member in [
                ("original-manifest.axml", original, "AndroidManifest.xml"),
                ("module-manifest.axml", module, "AndroidManifest.xml"),
                ("original-resources.arsc", original, "resources.arsc"),
                ("module-resources.arsc", module, "resources.arsc"),
            ]:
                (stage / name).write_bytes(archive.read(member))
            run([javac, "-cp", lspatch, "-d", stage, SOURCE / "MergeManifest.java"])
            run([javac, "-cp", arsc, "-d", stage, SOURCE / "MergeResources.java"])
            run([java, "-cp", f"{lspatch}{os.pathsep}{stage}", "MergeManifest", stage / "original-manifest.axml",
                 stage / "manifest.axml", stage / "module-manifest.axml"])
            run([java, "-cp", f"{arsc}{os.pathsep}{stage}", "MergeResources", stage / "original-resources.arsc",
                 stage / "module-resources.arsc", stage / "resources.arsc"])
            unaligned = stage / "unaligned.apk"
            with zipfile.ZipFile(unaligned, "w") as composed:
                for entry in original.infolist():
                    if entry.filename.startswith("META-INF/") or entry.filename == "resources.arsc":
                        continue
                    # LSPatch deliberately uses overlapping ZIP views into its embedded APK.
                    # This input was just generated locally; preserve its bounded entry contents.
                    entry._end_offset = None
                    data = (stage / "manifest.axml").read_bytes() if entry.filename == "AndroidManifest.xml" else original.read(entry)
                    composed.writestr(entry, data)
                for entry in module.infolist():
                    if entry.filename.startswith("res/"):
                        data = module.read(entry.filename)
                        entry.filename = "res/express/" + entry.filename[4:]
                        composed.writestr(entry, data)
                composed.writestr("resources.arsc", (stage / "resources.arsc").read_bytes(), compress_type=zipfile.ZIP_STORED)
        aligned = stage / "aligned.apk"
        run([tools / "zipalign", "-f", "-P", "16", "4", unaligned, aligned])
        properties = dict(line.strip().split("=", 1) for line in (REPO / ".signing/personal.properties").read_text().splitlines()
                          if "=" in line and not line.startswith("#"))
        for key in ["storePassword", "keyPassword"]:
            path = stage / key
            path.write_text(properties[key])
            path.chmod(0o600)
        run([tools / "apksigner", "sign", "--ks", REPO / ".signing/personal.jks", "--ks-key-alias", properties["keyAlias"],
             "--ks-pass", f"file:{stage}/storePassword", "--key-pass", f"file:{stage}/keyPassword", aligned])
        run([tools / "apksigner", "verify", aligned])
        os.replace(aligned, output)
    print(json.dumps({"apk": str(output), "version": version, "bytes": output.stat().st_size,
                      "package": "com.halo.expressassistant", "externalCainiaoRequired": False}, ensure_ascii=False))


if __name__ == "__main__":
    main()
