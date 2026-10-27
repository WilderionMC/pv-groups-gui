import json
from contextlib import closing
from pathlib import Path
import sqlite3
import tempfile
import unittest
from unittest.mock import patch
import zipfile

from sync_local_test import ROOT, digest, prepare_server, sync_profiles


def mod(path, mod_id, version):
    path.parent.mkdir(parents=True, exist_ok=True)
    with zipfile.ZipFile(path, "w") as jar:
        jar.writestr("fabric.mod.json", json.dumps({"id": mod_id, "version": version}))


class LocalInstallationTest(unittest.TestCase):
    def test_matching_profile_backup_idempotence_and_unrelated_files(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            app = base / "ModrinthApp"
            mods = app / "profiles" / "Current" / "mods"
            mod(mods / "voice.jar", "plasmovoice", "2.1.17")
            old = mods / "local-gui.jar"
            mod(old, "pv-addon-groups-gui", "old-build")
            old_hash = digest(old)
            mod(mods / "other.jar", "unrelated-mod", "1")
            other_hash = digest(mods / "other.jar")
            with closing(sqlite3.connect(app / "app.db")) as db:
                db.executescript("""
                    CREATE TABLE instances(id TEXT,path TEXT,name TEXT,applied_content_set_id TEXT);
                    CREATE TABLE instance_content_sets(id TEXT,game_version TEXT,loader TEXT);
                    CREATE TABLE instance_files(id TEXT,instance_id TEXT,relative_path TEXT);
                    CREATE TABLE store_instance_files(file_id TEXT);
                    INSERT INTO instances VALUES('one','Current','Current','set1'),('two','Old','Old','set2');
                    INSERT INTO instance_content_sets VALUES('set1','26.2','fabric'),('set2','1.20.6','fabric');
                """)
            config = {"modrinthRoot": str(app)}
            artifacts = ROOT / "build/artifacts/plasmo-ui"
            backup = base / "backups"
            with patch("sync_local_test.os.replace", side_effect=PermissionError("game is running")):
                self.assertEqual(sync_profiles(config, artifacts, backup)[0]["status"], "pending")
            self.assertEqual(digest(old), old_hash)
            self.assertFalse(list(mods.glob(".pv-gui-*.tmp")))
            first = sync_profiles(config, artifacts, backup)
            self.assertEqual([r["status"] for r in first], ["updated", "skipped"])
            source = artifacts / "pv-addon-groups-gui-fabric-26.2-1.0.0.jar"
            self.assertEqual(digest(old), digest(source))
            self.assertEqual(digest(mods / "other.jar"), other_hash)
            saved = list(backup.rglob("*.jar"))
            self.assertEqual(len(saved), 1)
            self.assertEqual(digest(saved[0]), old_hash)
            self.assertEqual(sync_profiles(config, artifacts, backup)[0]["status"], "unchanged")
            self.assertEqual(len(list(backup.rglob("*.jar"))), 1)

    def test_managed_modrinth_file_is_not_overwritten(self):
        with tempfile.TemporaryDirectory() as temporary:
            app = Path(temporary)
            mods = app / "profiles/Managed/mods"
            mod(mods / "voice.jar", "plasmovoice", "2.1.17")
            gui = mods / "gui.jar"
            mod(gui, "pv-addon-groups-gui", "managed-build")
            original = digest(gui)
            with closing(sqlite3.connect(app / "app.db")) as db:
                db.executescript("""
                    CREATE TABLE instances(id TEXT,path TEXT,name TEXT,applied_content_set_id TEXT);
                    CREATE TABLE instance_content_sets(id TEXT,game_version TEXT,loader TEXT);
                    CREATE TABLE instance_files(id TEXT,instance_id TEXT,relative_path TEXT);
                    CREATE TABLE store_instance_files(file_id TEXT);
                    INSERT INTO instances VALUES('one','Managed','Managed','set1');
                    INSERT INTO instance_content_sets VALUES('set1','26.2','fabric');
                    INSERT INTO instance_files VALUES('file','one','mods/gui.jar');
                    INSERT INTO store_instance_files VALUES('file');
                """)
            with self.assertRaisesRegex(RuntimeError, "managed by Modrinth"):
                sync_profiles({"modrinthRoot": str(app)}, ROOT / "build/artifacts/plasmo-ui", app / "backups")
            self.assertEqual(digest(gui), original)

    def test_server_is_separate_and_existing_settings_survive(self):
        with tempfile.TemporaryDirectory() as temporary:
            base = Path(temporary)
            seed = base / "existing-server"
            for relative in ("paper.jar", "plugins/PlasmoVoice.jar", "plugins/pv-addon-groups.jar", "eula.txt", "world/important-data"):
                file = seed / relative
                file.parent.mkdir(parents=True, exist_ok=True)
                file.write_text("original")
            server = base / "test-server"
            config = {"serverRoot": str(server), "serverSeedRoot": str(seed)}
            artifacts = ROOT / "build/artifacts/plasmo-ui"
            result = prepare_server(config, artifacts, base / "backups")
            self.assertFalse(result["started"])
            self.assertFalse((server / "world").exists())
            self.assertEqual(digest(server / "plugins/pv-addon-groups-gui-1.0.0.jar"), digest(artifacts / "pv-addon-groups-gui-1.0.0.jar"))
            (server / "server.properties").write_text("custom-test-settings")
            prepare_server(config, artifacts, base / "backups")
            self.assertEqual((server / "server.properties").read_text(), "custom-test-settings")
            self.assertEqual((seed / "world/important-data").read_text(), "original")


if __name__ == "__main__":
    unittest.main()
