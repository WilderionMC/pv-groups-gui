import unittest
import hashlib
import io
from pathlib import Path
import tempfile
from unittest.mock import patch
import run_ui_matrix
from run_ui_matrix import targets


class MatrixSelectionTest(unittest.TestCase):
    def test_latest_patch_per_supported_compatibility_line(self):
        matrix = targets()
        pairs = {(t["game"], t["loader"]) for t in matrix}
        self.assertEqual(31, len(matrix))
        self.assertEqual(len(matrix), len(pairs))
        self.assertEqual(15, len({t["game"] for t in matrix}))
        self.assertNotIn(("1.21.6", "neoforge"), pairs)
        self.assertNotIn(("26.1", "fabric"), pairs)
        self.assertNotIn(("1.20.6", "fabric"), pairs)
        artifact = {(t["game"], t["loader"]): t["name"] for t in matrix}
        self.assertEqual("fabric-1.21.6", artifact["1.21.8", "fabric"])
        self.assertEqual("neoforge-1.21.7", artifact["1.21.8", "neoforge"])
        self.assertEqual("fabric-26.1", artifact["26.1.2", "fabric"])

    def test_historical_fabric_api_without_primary_flag_is_installed(self):
        payload = b"verified historical Fabric API"
        version = {"version_type": "release", "files": [{"primary": False, "filename": "fabric-api.jar",
            "url": "https://example.invalid/fabric-api.jar", "hashes": {"sha512": hashlib.sha512(payload).hexdigest()}}]}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            mods = directory / "mods"
            mods.mkdir()
            with patch.object(run_ui_matrix, "WORK", directory), patch.object(run_ui_matrix, "fetch", return_value=[version]), patch("urllib.request.urlopen", return_value=io.BytesIO(payload)):
                run_ui_matrix.fabric_api("1.16.5", directory, mods)
            self.assertEqual(payload, (mods / "fabric-api.jar").read_bytes())

    def test_corrupt_fabric_api_is_not_installed(self):
        version = {"version_type": "release", "files": [{"primary": True, "filename": "fabric-api.jar",
            "url": "https://example.invalid/fabric-api.jar", "hashes": {"sha512": "0" * 128}}]}
        with tempfile.TemporaryDirectory() as temporary:
            directory = Path(temporary)
            mods = directory / "mods"
            mods.mkdir()
            with patch.object(run_ui_matrix, "WORK", directory), patch.object(run_ui_matrix, "fetch", return_value=[version]), patch("urllib.request.urlopen", return_value=io.BytesIO(b"corrupt")):
                with self.assertRaisesRegex(RuntimeError, "checksum mismatch"):
                    run_ui_matrix.fabric_api("1.16.5", directory, mods)
            self.assertFalse((mods / "fabric-api.jar").exists())


if __name__ == "__main__":
    unittest.main()
