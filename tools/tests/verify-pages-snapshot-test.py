"""Contracts for the manual Pages publication guard."""
from importlib.machinery import SourceFileLoader
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest

verify = SourceFileLoader("pages_verifier", str(Path(__file__).parents[1] / "verify-pages-snapshot.py")).load_module().verify

class SnapshotTest(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.source = "a" * 40
        self.version = "1.4.0.test"
        subprocess.run(["git", "init", "-q", str(self.root)], check=True)
        self.hashes = {}
        for name in ["content.jar", "artifacts.jar", "index.html", "downloads/agent-host-package.json"]:
            path = self.root / name
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(b"fixture")
            self.hashes[name] = hashlib.sha256(b"fixture").hexdigest()
        self.commit()

    def commit(self):
        (self.root / "release-manifest.json").write_text(json.dumps({"version": self.version, "source_commit": self.source, "files_sha256": self.hashes}))
        subprocess.run(["git", "-C", str(self.root), "add", "-A"], check=True, stdout=subprocess.DEVNULL)
        subprocess.run(["git", "-C", str(self.root), "-c", "user.name=Fixture", "-c", "user.email=fixture@invalid", "commit", "-qm", "fixture"], check=True)
        self.head = subprocess.check_output(["git", "-C", str(self.root), "rev-parse", "HEAD"], text=True).strip()

    def check(self, **args):
        verify(self.root, args.get("pages", self.head), args.get("source", self.source), args.get("version", self.version))

    def test_exact_clean_snapshot(self):
        self.check()

    def test_exact_commits_only(self):
        with self.assertRaises(ValueError): self.check(pages="gh-pages")
        with self.assertRaises(ValueError): self.check(pages="b" * 40)

    def test_release_identity(self):
        with self.assertRaises(ValueError): self.check(source="b" * 40)
        with self.assertRaises(ValueError): self.check(version="1.4.0.other")

    def test_dirty_bytes(self):
        (self.root / "index.html").write_bytes(b"changed")
        with self.assertRaisesRegex(ValueError, "clean"): self.check()

    def test_committed_wrong_hash(self):
        self.hashes["index.html"] = "b" * 64
        self.commit()
        with self.assertRaisesRegex(ValueError, "bytes changed"): self.check()

    def test_incomplete_inventory(self):
        del self.hashes["content.jar"]
        self.commit()
        with self.assertRaisesRegex(ValueError, "Incomplete"): self.check()

    def test_parent_traversal(self):
        self.hashes["../outside"] = "a" * 64
        self.commit()
        with self.assertRaisesRegex(ValueError, "Invalid manifest"): self.check()

if __name__ == "__main__":
    unittest.main()
