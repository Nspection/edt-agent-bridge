"""Contracts for the manual Pages publication guard."""
from importlib.machinery import SourceFileLoader
import hashlib
import json
import os
from pathlib import Path
import subprocess
import tempfile
import unittest
import sys
import zipfile
sys.path.insert(0, str(Path(__file__).parents[1]))

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
        for name in ["index.html", "downloads/agent-host-package.json",
                     f"plugins/com.codepilot1c.core_{self.version}.jar", f"plugins/com.codepilot1c.ui_{self.version}.jar",
                     f"features/com.codepilot1c.feature_{self.version}.jar",f"downloads/edt-agent-bridge-{self.version}-update-site.zip"]:
            path = self.root / name
            path.parent.mkdir(exist_ok=True)
            path.write_bytes(b"fixture")
            self.hashes[name] = hashlib.sha256(b"fixture").hexdigest()
        units = "".join(f'<unit id="{name}" version="{self.version}"/>' for name in ("com.codepilot1c.core", "com.codepilot1c.ui", "com.codepilot1c.feature.feature.jar", "com.codepilot1c.feature.feature.group"))
        artifacts = "".join(f'<artifact classifier="{classifier}" id="{name}" version="{self.version}"/>' for classifier,name in (("osgi.bundle","com.codepilot1c.core"),("osgi.bundle","com.codepilot1c.ui"),("org.eclipse.update.feature","com.codepilot1c.feature")))
        for name, xml in (("content",f"<repository><units>{units}</units></repository>"),("artifacts",f"<repository><artifacts>{artifacts}</artifacts></repository>")):
            with zipfile.ZipFile(self.root / (name+".jar"), "w") as jar: jar.writestr(name+".xml",xml)
            self.hashes[name+".jar"] = hashlib.sha256((self.root / (name+".jar")).read_bytes()).hexdigest()
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

    def test_each_mandatory_jar_is_required(self):
        for kind, name in (("plugins", "core"),("plugins","ui"),("features","feature")):
            member = f"{kind}/com.codepilot1c.{name}_{self.version}.jar"
            digest = self.hashes.pop(member)
            self.commit()
            with self.assertRaisesRegex(ValueError,"Incomplete"): self.check()
            self.hashes[member] = digest

    def test_only_verified_inventory_reaches_publication(self):
        (self.root / "private.txt").write_text("must never publish")
        self.commit()
        stage = self.root.parent / (self.root.name+"-stage")
        self.addCleanup(lambda: __import__("shutil").rmtree(stage, ignore_errors=True))
        verify(self.root,self.head,self.source,self.version,stage)
        self.assertFalse((stage / "private.txt").exists())
        self.assertEqual(set(self.hashes)|{"release-manifest.json"}, {p.relative_to(stage).as_posix() for p in stage.rglob("*") if p.is_file()})

    def test_unexpected_manifest_member_rejected(self):
        (self.root / "private.txt").write_bytes(b"fixture")
        self.hashes["private.txt"] = hashlib.sha256(b"fixture").hexdigest()
        self.commit()
        with self.assertRaisesRegex(ValueError,"Invalid manifest"): self.check()

    def test_metadata_version_is_bound(self):
        with zipfile.ZipFile(self.root / "content.jar","w") as jar: jar.writestr("content.xml","<repository><units/></repository>")
        self.hashes["content.jar"] = hashlib.sha256((self.root / "content.jar").read_bytes()).hexdigest()
        self.commit()
        with self.assertRaisesRegex(RuntimeError,"Missing tested"): self.check()

if __name__ == "__main__":
    unittest.main()
