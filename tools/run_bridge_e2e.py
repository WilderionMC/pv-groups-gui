"""Run production bridge, Groups and two real Minecraft clients on isolated loopback ports."""
import argparse
import concurrent.futures
import hashlib
import json
import os
import msvcrt
from pathlib import Path
import shutil
import subprocess
import sys
import time
import traceback
import urllib.parse
import urllib.request
import uuid

ROOT = Path(__file__).resolve().parents[1]
sys.path.insert(0, str(ROOT / "tools"))
import run_ui_matrix as matrix
sys.path.insert(0, str(ROOT / "build/matrix-deps"))
import minecraft_launcher_lib as launcher

WORK = ROOT / "build/bridge-e2e"
USER_AGENT = "pv-groups-gui-e2e (https://modrinth.com/plugin/plasmo-voice-groups-gui)"
PREFIX = "pv.addon.groups.gui."
ENGLISH = json.loads((ROOT / "client-ui/src/main/resources/assets/pv-addon-groups-gui/lang/en_us.json").read_text(encoding="utf8"))
SCENARIO_VERSION = 2


def client_matrix_cells():
    servers = {
        ("fabric", "26.3"): "folia",
        ("fabric", "26.1.2"): "bungeecord",
        ("fabric", "1.21.11"): "velocity",
        ("fabric", "1.21.8"): "fabric",
        ("forge", "1.21.1"): "forge",
        ("neoforge", "26.3"): "neoforge",
        ("neoforge", "1.21.4"): "neoforge",
        ("fabric", "1.19.3"): "fabric",
        ("forge", "1.19.3"): "forge",
    }
    return [dict(platform=servers.get((t["loader"], t["game"]), "paper"), game=t["game"],
                 clientLoader=t["loader"], clientArtifact=t["name"], upstream=True)
            for t in matrix.targets()]


def artifact_hashes(target):
    bridge = ROOT / "build/artifacts/plasmo-ui/pv-addon-groups-gui-1.0.0.jar"
    client = ROOT / "build/artifacts/plasmo-ui" / ("pv-addon-groups-gui-" + target["name"] + "-1.0.0.jar")
    return {"bridgeSha256": hashlib.sha256(bridge.read_bytes()).hexdigest(), "clientArtifactSha256": hashlib.sha256(client.read_bytes()).hexdigest()}


def discover_matrix():
    selected = json.loads((ROOT / "ui-test-matrix.json").read_text())["minecraft"]
    cells = []
    for platform in ("paper", "folia"):
        catalog = fetch("https://fill.papermc.io/v3/projects/" + platform)
        available = {v for group in catalog["versions"].values() for v in group}
        cells.extend({"platform": platform, "game": game, "upstream": game in available} for game in selected)
    cells.extend({"platform": t["loader"], "game": t["game"], "upstream": True, "clientArtifact": t["name"]} for t in matrix.targets())
    for platform in ("velocity", "bungeecord"):
        versions = fetch("https://api.modrinth.com/v2/project/plasmo-voice/version?" + urllib.parse.urlencode({"loaders": json.dumps([platform])}))
        release = next(v for v in versions if v["version_number"].endswith("2.1.17"))
        cells.extend({"platform": platform, "game": game, "upstream": game in release["game_versions"], "plasmoVersion": release["id"]} for game in selected)
    def spigot(game):
        try:
            return game, fetch("https://hub.spigotmc.org/versions/" + game + ".json")
        except urllib.error.HTTPError as error:
            if error.code != 404:
                raise
            return game, None
    with concurrent.futures.ThreadPoolExecutor(max_workers=5) as pool:
        for game, info in pool.map(spigot, selected):
            cells.extend({"platform": platform, "game": game, "upstream": info is not None, "buildTools": info.get("name") if info else None} for platform in ("spigot", "craftbukkit"))
    WORK.mkdir(parents=True, exist_ok=True)
    (WORK / "matrix.json").write_text(json.dumps({"selectedMinecraft": selected, "cells": cells}, indent=2), encoding="utf8")
    return cells


def fetch(url):
    with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=120) as response:
        return json.load(response)


def download(url, path, sha512=None, sha256=None):
    path.parent.mkdir(parents=True, exist_ok=True)
    if path.exists():
        data = path.read_bytes()
        if (not sha512 or hashlib.sha512(data).hexdigest() == sha512) and (not sha256 or hashlib.sha256(data).hexdigest() == sha256):
            return path
    print("DOWNLOAD " + path.name, flush=True)
    curl = shutil.which("curl.exe") or shutil.which("curl")
    if curl:
        temporary = path.with_name(path.name + ".part-" + str(os.getpid()))
        try:
            subprocess.run([curl, "--fail", "--location", "--silent", "--show-error", "--connect-timeout", "30", "--max-time", "180", "--user-agent", USER_AGENT, "--output", str(temporary), url], check=True, timeout=190)
            data = temporary.read_bytes()
        finally:
            temporary.unlink(missing_ok=True)
    else:
        with urllib.request.urlopen(urllib.request.Request(url, headers={"User-Agent": USER_AGENT}), timeout=180) as response:
            data = response.read()
    if sha512 and hashlib.sha512(data).hexdigest() != sha512 or sha256 and hashlib.sha256(data).hexdigest() != sha256:
        raise AssertionError("Download checksum mismatch: " + url)
    path.write_bytes(data)
    return path


def modrinth(project, loader, game=None, version=None):
    query = {"loaders": json.dumps([loader])}
    if game:
        query["game_versions"] = json.dumps([game])
    versions = fetch("https://api.modrinth.com/v2/project/" + project + "/version?" + urllib.parse.urlencode(query))
    release = next(v for v in versions if v["version_type"] == "release" and (not version or version in v["version_number"]))
    primary = next((f for f in release["files"] if f["primary"]), release["files"][0])
    path = download(primary["url"], WORK / "downloads" / primary["filename"], primary["hashes"]["sha512"])
    return release, path


def offline_uuid(name):
    return str(uuid.UUID(bytes=hashlib.md5(("OfflinePlayer:" + name).encode()).digest(), version=3))


def prepare_paper(game, directory, port, platform="paper"):
    server = directory / "server"
    server.mkdir(parents=True, exist_ok=True)
    builds = fetch(f"https://fill.papermc.io/v3/projects/{platform}/versions/{game}/builds")
    # Alpha-only upstreams are valid disposable test targets, never a production recommendation.
    build = next((b for b in builds if b["channel"] == "STABLE"), builds[0])
    binary = build["downloads"]["server:default"]
    source = download(binary["url"], WORK / "downloads" / binary["name"], sha256=binary["checksums"]["sha256"])
    shutil.copy2(source, server / "server.jar")
    cached_mojang = WORK / "downloads" / ("mojang_" + game + ".jar")
    if cached_mojang.exists():
        version_file = matrix.WORK / "minecraft/versions" / game / (game + ".json")
        expected = json.loads(version_file.read_text())["downloads"]["server"]["sha1"]
        if hashlib.sha1(cached_mojang.read_bytes()).hexdigest() != expected:
            raise AssertionError("Cached Mojang server checksum mismatch")
        (server / "cache").mkdir(exist_ok=True)
        shutil.copy2(cached_mojang, server / "cache" / cached_mojang.name)
    plugins = server / "plugins"
    plugins.mkdir(exist_ok=True)
    plasmo, path = modrinth("plasmo-voice", "paper", game, "2.1.17")
    shutil.copy2(path, plugins / "PlasmoVoice.jar")
    groups, path = modrinth("pv-addon-groups", "paper", game)
    shutil.copy2(path, plugins / "pv-addon-groups.jar")
    shutil.copy2(ROOT / "build/artifacts/plasmo-ui/pv-addon-groups-gui-1.0.0.jar", plugins / "pv-addon-groups-gui.jar")
    # Reuse the user's already accepted EULA. Do not accept a new agreement here.
    shutil.copy2(Path.home() / "Desktop/pv-groups-gui-test-server/eula.txt", server / "eula.txt")
    (server / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=4\nallow-flight=true\ngamemode=creative\nforce-gamemode=true\ndifficulty=peaceful\n", encoding="utf8")
    (server / "ops.json").write_text(json.dumps([{"uuid": offline_uuid("PVBridgeA"), "name": "PVBridgeA", "level": 4, "bypassesPlayerLimit": True}]), encoding="utf8")
    (server / "bukkit.yml").write_text("settings:\n  connection-throttle: 0\n", encoding="utf8")
    (server / "whitelist.json").write_text(json.dumps([{"uuid": offline_uuid(name), "name": name} for name in ("PVBridgeA", "PVBridgeB")]), encoding="utf8")
    voice = plugins / "PlasmoVoice"
    voice.mkdir(exist_ok=True)
    (voice / "config.toml").write_text(f'default_language = "en_us"\ndebug = false\nuse_crowdin_translations = false\ncheck_for_updates = false\n[host]\nip = "127.0.0.1"\nport = {port + 1000}\n', encoding="utf8")
    return server, {"platform": platform, "minecraft": game, "build": build["id"], "channel": build["channel"], "plasmo": plasmo["id"], "groups": groups["id"]}


def configure_server(server, game, port, folder):
    shutil.copy2(Path.home() / "Desktop/pv-groups-gui-test-server/eula.txt", server / "eula.txt")
    (server / "server.properties").write_text(f"server-ip=127.0.0.1\nserver-port={port}\nonline-mode=false\nenforce-secure-profile=false\nlevel-type=minecraft:flat\ngenerate-structures=false\nspawn-protection=0\nview-distance=2\nsimulation-distance=2\nmax-players=4\nallow-flight=true\ngamemode=creative\nforce-gamemode=true\ndifficulty=peaceful\n", encoding="utf8")
    (server / "ops.json").write_text(json.dumps([{"uuid": offline_uuid("PVBridgeA"), "name": "PVBridgeA", "level": 4, "bypassesPlayerLimit": True}]), encoding="utf8")
    (server / "bukkit.yml").write_text("settings:\n  connection-throttle: 0\n", encoding="utf8")
    (server / "whitelist.json").write_text(json.dumps([{"uuid": offline_uuid(name), "name": name} for name in ("PVBridgeA", "PVBridgeB")]), encoding="utf8")
    voice = server / folder
    voice.mkdir(parents=True, exist_ok=True)
    (voice / "config.toml").write_text(f'default_language = "en_us"\ndebug = false\nuse_crowdin_translations = false\ncheck_for_updates = false\n[host]\nip = "127.0.0.1"\nport = {port + 1000}\n', encoding="utf8")


def prepare_modded(target, directory, port):
    game, loader = target["game"], target["loader"]
    server = directory / "server"
    server.mkdir(parents=True, exist_ok=True)
    cached_run = matrix.WORK / "runs" / (loader + "-" + game)
    loader_version = json.loads((cached_run / "loader.json").read_text())["version"]
    java = matrix.java_for(game)
    if loader == "fabric":
        launcher_version = fetch("https://meta.fabricmc.net/v2/versions/installer")[0]["version"]
        path = download(f"https://meta.fabricmc.net/v2/versions/loader/{game}/{loader_version}/{launcher_version}/server/jar", WORK / "downloads" / f"fabric-server-{game}-{loader_version}-{launcher_version}.jar")
        shutil.copy2(path, server / "fabric-server-launch.jar")
        arguments = ["-jar", "fabric-server-launch.jar", "nogui"]
    else:
        group = "net/minecraftforge/forge" if loader == "forge" else "net/neoforged/neoforge"
        domain = "https://maven.minecraftforge.net" if loader == "forge" else "https://maven.neoforged.net/releases"
        full_version = game + "-" + loader_version if loader == "forge" and not loader_version.startswith(game + "-") else loader_version
        artifact = "forge" if loader == "forge" else "neoforge"
        installer = download(f"{domain}/{group}/{full_version}/{artifact}-{full_version}-installer.jar", WORK / "downloads" / f"{artifact}-{full_version}-installer.jar")
        marker = server / "installed.json"
        if not marker.exists():
            with (directory / "server-install.log").open("w", encoding="utf8") as log:
                subprocess.run([java, "-Xmx2G", "-jar", str(installer), "--installServer"], cwd=server, stdout=log, stderr=subprocess.STDOUT, timeout=600, check=True)
            marker.write_text(json.dumps({"loader": full_version}))
        args_files = list((server / "libraries" / group).glob("*/win_args.txt"))
        if args_files:
            arguments = ["@" + str(args_files[0]), "nogui"]
        else:
            candidates = [p for p in server.glob("forge-*.jar") if "installer" not in p.name]
            arguments = ["-jar", candidates[0].name, "nogui"]
    mods = server / "mods"
    mods.mkdir(exist_ok=True)
    shutil.copy2(ROOT / "build/upstream" / (target["plasmoVersion"] + ".jar"), mods / "plasmovoice.jar")
    groups, path = modrinth("pv-addon-groups", loader, game)
    shutil.copy2(path, mods / "pv-addon-groups.jar")
    shutil.copy2(ROOT / "build/artifacts/plasmo-ui/pv-addon-groups-gui-1.0.0.jar", mods / "groups-gui-bridge.jar")
    if loader == "fabric":
        matrix.fabric_api(game, cached_run, mods)
    configure_server(server, game, port, "config/plasmovoice/server")
    return server, [java, "-Xms512M", "-Xmx2G"] + arguments, {"platform": loader, "minecraft": game, "loader": loader_version, "plasmo": target["plasmoVersion"], "groups": groups["id"]}


def prepare_bukkit(game, directory, port, platform):
    server = directory / "server"
    server.mkdir(parents=True, exist_ok=True)
    cache = WORK / "buildtools" / game
    cache.mkdir(parents=True, exist_ok=True)
    binary_name = ("craftbukkit" if platform == "craftbukkit" else "spigot") + "-" + game + ".jar"
    binary = cache / binary_name
    # Windows releases this lock even if a test worker exits unexpectedly.
    with (cache / ".build.lock").open("a+b") as lock:
        lock.seek(0)
        if not lock.read(1):
            lock.write(b"0")
            lock.flush()
        deadline = time.monotonic() + 1200
        while True:
            lock.seek(0)
            try:
                msvcrt.locking(lock.fileno(), msvcrt.LK_NBLCK, 1)
                break
            except OSError:
                if time.monotonic() > deadline:
                    raise TimeoutError("Another BuildTools worker owns " + str(cache))
                time.sleep(1)
        try:
            if not binary.exists():
                tool = download("https://hub.spigotmc.org/jenkins/job/BuildTools/lastSuccessfulBuild/artifact/target/BuildTools.jar", WORK / "downloads/BuildTools.jar")
                with (cache / "build.log").open("a", encoding="utf8") as log:
                    subprocess.run([matrix.java_for(game), "-Xmx2G", "-jar", str(tool), "--rev", game, "--compile", "craftbukkit,spigot"], cwd=cache, stdout=log, stderr=subprocess.STDOUT, timeout=1200, check=True)
        finally:
            lock.seek(0)
            msvcrt.locking(lock.fileno(), msvcrt.LK_UNLCK, 1)
    shutil.copy2(binary, server / "server.jar")
    plugins = server / "plugins"
    plugins.mkdir(exist_ok=True)
    pv, path = modrinth("plasmo-voice", "paper", game, "2.1.17")
    shutil.copy2(path, plugins / "PlasmoVoice.jar")
    groups, path = modrinth("pv-addon-groups", "paper", game)
    shutil.copy2(path, plugins / "pv-addon-groups.jar")
    shutil.copy2(ROOT / "build/artifacts/plasmo-ui/pv-addon-groups-gui-1.0.0.jar", plugins / "groups-gui-bridge.jar")
    configure_server(server, game, port, "plugins/PlasmoVoice")
    return server, {"platform": platform, "minecraft": game, "plasmo": pv["id"], "groups": groups["id"], "sha256": hashlib.sha256(binary.read_bytes()).hexdigest()}


def prepare_proxy(platform, game, directory, port):
    server = directory / "proxy"
    server.mkdir(parents=True, exist_ok=True)
    if platform == "velocity":
        catalog = fetch("https://fill.papermc.io/v3/projects/velocity")
        versions = [v for group in catalog["versions"].values() for v in group]
        builds = fetch(f"https://fill.papermc.io/v3/projects/velocity/versions/{versions[0]}/builds")
        build = next((b for b in builds if b["channel"] == "STABLE"), builds[0])
        binary = build["downloads"]["server:default"]
        path = download(binary["url"], WORK / "downloads" / binary["name"], sha256=binary["checksums"]["sha256"])
        server_version = versions[0] + "-" + str(build["id"])
        (server / "velocity.toml").write_text(f'config-version = "2.8"\nbind = "127.0.0.1:{port}"\nmotd = "PV bridge E2E"\nshow-max-players = 4\nonline-mode = false\nforce-key-authentication = false\nplayer-info-forwarding-mode = "NONE"\n[servers]\nbackend = "127.0.0.1:{port + 1}"\ntry = ["backend"]\n[forced-hosts]\n"lobby.example.com" = ["backend"]\n"factions.example.com" = ["backend"]\n"minigames.example.com" = ["backend"]\n', encoding="utf8")
    else:
        build = fetch("https://ci.md-5.net/job/BungeeCord/lastSuccessfulBuild/api/json")
        asset = next(a for a in build["artifacts"] if a["fileName"] == "BungeeCord.jar")
        path = download(build["url"] + "artifact/" + asset["relativePath"], WORK / "downloads" / ("BungeeCord-" + str(build["number"]) + ".jar"))
        server_version = str(build["number"])
        (server / "config.yml").write_text(f'online_mode: false\nip_forward: false\nprevent_proxy_connections: false\nservers:\n  backend:\n    address: 127.0.0.1:{port + 1}\n    restricted: false\nlisteners:\n- host: 127.0.0.1:{port}\n  priorities: [backend]\n  max_players: 4\n  force_default_server: true\n  bind_local_address: false\n  ping_passthrough: false\n  query_enabled: false\npermissions:\n  default: [bungeecord.command.server, pv.addon.groups.browse, pv.addon.groups.create, pv.addon.groups.join, pv.addon.groups.leave, pv.addon.groups.invite, pv.addon.groups.kick, pv.addon.groups.ban, pv.addon.groups.unban, pv.addon.groups.transfer, pv.addon.groups.delete, pv.addon.groups.set, pv.addon.groups.unset, pv.addon.groups.flag.password, pv.addon.groups.delete.owner, pv.addon.groups.set.owner, pv.addon.groups.unset.owner, pv.addon.groups.transfer.owner, pv.addon.groups.kick.owner, pv.addon.groups.ban.owner, pv.addon.groups.unban.owner]\n  admin: [pv.addon.groups.*]\ngroups:\n  PVBridgeA: [admin]\n', encoding="utf8")
    shutil.copy2(path, server / "proxy.jar")
    plugins = server / "plugins"
    plugins.mkdir(exist_ok=True)
    pv, path = modrinth("plasmo-voice", platform, game, "2.1.17")
    shutil.copy2(path, plugins / "PlasmoVoice.jar")
    groups, path = modrinth("pv-addon-groups", platform, game)
    shutil.copy2(path, plugins / "groups.jar")
    shutil.copy2(ROOT / "build/artifacts/plasmo-ui/pv-addon-groups-gui-1.0.0.jar", plugins / "groups-gui-bridge.jar")
    voice = plugins / "plasmovoice"
    voice.mkdir(exist_ok=True)
    (voice / "config.toml").write_text(f'default_language = "en_us"\ndebug = false\nuse_crowdin_translations = false\ncheck_for_updates = false\n[host]\nip = "127.0.0.1"\nport = {port + 1000}\n[servers]\nbackend = "127.0.0.1:{port + 1001}"\n', encoding="utf8")
    return server, {"platform": platform, "minecraft": game, "build": server_version, "channel": build.get("channel", "upstream-ci"), "plasmo": pv["id"], "groups": groups["id"]}


class Client:
    def __init__(self, target, directory, name, port):
        self.restart_arguments = (target, directory, name, port)
        self.directory = directory / name
        self.directory.mkdir(parents=True, exist_ok=True)
        for name_to_clear in ("command.json", "state.json"):
            (self.directory / name_to_clear).unlink(missing_ok=True)
        self.sequence = 0
        self.last_snapshot = {}
        cached_run = matrix.WORK / "runs" / (target["loader"] + "-" + target["game"])
        installed, java, cache = matrix.prepare(target, cached_run)
        mods = self.directory / "mods"
        mods.mkdir(exist_ok=True)
        shutil.copy2(ROOT / "build/artifacts/plasmo-ui" / ("pv-addon-groups-gui-" + target["name"] + "-1.0.0.jar"), mods / "groups-gui.jar")
        shutil.copy2(ROOT / "build/upstream" / (target["plasmoVersion"] + ".jar"), mods / "plasmovoice.jar")
        if target["loader"] == "fabric":
            matrix.fabric_api(target["game"], cached_run, mods)
        (self.directory / "options.txt").write_text("guiScale:1\nmaxFps:30\nenableVsync:false\nfullscreen:false\noverrideWidth:960\noverrideHeight:640\nrenderDistance:2\npauseOnLostFocus:false\nlang:en_us\ntutorialStep:none\nonboardAccessibility:false\nskipMultiplayerWarning:true\nsoundCategory_master:0.0\n", encoding="utf8")
        options = {"username": name, "uuid": offline_uuid(name), "token": "", "executablePath": java,
                   "gameDirectory": str(self.directory), "customResolution": True, "resolutionWidth": "960", "resolutionHeight": "640",
                   "jvmArguments": ["-Xmx1536M", "-Duser.language=en", "-Duser.country=US", "-javaagent:" + str(ROOT / "build/ui-matrix/bridge-e2e-agent.jar"), "-Dpv.test.driver=su.plo.voice.groups.gui.ui.BridgeE2eDriver", "-Dpv.bridge.e2e.directory=" + str(self.directory)]}
        delayed_connect = target["game"] in {"1.16.5", "1.17.1", "1.19.3"}
        if delayed_connect:
            options["jvmArguments"].append("-Dpv.bridge.e2e.connect.port=" + str(port))
        command = launcher.command.get_minecraft_command(installed, str(cache), options)
        if tuple(map(int, target["game"].split("."))) >= (1, 20):
            command += ["--quickPlayMultiplayer", "127.0.0.1:" + str(port)]
        elif not delayed_connect:
            command += ["--server", "127.0.0.1", "--port", str(port)]
        self.log = (self.directory / "client.log").open("a", encoding="utf8")
        self.process = subprocess.Popen(command, cwd=self.directory, stdout=self.log, stderr=subprocess.STDOUT)

    def state(self):
        try:
            self.last_snapshot = json.loads((self.directory / "state.json").read_text())
        except (FileNotFoundError, json.JSONDecodeError, PermissionError):
            pass
        return self.last_snapshot

    def wait(self, predicate, description, timeout=30):
        deadline = time.monotonic() + timeout
        while time.monotonic() < deadline:
            state = self.state()
            if state.get("error"):
                raise AssertionError(state["error"])
            if "state" in state and predicate(state):
                return state
            if self.process.poll() is not None:
                raise AssertionError("Client exited: " + str(self.process.returncode))
            time.sleep(.1)
        raise AssertionError(description + ": " + json.dumps(self.state()))

    def command(self, action, **values):
        self.sequence += 1
        data = dict(values, action=action, sequence=self.sequence)
        temp = self.directory / "command.tmp"
        temp.write_text(json.dumps(data), encoding="utf8")
        deadline = time.monotonic() + 2
        while True:
            try:
                os.replace(temp, self.directory / "command.json")
                break
            except PermissionError:
                # Windows briefly locks command.json while Java reads it.
                # Retry publication with the same sequence, never replay the action.
                if time.monotonic() >= deadline:
                    raise
                time.sleep(.05)
        result = self.wait(lambda s: s.get("sequence") == self.sequence, "command " + action)
        # Give Minecraft one actual render/tick before finding the next widget.
        time.sleep(.25)
        return result

    def click(self, key=None, text=None):
        label = ENGLISH[PREFIX + key] if key else text
        self.wait(lambda s: not s.get("uiDirty", False) and any(button["text"] == label and button["active"] for button in s.get("buttons", [])), "visible active button " + label, 10)
        return self.command("click", **({"key": PREFIX + key} if key else {"text": text}))

    def field(self, name, value):
        self.wait(lambda s: not s.get("uiDirty", False) and name in s.get("fields", []), "rendered field " + name, 10)
        return self.command("field", field=name, value=value)

    def current(self):
        return self.state().get("state", {}).get("current")

    def refresh(self):
        self.command("refresh")

    def close(self):
        if self.process.poll() is None:
            self.process.terminate()
            try:
                self.process.wait(timeout=15)
            except subprocess.TimeoutExpired:
                self.process.kill()
                self.process.wait()
        self.log.close()

    def reconnect(self):
        arguments = self.restart_arguments
        self.close()
        self.__init__(*arguments)
        self.wait(lambda s: s.get("voiceConnected"), "Plasmo reconnect", 150)
        self.command("open")
        self.wait(lambda s: s.get("state", {}).get("connected"), "bridge reconnect hello")


def scenarios(a, b, record):
    def check(name, function):
        function()
        record.append({"scenario": name, "status": "passed", "a": a.state(), "b": b.state()})
        print("PASS " + name, flush=True)
    for client in (a, b):
        client.wait(lambda s: s.get("voiceConnected"), "Plasmo server connection", 150)
        client.command("open")
        client.wait(lambda s: s.get("state", {}).get("connected") and s["state"].get("groupsAvailable"), "bridge hello")
    check("real-bridge-handshake-and-browse", lambda: a.wait(lambda s: s["state"].get("browse") is not None, "browse"))

    def create():
        a.click("button.create")
        a.field("name", "E2E Protected")
        a.field("password", "secret")
        a.click("button.create")
        a.wait(lambda s: s.get("state", {}).get("current", {}).get("name") == "E2E Protected", "created group")
        b.refresh()
        b.wait(lambda s: any(g["name"] == "E2E Protected" for g in s["state"]["browse"]["groups"]), "peer sees group")
    check("create-protected-group-and-peer-browse", create)

    def wrong_password():
        b.click("button.join")
        b.field("joinPassword", "wrong")
        b.click("button.join")
        b.wait(lambda s: s["state"].get("status", {}).get("translationKey") == PREFIX + "error.wrong_password", "wrong password rejected")
        assert b.current() is None
        assert a.current()["onlinePlayers"] == 1
    check("wrong-password-no-membership-change", wrong_password)

    def join():
        b.field("joinPassword", "secret")
        b.click("button.join")
        b.wait(lambda s: s.get("state", {}).get("current") is not None, "peer joins")
        a.refresh()
        a.wait(lambda s: s["state"]["current"]["onlinePlayers"] == 2, "owner sees peer")
        assert a.current()["id"] == b.current()["id"]
    check("join-password-and-two-sided-membership", join)

    def cancel_draft():
        a.click("button.manage")
        a.click("title.settings")
        a.field("name", "Never Saved")
        a.field("password", "never-saved")
        assert a.current()["name"] == "E2E Protected"
        a.click("button.cancel")
        b.refresh()
        b.wait(lambda s: s["state"]["current"]["name"] == "E2E Protected", "cancelled draft not sent")
        assert a.current()["name"] == "E2E Protected"
    check("cancel-settings-draft-does-not-change-server", cancel_draft)

    def settings():
        a.click("button.manage")
        a.click("title.settings")
        a.field("name", "E2E Renamed")
        a.field("password", "changed")
        a.click("button.save")
        a.wait(lambda s: s["state"].get("status", {}).get("translationKey") == PREFIX + "success.settings", "settings acknowledged")
        assert a.current()["name"] == "E2E Renamed"
        b.refresh()
        b.wait(lambda s: s["state"]["current"]["name"] == "E2E Renamed", "peer sees settings")
        a.click("button.cancel")
    check("settings-save-real-sequential-requests", settings)

    def leave():
        b.click("tooltip.leave")
        b.click("button.leave")
        b.wait(lambda s: s["state"].get("current") is None, "peer leaves")
        a.refresh()
        a.wait(lambda s: s["state"]["current"]["onlinePlayers"] == 1, "owner sees leave")
    check("leave-confirmation-two-sided-state", leave)

    def invite():
        a.refresh()
        a.click("button.confirm_invite")
        a.click(text="PVBridgeB")
        a.click("button.confirm_invite")
        a.wait(lambda s: s["state"].get("status", {}).get("translationKey") == PREFIX + "success.invite", "invite acknowledged")
        b.refresh()
        b.click("button.join")
        b.field("joinPassword", "changed")
        b.click("button.join")
        b.wait(lambda s: s["state"].get("current") is not None, "invited peer joins")
        a.refresh()
        a.wait(lambda s: s["state"]["current"]["onlinePlayers"] == 2, "owner sees invited peer")
    check("invite-and-peer-join", invite)

    def member_permissions():
        b.click("button.manage")
        b.wait(lambda s: s.get("view") == "MANAGE", "member management screen")
        controls = {v["text"]: v["active"] for v in b.state()["buttons"]}
        assert controls.get("Group settings") is False, controls
        assert controls.get("Transfer ownership") is False, controls
        assert not b.current()["isOwner"]
        b.click("button.back")
    check("member-owner-controls-disabled", member_permissions)

    def permission_rejection():
        b.command("settings", name="Unauthorized Rename")
        b.wait(lambda s: s["state"].get("status", {}).get("translationKey") == PREFIX + "error.no_permission.set", "server rejects forged non-owner settings")
        a.refresh()
        a.wait(lambda s: s["state"]["current"]["name"] == "E2E Renamed", "owner state unchanged after denied mutation")
        assert b.current()["name"] == "E2E Renamed"
    check("server-rejects-non-owner-settings-request", permission_rejection)

    def kick_and_ban():
        a.click("button.kick")
        a.click("button.confirm_kick")
        b.refresh()
        b.wait(lambda s: s["state"].get("current") is None, "kicked peer sees removal")
        assert a.current()["onlinePlayers"] == 1
        b.click("button.join")
        b.field("joinPassword", "changed")
        b.click("button.join")
        b.wait(lambda s: s["state"].get("current") is not None, "kicked peer can rejoin")
        a.refresh()
        a.wait(lambda s: s["state"]["current"]["onlinePlayers"] == 2, "peer rejoined")
        a.click("button.ban")
        a.click("button.confirm_ban")
        b.refresh()
        b.wait(lambda s: s["state"].get("current") is None, "banned peer sees removal")
        a.wait(lambda s: any(p["name"] == "PVBridgeB" for p in s["state"]["current"]["bannedPlayers"]), "owner sees ban")
        b.click("button.join")
        b.field("joinPassword", "changed")
        b.click("button.join")
        b.wait(lambda s: s["state"].get("status", {}).get("translationKey") == PREFIX + "error.banned", "banned peer rejected")
        assert b.current() is None
        a.click("button.unban")
        a.wait(lambda s: not s["state"]["current"]["bannedPlayers"], "unban acknowledged")
        b.click("button.join")
        b.wait(lambda s: s["state"].get("current") is not None, "unbanned peer can join")
        a.refresh()
        a.wait(lambda s: s["state"]["current"]["onlinePlayers"] == 2, "owner sees unbanned peer")
    check("kick-ban-rejected-rejoin-unban-two-sided", kick_and_ban)

    def reconnect():
        group_id = a.current()["id"]
        b.reconnect()
        b.wait(lambda s: s["state"].get("browse") is not None, "browse after reconnect")
        if b.current() is None:
            b.click("button.join")
            b.field("joinPassword", "changed")
            b.click("button.join")
        b.wait(lambda s: s["state"].get("current", {}).get("id") == group_id, "membership restored after reconnect")
        b.wait(lambda s: s["state"]["current"]["onlinePlayers"] == 2, "reconnected peer appears online in group")
        a.refresh()
        a.wait(lambda s: s["state"]["current"]["onlinePlayers"] == 2, "owner sees reconnected peer")
    check("real-client-disconnect-reconnect-and-refresh", reconnect)

    def transfer():
        a.click("button.manage")
        a.click("button.transfer")
        a.click(text="PVBridgeB")
        a.click("button.confirm_transfer")
        a.click("button.confirm_transfer")
        a.wait(lambda s: not s["state"]["current"]["isOwner"], "former owner loses ownership")
        b.refresh()
        b.wait(lambda s: s["state"]["current"]["isOwner"], "peer gains ownership")
    check("transfer-two-sided-ownership", transfer)

    def delete():
        b.click("button.manage")
        b.click("button.delete")
        b.click("button.delete")
        b.wait(lambda s: s["state"].get("current") is None, "delete acknowledged")
        a.refresh()
        a.wait(lambda s: s["state"].get("current") is None and not s["state"]["browse"]["groups"], "former owner sees deletion")
    check("delete-confirmation-two-sided-removal", delete)


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--game", default="26.2")
    parser.add_argument("--client-loader", default="fabric")
    parser.add_argument("--platform", default="paper", choices=["paper", "folia", "spigot", "craftbukkit", "fabric", "forge", "neoforge", "velocity", "bungeecord"])
    parser.add_argument("--matrix", action="store_true")
    parser.add_argument("--client-matrix", action="store_true", help="One real server per each of the 31 selected client targets")
    parser.add_argument("--discover", action="store_true")
    parser.add_argument("--resume", action="store_true")
    parser.add_argument("--targets", nargs="*")
    parser.add_argument("--port", type=int, default=25670)
    args = parser.parse_args()
    if args.discover:
        print(json.dumps(discover_matrix(), indent=2))
        return
    if args.matrix or args.client_matrix:
        sys.exit(run_matrix(args))
    directory = WORK / "runs" / (args.platform + "-" + args.game + "-" + args.client_loader)
    directory.mkdir(parents=True, exist_ok=True)
    (directory / "result.json").unlink(missing_ok=True)
    report = {"scenarioVersion": SCENARIO_VERSION, "coverage": "Real Minecraft clients, production Plasmo TCP transport, bridge and Groups addon. GUI widget events; no fixture replies. Forged permission-negative request is explicitly separate from UI checks.", "status": "failed", "scenarios": []}
    clients = []
    process = None
    log = None
    proxy = None
    proxy_log = None
    started = time.monotonic()
    phase = "prepare"
    try:
        target = next(t for t in matrix.targets() if t["game"] == args.game and t["loader"] == args.client_loader)
        report["clientLoader"] = args.client_loader
        report["clientArtifact"] = target["name"]
        # Modern Paper PV needs Java 17 even on the legacy 1.16 server.
        java = matrix.java_for("1.18.2" if args.game == "1.16.5" else args.game)
        launch_port = args.port + 1 if args.platform in {"velocity", "bungeecord"} else args.port
        if args.platform in {"fabric", "forge", "neoforge"}:
            server, command, manifest = prepare_modded(target, directory, args.port)
        else:
            if args.platform in {"spigot", "craftbukkit"}:
                server, manifest = prepare_bukkit(args.game, directory, launch_port, args.platform)
            else:
                server, manifest = prepare_paper(args.game, directory, launch_port, "folia" if args.platform == "folia" else "paper")
            command = [java, "-Xms512M", "-Xmx2G"]
            if args.game == "1.16.5":
                command.append("-DPaper.IgnoreJavaVersion=true")
            command += ["-jar", "server.jar", "--nogui"]
        report["server"] = manifest
        report.update(artifact_hashes(target))
        report["driverAgentSha256"] = hashlib.sha256((ROOT / "build/ui-matrix/bridge-e2e-agent.jar").read_bytes()).hexdigest()
        report["driverVersion"] = SCENARIO_VERSION
        environment = dict(os.environ)
        if args.platform in {"velocity", "bungeecord"}:
            environment["PLASMO_VOICE_FORWARDING_SECRET"] = str(uuid.uuid4())
            (server / "plugins/pv-addon-groups.jar").unlink(missing_ok=True)
            (server / "plugins/pv-addon-groups-gui.jar").unlink(missing_ok=True)
        log = (directory / "server.log").open("w", encoding="utf8")
        phase = "server-start"
        process = subprocess.Popen(command, cwd=server, stdin=subprocess.PIPE, stdout=log, stderr=subprocess.STDOUT, text=True, env=environment)
        deadline = time.monotonic() + 240
        while time.monotonic() < deadline:
            content = (directory / "server.log").read_text(encoding="utf8", errors="replace")
            if "Done (" in content:
                break
            if process.poll() is not None:
                raise AssertionError("Server exited; see server.log")
            time.sleep(.5)
        else:
            raise AssertionError("Server startup timeout")
        if args.platform in {"velocity", "bungeecord"}:
            phase = "proxy-prepare"
            proxy_directory, proxy_manifest = prepare_proxy(args.platform, args.game, directory, args.port)
            report["proxy"] = proxy_manifest
            proxy_log = (directory / "proxy.log").open("w", encoding="utf8")
            proxy = subprocess.Popen([matrix.java_for("26.2"), "-Xmx1G", "-jar", "proxy.jar"], cwd=proxy_directory, stdin=subprocess.PIPE, stdout=proxy_log, stderr=subprocess.STDOUT, text=True, env=environment)
            phase = "proxy-start"
            deadline = time.monotonic() + 90
            while time.monotonic() < deadline:
                content = (directory / "proxy.log").read_text(encoding="utf8", errors="replace")
                if "Listening on" in content or "Listening on /" in content:
                    break
                if proxy.poll() is not None:
                    raise AssertionError("Proxy exited; see proxy.log")
                time.sleep(.5)
            else:
                raise AssertionError("Proxy startup timeout")
        for name in ("PVBridgeA", "PVBridgeB"):
            phase = "client-connect-" + name
            clients.append(Client(target, directory, name, args.port))
            # Avoid login throttles and make failures identify the specific client.
            clients[-1].wait(lambda s: s.get("voiceConnected"), "initial Plasmo connection for " + name, 150)
        phase = "scenarios"
        scenarios(*clients, report["scenarios"])
        report["status"] = "passed"
    except Exception as error:
        traceback.print_exc()
        report["error"] = repr(error)
        report["failurePhase"] = phase
        report["failureKind"] = "runtime-assertion-or-driver" if phase == "scenarios" else "setup-or-download"
        print(report["error"], flush=True)
    finally:
        for client in clients:
            client.close()
        if proxy and proxy.poll() is None:
            proxy.terminate()
            try:
                proxy.wait(timeout=15)
            except subprocess.TimeoutExpired:
                proxy.kill()
                proxy.wait()
        if proxy_log:
            proxy_log.close()
        if process and process.poll() is None:
            process.stdin.write("stop\n")
            process.stdin.flush()
            try:
                process.wait(timeout=30)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait()
        if log:
            log.close()
        report["seconds"] = round(time.monotonic() - started, 1)
        report["target"] = args.platform + "-" + args.game
        (directory / "result.json").write_text(json.dumps(report, indent=2), encoding="utf8")
    sys.exit(0 if report["status"] == "passed" else 1)


def run_matrix(args):
    WORK.mkdir(parents=True, exist_ok=True)
    if args.client_matrix:
        cells = client_matrix_cells()
        report_name = "client-report"
        (WORK / "client-matrix.json").write_text(json.dumps(cells, indent=2), encoding="utf8")
    else:
        if not (WORK / "matrix.json").exists():
            discover_matrix()
        cells = json.loads((WORK / "matrix.json").read_text())["cells"]
        report_name = "report"
    if args.targets:
        cells = [c for c in cells if c["platform"] + "-" + c["game"] in args.targets]
    # Current lines first, then older compatibility lines. Every cell writes its own report.
    order = ["paper", "fabric", "neoforge", "forge", "folia", "velocity", "bungeecord", "spigot", "craftbukkit"]
    cells.sort(key=lambda c: (c["platform"] not in {"spigot", "craftbukkit"}, tuple(map(int, c["game"].split("."))), -order.index(c["platform"])), reverse=True)
    summary = {"coverage": "Two real Minecraft clients through the actual Plasmo server/proxy bridge, not fixture replies", "totalCells": len(cells), "results": []}
    for cell in cells:
        name = cell["platform"] + "-" + cell["game"]
        loader = cell.get("clientLoader", cell["platform"] if cell["platform"] in {"fabric", "forge", "neoforge"} else "fabric")
        result_path = WORK / "runs" / (name + "-" + loader) / "result.json"
        old = json.loads(result_path.read_text()) if result_path.exists() else None
        target = next(t for t in matrix.targets() if t["game"] == cell["game"] and t["loader"] == loader)
        hashes = artifact_hashes(target)
        if not cell.get("upstream"):
            result = dict(cell, target=name, status="unsupported", reason="No matching upstream release in the official catalog")
        elif args.resume and old and old.get("status") == "passed" and old.get("scenarioVersion") == SCENARIO_VERSION and all(old.get(key) == value for key, value in hashes.items()):
            result = old
        else:
            print("START " + name + " client=" + loader, flush=True)
            output = WORK / (name + "-" + loader + ".log")
            with output.open("w", encoding="utf8") as log:
                process = subprocess.Popen([sys.executable, str(Path(__file__).resolve()), "--game", cell["game"], "--platform", cell["platform"], "--client-loader", loader, "--port", str(args.port)], cwd=ROOT, stdout=log, stderr=subprocess.STDOUT)
                try:
                    process.wait(timeout=1500)
                except subprocess.TimeoutExpired:
                    # Only this runner's isolated child and its descendants, never an existing server.
                    subprocess.run(["taskkill", "/PID", str(process.pid), "/T", "/F"], stdout=log, stderr=subprocess.STDOUT)
                    process.wait()
            result = json.loads(result_path.read_text()) if result_path.exists() else dict(cell, target=name, status="failed", failureKind="setup-or-timeout", error="Runner exited " + str(process.returncode))
        result["clientLoader"] = loader
        result["clientArtifact"] = target["name"]
        summary["results"].append(result)
        (WORK / (report_name + ".json")).write_text(json.dumps(summary, indent=2), encoding="utf8")
        counts = {status: sum(r["status"] == status for r in summary["results"]) for status in ("passed", "failed", "unsupported")}
        lines = ["# Bridge E2E matrix", "", summary["coverage"], "", f"Completed {len(summary['results'])}/{len(cells)}. Passed {counts['passed']}, failed {counts['failed']}, unsupported {counts['unsupported']}.", "", "| Server / Minecraft | Client loader | Result | Passed scenarios | Evidence |", "| --- | --- | --- | ---: | --- |"]
        for result in summary["results"]:
            target = result["target"]
            loader = result["clientLoader"]
            evidence = str(WORK / "runs" / (target + "-" + loader) / "result.json")
            lines.append(f"| {target} | {loader} | {result['status']} | {len(result.get('scenarios', []))} | [result]({evidence}) |")
        (WORK / (report_name + ".md")).write_text("\n".join(lines) + "\n", encoding="utf8")
        print(name + " " + result["status"] + " " + json.dumps(counts), flush=True)
    return 1 if any(r["status"] == "failed" for r in summary["results"]) else 0


if __name__ == "__main__":
    main()
