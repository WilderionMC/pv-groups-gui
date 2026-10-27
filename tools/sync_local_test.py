"""Install verified local builds into matching Modrinth profiles and the isolated test server."""
import hashlib
from contextlib import closing
import json
import os
from pathlib import Path
import shutil
import sqlite3
import tempfile
import tomllib
import zipfile

ROOT = Path(__file__).resolve().parents[1]
GUI_IDS = {"pv-addon-groups-gui", "pv_groups_gui"}


def digest(path):
    return hashlib.sha256(path.read_bytes()).hexdigest()


def replace_file(source, destination, backups):
    destination.parent.mkdir(parents=True, exist_ok=True)
    if destination.exists():
        if digest(source) == digest(destination):
            return "unchanged"
        backups.mkdir(parents=True, exist_ok=True)
        backup = backups / (digest(destination)[:16] + "-" + destination.name)
        if not backup.exists():
            shutil.copy2(destination, backup)
    fd, temporary = tempfile.mkstemp(prefix=".pv-gui-", suffix=".tmp", dir=destination.parent)
    os.close(fd)
    try:
        shutil.copyfile(source, temporary)
        os.replace(temporary, destination)
    finally:
        Path(temporary).unlink(missing_ok=True)
    if digest(source) != digest(destination):
        raise RuntimeError(f"Copy verification failed: {destination}")
    return "updated"


def mod_info(path):
    try:
        with zipfile.ZipFile(path) as jar:
            if "fabric.mod.json" in jar.namelist():
                data = json.loads(jar.read("fabric.mod.json"))
                return data.get("id"), data.get("version", "")
            for name in ("META-INF/neoforge.mods.toml", "META-INF/mods.toml"):
                if name in jar.namelist():
                    data = tomllib.loads(jar.read(name).decode())
                    mods = data.get("mods", [])
                    if mods:
                        return mods[0].get("modId"), mods[0].get("version", "")
    except (zipfile.BadZipFile, ValueError, KeyError):
        pass
    return None, ""


def sync_profiles(config, artifacts, backups):
    app = Path(config["modrinthRoot"])
    profiles = app / "profiles"
    results = []
    targets = json.loads((ROOT / "client-targets.json").read_text(encoding="utf-8"))
    with closing(sqlite3.connect((app / "app.db").as_uri() + "?mode=ro", uri=True)) as database:
        rows = database.execute("""
            SELECT i.id, i.path, i.name, c.game_version, c.loader
            FROM instances i JOIN instance_content_sets c ON c.id=i.applied_content_set_id
        """).fetchall()
        for instance_id, relative, name, game, loader in rows:
            target = next((t for t in targets if game in t["minecraft"] and t["loader"] == loader.lower()), None)
            if target is None:
                results.append({"profile": name, "status": "skipped", "reason": f"unsupported {loader} {game}"})
                continue
            directory = (profiles / relative).resolve()
            if not directory.is_relative_to(profiles.resolve()):
                raise RuntimeError(f"Profile path escapes Modrinth profiles: {relative}")
            mods = directory / "mods"
            jars = list(mods.glob("*.jar"))
            info = [(jar, *mod_info(jar)) for jar in jars]
            if not any(mod_id == "plasmovoice" and version.split("+")[0] == "2.1.17" for _, mod_id, version in info):
                results.append({"profile": name, "status": "skipped", "reason": "requires Plasmo Voice 2.1.17"})
                continue
            disabled = [jar for jar in mods.glob("*.jar.disabled") if mod_info(jar)[0] in GUI_IDS]
            installed = [jar for jar, mod_id, _ in info if mod_id in GUI_IDS]
            if disabled and not installed:
                results.append({"profile": name, "status": "skipped", "reason": "GUI is disabled"})
                continue
            for jar in installed:
                managed = database.execute("""
                    SELECT 1 FROM instance_files f JOIN store_instance_files s ON s.file_id=f.id
                    WHERE f.instance_id=? AND f.relative_path=?
                """, (instance_id, jar.relative_to(directory).as_posix())).fetchone()
                if managed:
                    raise RuntimeError(f"{name}: GUI is managed by Modrinth's content store; use the app's Upload action")
            source = artifacts / f"pv-addon-groups-gui-{target['name']}-1.0.0.jar"
            # Retain the existing local file path so Modrinth does not retain a dangling file entry.
            destination = installed[0] if installed else mods / source.name
            try:
                result = replace_file(source, destination, backups / "profiles" / relative)
            except PermissionError:
                results.append({"profile": name, "status": "pending", "reason": "JAR is locked; close this Minecraft client and rerun the build"})
                continue
            for duplicate in installed[1:]:
                backup_dir = backups / "profiles" / relative
                backup_dir.mkdir(parents=True, exist_ok=True)
                shutil.copy2(duplicate, backup_dir / (digest(duplicate)[:16] + "-" + duplicate.name))
                duplicate.unlink()
            results.append({"profile": name, "status": result, "target": target["name"], "file": str(destination)})
    return results


def prepare_server(config, artifacts, backups):
    server = Path(config["serverRoot"]).resolve()
    seed = Path(config["serverSeedRoot"]).resolve()
    marker = server / ".pv-groups-gui-test-server.json"
    if server == seed:
        raise RuntimeError("The dedicated test server must be separate from the existing server")
    if server.exists() and any(server.iterdir()) and not marker.exists():
        raise RuntimeError(f"Refusing to use an unmarked nonempty server directory: {server}")
    server.mkdir(parents=True, exist_ok=True)
    marker.write_text(json.dumps({"project": str(ROOT), "minecraft": "26.2", "tcpPort": 25567, "voicePort": 24467}, indent=2))
    for relative in ("paper.jar", "plugins/PlasmoVoice.jar", "plugins/pv-addon-groups.jar", "eula.txt"):
        destination = server / relative
        if not destination.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            shutil.copy2(seed / relative, destination)
    defaults = {
        "server.properties": "server-ip=127.0.0.1\nserver-port=25567\nlevel-name=world\nonline-mode=true\nmax-players=8\nview-distance=6\nsimulation-distance=6\nspawn-protection=0\nmotd=PV Groups GUI local test\n",
        "plugins/PlasmoVoice/config.toml": '[host]\nip = "127.0.0.1"\nport = 24467\n',
    }
    for relative, text in defaults.items():
        destination = server / relative
        if not destination.exists():
            destination.parent.mkdir(parents=True, exist_ok=True)
            destination.write_text(text, encoding="utf-8")
    shutil.copy2(ROOT / "tools/start-test-server.ps1", server / "start.ps1")
    bridge = artifacts / "pv-addon-groups-gui-1.0.0.jar"
    status = replace_file(bridge, server / "plugins" / bridge.name, backups / "server")
    return {"directory": str(server), "status": status, "address": "127.0.0.1:25567", "started": False}


def main():
    config = json.loads((ROOT / ".local-test.json").read_text(encoding="utf-8"))
    artifacts = ROOT / "build/artifacts/plasmo-ui"
    for line in (artifacts / "SHA256SUMS.txt").read_text().splitlines():
        expected, name = line.split("  ", 1)
        if digest(artifacts / name) != expected:
            raise RuntimeError(f"Artifact checksum mismatch: {name}")
    backups = Path(config["backupRoot"])
    report = {"profiles": sync_profiles(config, artifacts, backups), "server": prepare_server(config, artifacts, backups)}
    (ROOT / "build/local-test-sync.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
    print(json.dumps(report, ensure_ascii=False, indent=2))
    if any(profile["status"] == "pending" for profile in report["profiles"]):
        raise RuntimeError("Some client JARs are locked. The server was prepared; close the listed clients and rerun installation.")


if __name__ == "__main__":
    main()
