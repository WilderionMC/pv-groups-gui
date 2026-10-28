import json
from pathlib import Path
import tempfile
from types import SimpleNamespace
import unittest
from unittest.mock import patch

import run_bridge_e2e as runner


class ClientMatrixTest(unittest.TestCase):
    def test_fresh_workspace_creates_matrix_and_report(self):
        cell = dict(platform="paper", game="26.2", clientLoader="fabric", clientArtifact="fabric-26.2", upstream=False)
        args = SimpleNamespace(client_matrix=True, targets=None, resume=False, port=25670)
        with tempfile.TemporaryDirectory() as directory:
            work = Path(directory) / "new-workspace"
            with patch.object(runner, "WORK", work), patch.object(runner, "client_matrix_cells", return_value=[cell]), patch.object(runner, "artifact_hashes", return_value={}):
                self.assertEqual(0, runner.run_matrix(args))
            self.assertEqual([cell], json.loads((work / "client-matrix.json").read_text()))
            result = json.loads((work / "client-report.json").read_text())["results"][0]
            self.assertEqual("unsupported", result["status"])

    def test_each_client_has_exactly_one_same_game_server(self):
        cells = runner.client_matrix_cells()
        expected = {(t["loader"], t["game"]) for t in runner.matrix.targets()}
        self.assertEqual(31, len(cells))
        self.assertEqual(expected, {(c["clientLoader"], c["game"]) for c in cells})
        self.assertEqual({"paper", "folia", "fabric", "forge", "neoforge", "velocity", "bungeecord"}, {c["platform"] for c in cells})
        for cell in cells:
            if cell["platform"] in {"fabric", "forge", "neoforge"}:
                self.assertEqual(cell["platform"], cell["clientLoader"])


class CommandPublicationTest(unittest.TestCase):
    def test_read_lock_retries_same_command_without_replaying(self):
        with tempfile.TemporaryDirectory() as directory:
            client = runner.Client.__new__(runner.Client)
            client.directory = Path(directory)
            client.sequence = 8
            client.wait = lambda predicate, description: {"sequence": 9}
            replace = runner.os.replace
            attempts = []

            def locked_once(source, destination):
                attempts.append(json.loads(source.read_text()))
                if len(attempts) == 1:
                    raise PermissionError("reader holds command.json")
                replace(source, destination)

            with patch.object(runner.os, "replace", side_effect=locked_once), patch.object(runner.time, "sleep"):
                client.command("refresh")
            self.assertEqual(attempts, [{"action": "refresh", "sequence": 9}] * 2)
            self.assertEqual(json.loads((client.directory / "command.json").read_text()), attempts[0])

    def test_persistent_lock_fails_after_deadline(self):
        with tempfile.TemporaryDirectory() as directory:
            client = runner.Client.__new__(runner.Client)
            client.directory = Path(directory)
            client.sequence = 0
            with patch.object(runner.os, "replace", side_effect=PermissionError("locked")), patch.object(runner.time, "monotonic", side_effect=[0, 3]):
                with self.assertRaises(PermissionError):
                    client.command("refresh")
            self.assertEqual(client.sequence, 1)


if __name__ == "__main__":
    unittest.main()
