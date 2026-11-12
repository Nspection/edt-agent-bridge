"""Package/installer boundary regressions. Only temporary files and fake director calls."""
from pathlib import Path
import gzip, importlib.util, json, os, shutil, subprocess, sys, tempfile, types, unittest, uuid, zipfile
from unittest.mock import patch
import xml.etree.ElementTree as ET
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
import bridge_package as packages
from bridge_provenance import VerificationRun, REQUIRED_STAGES, source_snapshot, artifacts_snapshot, sha256, write_json

def module(name, filename):
    spec = importlib.util.spec_from_file_location(name, Path(__file__).resolve().parents[1] / filename)
    value = importlib.util.module_from_spec(spec);spec.loader.exec_module(value);return value
installer = module("installer_fixture", "install-agent-bridge.py")
packager = module("packager_fixture", "package-agent-bridge.py")

class PackageTests(unittest.TestCase):
    def setUp(self):
        self.tmp = tempfile.TemporaryDirectory();self.addCleanup(self.tmp.cleanup)
        self.root = Path(self.tmp.name);self.build = self.root / ".runs/build";self.build.mkdir(parents=True)
        (self.root / "tools").mkdir();(self.root / "tools/required.py").write_text("required")
        for name in ("LICENSE", "THIRD_PARTY_NOTICES.md"):(self.root / name).write_text(name)
        self.version = "1.4.0.fixture"
        artifacts = []
        for bundle in ("core", "ui"):
            file = self.build / ("com.codepilot1c." + bundle + "_" + self.version + ".jar");file.write_bytes(bundle.encode());artifacts.append(str(file))
        self.meta = {"version": self.version, "artifacts": artifacts, "classpath": []}
        self.meta.update(source_snapshot=source_snapshot(self.root), artifact_sha256=artifacts_snapshot(self.meta))
        write_json(self.build / "build.json", self.meta)
        with VerificationRun(self.root, self.build, self.meta) as run:
            for stage in REQUIRED_STAGES:
                with run.stage(stage):pass
    def metadata(self, site):
        content = ET.Element("repository", name="fixture");units = ET.SubElement(content, "units")
        for name in ("com.codepilot1c.core", "com.codepilot1c.ui", "com.codepilot1c.feature.feature.jar", "com.codepilot1c.feature.feature.group"):
            ET.SubElement(units, "unit", id=name, version=self.version)
        artifacts = ET.Element("repository", name="fixture");items = ET.SubElement(artifacts, "artifacts")
        for classifier, name in (("osgi.bundle", "com.codepilot1c.core"), ("osgi.bundle", "com.codepilot1c.ui"), ("org.eclipse.update.feature", "com.codepilot1c.feature")):
            ET.SubElement(items, "artifact", classifier=classifier, id=name, version=self.version)
        for name, entry, tree in (("content.jar", "content.xml", content), ("artifacts.jar", "artifacts.xml", artifacts)):
            with zipfile.ZipFile(site / name, "w") as jar:jar.writestr(entry, ET.tostring(tree))
    def candidate(self):
        generation = self.build / "packages" / ("package-" + uuid.uuid4().hex)
        site = generation / "update-site";(site / "plugins").mkdir(parents=True);(site / "features").mkdir()
        for file in self.meta["artifacts"]:shutil.copyfile(file, site / "plugins" / Path(file).name)
        feature = site / "features" / ("com.codepilot1c.feature_" + self.version + ".jar");feature.write_bytes(b"feature")
        self.metadata(site)
        archive = generation / ("edt-agent-bridge-" + self.version + "-update-site.zip")
        with zipfile.ZipFile(archive, "w") as package:
            for file in site.rglob("*"):
                if file.is_file():package.write(file, file.relative_to(site).as_posix())
        return generation, site, archive, sha256(feature)
    def committed(self):
        generation, site, archive, feature = self.candidate()
        receipt = packages.commit_package(self.root, self.build, self.meta, generation, feature)
        return receipt, site, archive
    def test_verified_exact_generation_is_accepted(self):
        expected, site, archive = self.committed()
        self.assertEqual((expected, site, archive), packages.require_package(self.root, self.build, self.meta))
    def test_unverified_build_never_commits(self):
        generation, site, archive, feature = self.candidate()
        proof = json.loads((self.build / "verification.json").read_text());proof["passed"] = False;write_json(self.build / "verification.json", proof)
        with self.assertRaises(RuntimeError):packages.commit_package(self.root, self.build, self.meta, generation, feature)
        self.assertFalse((self.build / "package-verification.json").exists())
    def test_extra_plugin_and_metadata_iu_are_rejected(self):
        generation, site, archive, feature = self.candidate()
        foreign = site / "plugins/foreign.jar";foreign.write_bytes(b"foreign")
        with self.assertRaises(RuntimeError):packages.verify_site(site, self.version, self.meta["artifact_sha256"], feature)
        foreign.unlink()
        with zipfile.ZipFile(site / "content.jar") as jar:tree = ET.fromstring(jar.read("content.xml"))
        ET.SubElement(tree.find("units"), "unit", id="unverified.feature", version="1")
        with zipfile.ZipFile(site / "content.jar", "w") as jar:jar.writestr("content.xml", ET.tostring(tree))
        with self.assertRaises(RuntimeError):packages.verify_site(site, self.version, self.meta["artifact_sha256"], feature)
    def test_unverified_repository_index_is_not_published(self):
        generation, site, archive, feature = self.candidate()
        (site / "p2.index").write_text("metadata.repository.factory.order=unverified.xml")
        with self.assertRaises(RuntimeError):packages.verify_site(site, self.version, self.meta["artifact_sha256"], feature)
    def test_changed_site_or_archive_is_rejected(self):
        receipt, site, archive = self.committed()
        target = site / "plugins" / Path(self.meta["artifacts"][0]).name;original = target.read_bytes();target.write_bytes(b"different")
        with self.assertRaises(RuntimeError):packages.require_package(self.root, self.build, self.meta)
        target.write_bytes(original);archive.write_bytes(b"changed archive")
        with self.assertRaises(RuntimeError):packages.require_package(self.root, self.build, self.meta)
    def test_failed_candidate_does_not_replace_previous_pointer(self):
        old, _, _ = self.committed();generation, site, archive, feature = self.candidate()
        with zipfile.ZipFile(archive, "a") as package:package.writestr("injected", "unexpected")
        with self.assertRaises(RuntimeError):packages.commit_package(self.root, self.build, self.meta, generation, feature)
        self.assertEqual(old, json.loads((self.build / "package-verification.json").read_text()))
        packages.require_package(self.root, self.build, self.meta)
    def test_receipt_cannot_escape_generation(self):
        receipt, _, _ = self.committed();receipt["generation"] = "../outside";write_json(self.build / "package-verification.json", receipt)
        with self.assertRaises(RuntimeError):packages.require_package(self.root, self.build, self.meta)
    def test_fresh_publisher_never_reuses_stale_directories(self):
        stale = self.build / "p2-source/plugins";stale.mkdir(parents=True);(stale / "foreign.jar").write_bytes(b"old")
        legacy = self.build / "update-site";legacy.mkdir();(legacy / "unverified").write_bytes(b"old")
        edt = self.root / "edt";info = edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info";info.parent.mkdir(parents=True)
        lines = []
        for name in ("org.eclipse.osgi", "org.eclipse.equinox.launcher", "org.eclipse.equinox.simpleconfigurator"):
            jar = edt / (name + "_1.jar");jar.write_bytes(b"SDK fixture");lines.append(name + ",1," + jar.as_uri() + ",4,false")
        info.write_text("\n".join(lines))
        def fake_director(command, **kwargs):
            if "-source" in command:
                source = Path(command[command.index("-source") + 1]);uri = command[command.index("-metadataRepository") + 1];site = installer.file_path(uri)
                shutil.copytree(source / "plugins", site / "plugins");shutil.copytree(source / "features", site / "features");self.metadata(site)
            return types.SimpleNamespace(returncode=0)
        argv = ["package", "--build", str(self.build), "--edt-home", str(edt), "--java-home", str(self.root / "jdk")]
        with patch.object(packager, "__file__", str(self.root / "tools/package-agent-bridge.py")), patch.object(sys, "argv", argv), patch.object(packager.subprocess, "run", side_effect=fake_director):packager.main()
        receipt, site, archive = packages.require_package(self.root, self.build, self.meta)
        self.assertNotEqual(site, legacy);self.assertNotIn("foreign.jar", str(packages.regular_tree(site)));self.assertTrue((legacy / "unverified").exists())
    def installation(self):
        edt = self.root / "installation";config = edt / "configuration";config.mkdir(parents=True)
        shared = self.root / "shared";registry = shared / "org.eclipse.equinox.p2.engine/profileRegistry/fixture.profile";registry.mkdir(parents=True)
        (config / "config.ini").write_text("eclipse.p2.profile=fixture\neclipse.p2.data.area=" + shared.as_uri() + "\n")
        info = config / "org.eclipse.equinox.simpleconfigurator/bundles.info";info.parent.mkdir();info.write_text("before")
        def profile(timestamp, version, extra=False):
            tree = ET.Element("profile", timestamp=timestamp);units = ET.SubElement(tree, "units");ET.SubElement(units, "unit", id=installer.FEATURE, version=version);ET.SubElement(units, "unit", id="unrelated.plugin", version="1" if not extra else "2")
            path = registry / (timestamp + ".profile.gz")
            with gzip.open(path, "wb") as stream:stream.write(ET.tostring(tree))
            return path
        previous = profile("1", "old");before = installer.snapshot(installer.target(edt))
        backup = self.build / "install-backup";backup.mkdir();shutil.copyfile(info, backup / "bundles.info");shutil.copyfile(previous, backup / "previous.profile.gz")
        profile("2", self.version);info.write_text("after")
        context = installer.target(edt)
        state = {"state": "installed", "target": installer.identity(context), "before": before, "after": installer.snapshot(context), "package": {"version": self.version}}
        return edt, context, state, backup, profile
    def test_rollback_accepts_only_exact_post_apply_profile(self):
        edt, context, state, backup, profile = self.installation();installer.require_rollback(state, context, backup)
        profile("3", self.version, extra=True)
        with self.assertRaises(RuntimeError):installer.require_rollback(state, installer.target(edt), backup)
    def test_rollback_rejects_bundles_info_and_backup_drift(self):
        edt, context, state, backup, profile = self.installation()
        (backup / "bundles.info").write_text("corrupt")
        with self.assertRaises(RuntimeError):installer.require_rollback(state, context, backup)
        shutil.copyfile(backup / "previous.profile.gz", backup / "unrelated")
        (edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info").write_text("foreign change")
        with self.assertRaises(RuntimeError):installer.require_rollback(state, context, backup)
    def test_corrupt_backup_is_rejected_before_any_director_call(self):
        receipt, site, archive = self.committed();edt, context, state, backup, profile = self.installation()
        write_json(self.build / "install-state.json", state);(backup / "previous.profile.gz").write_bytes(b"corrupt")
        with patch.object(installer, "require_package", return_value=(receipt, site, archive)), patch.object(installer.subprocess, "run") as director:
            with self.assertRaises(RuntimeError):installer.main(["--build", str(self.build), "--edt-home", str(edt), "--java-home", str(self.root / "jdk"), "--rollback"])
            director.assert_not_called()
    def test_local_rollback_does_not_require_current_sources_or_package(self):
        edt, context, state, backup, profile = self.installation();write_json(self.build / "install-state.json", state)
        (self.build / "build.json").unlink();self.source_missing = self.root / "tools/required.py";self.source_missing.unlink()
        def fake_director(command, **kwargs):
            self.assertIn("-revert", command);self.assertNotIn("-repository", command);self.assertIn("-verifyOnly", command)
            self.assertEqual("1", command[command.index("-revert") + 1]);return types.SimpleNamespace(returncode=0)
        with patch.object(installer, "require_package", side_effect=AssertionError("Rollback must not require new source provenance")), patch.object(installer, "configuration", return_value=self.root / "launcher.jar"), patch.object(installer.subprocess, "run", side_effect=fake_director) as director:
            installer.main(["--build", str(self.build), "--edt-home", str(edt), "--java-home", str(self.root / "jdk"), "--rollback"])
            self.assertEqual(1, director.call_count)
    def test_second_process_cannot_claim_same_installation(self):
        script = str(Path(installer.__file__).resolve())
        code = "import importlib.util,sys;sys.path.insert(0,sys.argv[1]);s=importlib.util.spec_from_file_location('installer_lock_fixture',sys.argv[2]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);lock=m.install_lock(sys.argv[3]);lock.__enter__();print('locked',flush=True);sys.stdin.readline();lock.__exit__(None,None,None)"
        child = subprocess.Popen([sys.executable, "-c", code, str(Path(script).parent), script, str(self.build)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual("locked", child.stdout.readline().strip())
            with patch.object(installer, "execute") as execute:
                with self.assertRaisesRegex(RuntimeError, "Another installer"):
                    installer.main(["--build", str(self.build), "--edt-home", str(self.root), "--java-home", str(self.root), "--apply", "--resume"])
                execute.assert_not_called()
        finally:
            child.communicate("done\n", timeout=5)
            if child.poll() is None:child.kill();child.wait(timeout=5)
        with installer.install_lock(self.build):pass
    def test_different_builds_share_the_target_profile_lock(self):
        edt, context, state, backup, profile = self.installation()
        other_build = self.root / "another-build";other_build.mkdir()
        script = str(Path(installer.__file__).resolve())
        code = "import importlib.util,sys;sys.path.insert(0,sys.argv[1]);s=importlib.util.spec_from_file_location('target_lock_fixture',sys.argv[2]);m=importlib.util.module_from_spec(s);s.loader.exec_module(m);lock=m.target_lock(m.target(m.Path(sys.argv[3])));lock.__enter__();print('locked',flush=True);sys.stdin.readline();lock.__exit__(None,None,None)"
        child = subprocess.Popen([sys.executable, "-c", code, str(Path(script).parent), script, str(edt)], stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE, text=True)
        try:
            self.assertEqual("locked", child.stdout.readline().strip())
            with patch.object(installer, "execute") as execute:
                with self.assertRaisesRegex(RuntimeError, "Another installer"):
                    installer.main(["--build", str(other_build), "--edt-home", str(edt), "--java-home", str(self.root), "--apply"])
                execute.assert_not_called()
        finally:
            child.communicate("done\n", timeout=5)
            if child.poll() is None:child.kill();child.wait(timeout=5)
    @unittest.skipUnless(os.name == "nt", "Apply targets Windows EDT")
    def test_rollback_apply_readbacks_profile_and_bundles_without_current_package(self):
        edt, context, state, backup, profile = self.installation();write_json(self.build / "install-state.json", state)
        def fake_director(command, **kwargs):
            if "-revert" not in command:return types.SimpleNamespace(stdout="0", returncode=0)
            profile("3", "old");return types.SimpleNamespace(returncode=0)
        with patch.object(installer, "require_package", side_effect=AssertionError("No current package on rollback")), patch.object(installer, "configuration", return_value=self.root / "launcher.jar"), patch.object(installer.subprocess, "run", side_effect=fake_director):
            installer.main(["--build", str(self.build), "--edt-home", str(edt), "--java-home", str(self.root), "--apply", "--rollback"])
        self.assertEqual("rolled_back", json.loads((self.build / "install-state.json").read_text())["state"])
        self.assertEqual(state["before"]["bundles_info_sha256"], sha256(edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"))
    @unittest.skipUnless(os.name == "nt", "Apply targets Windows EDT")
    def test_rollback_refuses_unrelated_post_director_change_before_backup_copy(self):
        edt, context, state, backup, profile = self.installation();write_json(self.build / "install-state.json", state)
        def fake_director(command, **kwargs):
            if "-revert" not in command:return types.SimpleNamespace(stdout="0", returncode=0)
            profile("3", "old", extra=True);return types.SimpleNamespace(returncode=0)
        with patch.object(installer, "configuration", return_value=self.root / "launcher.jar"), patch.object(installer.subprocess, "run", side_effect=fake_director):
            with self.assertRaisesRegex(RuntimeError, "exact previous profile"):
                installer.main(["--build", str(self.build), "--edt-home", str(edt), "--java-home", str(self.root), "--apply", "--rollback"])
        self.assertEqual("rollback_failed", json.loads((self.build / "install-state.json").read_text())["state"])
        self.assertEqual("after", (edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info").read_text())
    def test_prepared_resume_rejects_dispatching_state(self):
        edt, context, state, backup, profile = self.installation()
        receipt = {"version": self.version, "archive_sha256": "a", "verification_run_id": "r"}
        state.update(state="applying", package=installer.package_identity(receipt), before=installer.snapshot(context))
        with self.assertRaises(RuntimeError):installer.require_prepared(state, context, receipt)
    def test_optimized_entrypoints_fail_before_argument_processing(self):
        for filename in ("install-agent-bridge.py", "package-agent-bridge.py"):
            process = subprocess.run([sys.executable, "-O", str(Path(__file__).resolve().parents[1] / filename)], capture_output=True, text=True)
            self.assertNotEqual(0, process.returncode);self.assertIn("Optimized Python", process.stderr)

if __name__ == "__main__":unittest.main()
