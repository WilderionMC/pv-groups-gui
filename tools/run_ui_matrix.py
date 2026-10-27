"""Launch disposable Minecraft clients and report runtime UI results separately from ABI checks.

Development dependency: pip install --target build/matrix-deps minecraft-launcher-lib==8.0
Build the test agent first: gradlew :client-ui:matrixTestAgent -PwithClient=true
"""
import argparse
import contextlib
import hashlib
import json
import os
from pathlib import Path
import shutil
import subprocess
import sys
import time
import threading
import urllib.request
import urllib.parse
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "build/matrix-deps"))
WORK = ROOT / "build/ui-matrix"
AGENT = WORK / "ui-matrix-agent.jar"


def targets():
    selected = set(json.loads((ROOT / "ui-test-matrix.json").read_text())["minecraft"])
    result = []
    for target in json.loads((ROOT / "client-targets.json").read_text()):
        matches = selected.intersection(target["minecraft"])
        if matches:
            if len(matches) != 1:
                raise ValueError(f"More than one selected patch in {target['name']}")
            result.append(dict(target, game=matches.pop()))
    return sorted(result, key=lambda t: (tuple(map(int, t["game"].split("."))), t["loader"]))


def fetch(url):
    with urllib.request.urlopen(url, timeout=120) as response:
        return json.load(response)


def fabric_api(game, directory, mods):
    pin = directory / "fabric-api.json"
    if pin.exists():
        version = json.loads(pin.read_text())
    else:
        query = urllib.parse.urlencode({"loaders": json.dumps(["fabric"]), "game_versions": json.dumps([game])})
        versions = fetch("https://api.modrinth.com/v2/project/fabric-api/version?" + query)
        version = next(v for v in versions if v["version_type"] == "release")
        pin.write_text(json.dumps(version), encoding="utf-8")
    primary = next((f for f in version["files"] if f["primary"]), version["files"][0])
    cached = WORK / "downloads" / primary["filename"]
    cached.parent.mkdir(parents=True, exist_ok=True)
    if not cached.exists() or hashlib.sha512(cached.read_bytes()).hexdigest() != primary["hashes"]["sha512"]:
        with urllib.request.urlopen(primary["url"], timeout=120) as response:
            cached.write_bytes(response.read())
    if hashlib.sha512(cached.read_bytes()).hexdigest() != primary["hashes"]["sha512"]:
        raise RuntimeError("Fabric API checksum mismatch")
    shutil.copy2(cached, mods / "fabric-api.jar")


def java_for(version):
    feature = 25 if version.startswith("26.") else 21 if version.startswith("1.21") else 8 if version == "1.16.5" else 17
    jdks = Path.home() / ".gradle/jdks"
    candidates = list(jdks.glob(f"*{feature}*-windows*/bin/java.exe"))
    if feature == 25:
        candidates += list((Path.home() / "AppData/Local/Programs/Eclipse Adoptium").glob("jdk-25*/bin/java.exe"))
    if not candidates:
        raise RuntimeError(f"JDK {feature} missing; install it before running {version}")
    return str(candidates[0])


def prepare(target, directory):
    import minecraft_launcher_lib as launcher
    loader = launcher.mod_loader.get_mod_loader(target["loader"])
    java = java_for(target["game"])
    pin = directory / "loader.json"
    if pin.exists():
        loader_version = json.loads(pin.read_text())["version"]
    elif target["loader"] == "neoforge" and target["game"].startswith("26."):
        # launcher-lib 8 predates calendar versioning; the installer itself supports it.
        versions = fetch("https://maven.neoforged.net/api/maven/versions/releases/net/neoforged/neoforge")["versions"]
        branch = ".".join(target["game"].split(".")[:2]) + "."
        compatible = [v for v in versions if v.startswith(branch)]
        if not compatible:
            raise RuntimeError("No NeoForge installer found for " + target["game"])
        loader_version = compatible[-1]
    else:
        loader_version = loader.get_latest_loader_version(target["game"])
    pin.write_text(json.dumps({"version": loader_version}), encoding="utf-8")
    installed = loader.get_installed_version(target["game"], loader_version)
    cache = WORK / "minecraft"
    version_json = cache / "versions" / installed / (installed + ".json")
    prepared = directory / "prepared.json"
    with (directory / "install.log").open("w", encoding="utf-8") as log, contextlib.redirect_stdout(log), contextlib.redirect_stderr(log):
        if not version_json.exists() or not prepared.exists():
            if target["loader"] == "fabric":
                launcher.install.install_minecraft_version(target["game"], str(cache))
                profile = fetch(f"https://meta.fabricmc.net/v2/versions/loader/{target['game']}/{loader_version}/profile/json")
                version_json.parent.mkdir(parents=True, exist_ok=True)
                version_json.write_text(json.dumps(profile), encoding="utf-8")
                launcher.install.install_minecraft_version(installed, str(cache))
            elif target["loader"] == "neoforge" and target["game"].startswith("26."):
                launcher.install.install_minecraft_version(target["game"], str(cache))
                # Narrow calendar-version adapter, reusing the library's installer implementation.
                loader._base.install(target["game"], str(cache), {}, java, loader_version)
                launcher.install.install_minecraft_version(installed, str(cache))
            else:
                loader.install(target["game"], str(cache), loader_version=loader_version, java=java)
            prepared.write_text(json.dumps({"installed": installed}), encoding="utf-8")
    return installed, java, cache


def run_target(target, timeout, prepare_only):
    import minecraft_launcher_lib as launcher
    name = target["loader"] + "-" + target["game"]
    directory = WORK / "runs" / name
    directory.mkdir(parents=True, exist_ok=True)
    report = directory / "result.json"
    report.unlink(missing_ok=True)
    print("Preparing " + name, flush=True)
    started = time.monotonic()
    result = {"target": name, "artifact": target["name"], "status": "failed"}
    try:
        installed, java, cache = prepare(target, directory)
        result["loaderVersion"] = json.loads((directory / "loader.json").read_text())["version"]
        if prepare_only:
            result["status"] = "prepared"
            return result
        mods = directory / "mods"
        mods.mkdir(exist_ok=True)
        addon = ROOT / "build/artifacts/plasmo-ui" / ("pv-addon-groups-gui-" + target["name"] + "-1.0.0.jar")
        plasmo = ROOT / "build/upstream" / (target["plasmoVersion"] + ".jar")
        if hashlib.sha512(plasmo.read_bytes()).hexdigest() != target["plasmoSha512"]:
            raise RuntimeError("Plasmo checksum mismatch")
        shutil.copy2(addon, mods / "groups-gui.jar")
        shutil.copy2(plasmo, mods / "plasmovoice.jar")
        if target["loader"] == "fabric":
            fabric_api(target["game"], directory, mods)
        (directory / "options.txt").write_text("guiScale:2\nmaxFps:60\nenableVsync:false\nrenderDistance:2\npauseOnLostFocus:false\nlang:en_us\ntutorialStep:none\nonboardAccessibility:false\nsoundCategory_master:0.0\n", encoding="utf-8")
        agent = AGENT
        command = launcher.command.get_minecraft_command(installed, str(cache), {
            "username": "PVGuiTest", "uuid": str(uuid.uuid3(uuid.NAMESPACE_DNS, "pv-groups-gui-tests")),
            "token": "", "executablePath": java, "gameDirectory": str(directory),
            "customResolution": True, "resolutionWidth": "960", "resolutionHeight": "640",
            "jvmArguments": ["-Xmx2G", "-Duser.language=en", "-Duser.country=US", "-javaagent:" + str(agent), "-Dpv.ui.matrix.report=" + str(report)]})
        print("Testing " + name, flush=True)
        with (directory / "client.log").open("w", encoding="utf-8") as log:
            process = subprocess.run(command, cwd=directory, stdout=log, stderr=subprocess.STDOUT, timeout=timeout)
        if report.exists():
            result.update(json.loads(report.read_text()))
        if process.returncode != 0 or result.get("uiScenarios") != 10:
            result["status"] = "failed"
            if not result.get("error"):
                result["error"] = "Client exit " + str(process.returncode) + "; see client.log"
        result["artifactSha256"] = hashlib.sha256(addon.read_bytes()).hexdigest()
    except Exception as error:
        result["error"] = type(error).__name__ + ": " + str(error)
    finally:
        result["seconds"] = round(time.monotonic() - started, 1)
    print(result["status"].upper() + " " + name + (": " + str(result["error"]) if result.get("error") else ""), flush=True)
    return result


def main():
    global AGENT
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--targets", nargs="*", help="Loader-version names, e.g. fabric-26.2")
    parser.add_argument("--list", action="store_true")
    parser.add_argument("--prepare-only", action="store_true")
    parser.add_argument("--timeout", type=int, default=180)
    parser.add_argument("--report", type=Path, default=WORK / "report.json")
    parser.add_argument("--agent", type=Path, default=AGENT)
    args = parser.parse_args()
    AGENT = args.agent.resolve()
    # Native archives are unpacked concurrently by launcher-lib 8. Serialize extraction
    # to avoid a Windows META-INF directory creation race between different libraries.
    import minecraft_launcher_lib.install as installation
    original_extract = installation.extract_natives_file
    extraction_lock = threading.Lock()
    def extract_native(*arguments, **keywords):
        with extraction_lock:
            return original_extract(*arguments, **keywords)
    installation.extract_natives_file = extract_native
    matrix = targets()
    if args.targets:
        matrix = [t for t in matrix if t["loader"] + "-" + t["game"] in args.targets]
        if len(matrix) != len(set(args.targets)):
            parser.error("Unknown or duplicate target")
    if args.list:
        print(json.dumps([{"target": t["loader"] + "-" + t["game"], "artifact": t["name"]} for t in matrix], indent=2))
        return
    WORK.mkdir(parents=True, exist_ok=True)
    summary = {"coverage": "Real Minecraft rendering and Plasmo event routing; deterministic protocol fixtures; hotkey registration only", "results": []}
    for target in matrix:
        summary["results"].append(run_target(target, args.timeout, args.prepare_only))
        args.report.parent.mkdir(parents=True, exist_ok=True)
        args.report.write_text(json.dumps(summary, indent=2), encoding="utf-8")
    sys.exit(0 if all(r["status"] in {"passed", "prepared"} for r in summary["results"]) else 1)


if __name__ == "__main__":
    main()
