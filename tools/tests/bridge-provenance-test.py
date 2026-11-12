"""Regression tests for stale/incomplete verification, with no product or PowerShell execution."""
from pathlib import Path
import json, sys, tempfile, unittest, subprocess
sys.path.insert(0, str(Path(__file__).resolve().parents[1]))
from bridge_provenance import VerificationRun, REQUIRED_STAGES, source_snapshot, artifacts_snapshot, require_verified, write_json

class ProvenanceTests(unittest.TestCase):
    def setUp(self):
        self.temp=tempfile.TemporaryDirectory();self.addCleanup(self.temp.cleanup)
        self.root=Path(self.temp.name);self.build=self.root/".runs/build";self.build.mkdir(parents=True)
        (self.root/"tools").mkdir();self.source=self.root/"tools/test-required.py";self.source.write_text("original",encoding="utf-8")
        self.jar=self.build/"core.jar";self.jar.write_bytes(b"original jar")
        self.meta={"version":"fixture","artifacts":[str(self.jar)]}
        self.meta.update(source_snapshot=source_snapshot(self.root),artifact_sha256=artifacts_snapshot(self.meta))
    def receipt(self):return json.loads((self.build/"verification.json").read_text(encoding="utf-8"))
    def complete(self,run):
        for stage in REQUIRED_STAGES:
            with run.stage(stage):pass
    def passed(self):
        with VerificationRun(self.root,self.build,self.meta) as run:self.complete(run)
    def test_exact_success_is_accepted(self):
        self.passed();self.assertTrue(require_verified(self.root,self.build,self.meta)["passed"])
    def test_manual_legacy_pass_is_rejected(self):
        write_json(self.build/"verification.json",{"passed":True,"tests":1148})
        with self.assertRaises(RuntimeError):require_verified(self.root,self.build,self.meta)
    def test_restart_invalidates_prior_pass_even_if_interrupted(self):
        self.passed();previous=self.receipt()["run_id"]
        run=VerificationRun(self.root,self.build,self.meta);run.__enter__()
        self.assertFalse(self.receipt()["passed"]);self.assertNotEqual(previous,self.receipt()["run_id"])
        with self.assertRaises(RuntimeError):require_verified(self.root,self.build,self.meta)
    def test_failed_preflight_invalidates_prior_pass(self):
        self.passed();self.jar.write_bytes(b"changed")
        with self.assertRaises(RuntimeError):
            with VerificationRun(self.root,self.build,self.meta):pass
        self.assertEqual("failed",self.receipt()["state"]);self.assertFalse(self.receipt()["passed"])
    def test_failed_stage_never_passes(self):
        with self.assertRaisesRegex(ValueError,"failed stage"):
            with VerificationRun(self.root,self.build,self.meta) as run:
                with run.stage("managed_powershell"):raise ValueError("failed stage")
        self.assertEqual("failed",self.receipt()["stages"]["managed_powershell"]["state"]);self.assertFalse(self.receipt()["passed"])
    def test_missing_mandatory_stage_is_failure(self):
        with self.assertRaises(RuntimeError):
            with VerificationRun(self.root,self.build,self.meta) as run:
                with run.stage("java"):pass
        self.assertFalse(self.receipt()["passed"])
    def test_artifact_drift_during_run_is_failure(self):
        with self.assertRaises(RuntimeError):
            with VerificationRun(self.root,self.build,self.meta) as run:
                self.complete(run);self.jar.write_bytes(b"changed")
        self.assertFalse(self.receipt()["passed"])
    def test_source_drift_during_run_is_failure(self):
        with self.assertRaises(RuntimeError):
            with VerificationRun(self.root,self.build,self.meta) as run:
                self.complete(run);self.source.write_text("changed",encoding="utf-8")
        self.assertFalse(self.receipt()["passed"])
    def test_deleted_test_after_pass_is_rejected(self):
        self.passed();self.source.unlink()
        with self.assertRaises(RuntimeError):require_verified(self.root,self.build,self.meta)
    def test_new_test_after_pass_is_rejected(self):
        self.passed();(self.root/"tools/new-test.py").write_text("new",encoding="utf-8")
        with self.assertRaises(RuntimeError):require_verified(self.root,self.build,self.meta)
    def test_changed_jar_after_pass_is_rejected(self):
        self.passed();self.jar.write_bytes(b"different")
        with self.assertRaises(RuntimeError):require_verified(self.root,self.build,self.meta)
    def test_receipt_with_missing_stage_is_rejected(self):
        self.passed();receipt=self.receipt();receipt["stages"].pop("managed_powershell");write_json(self.build/"verification.json",receipt)
        with self.assertRaises(RuntimeError):require_verified(self.root,self.build,self.meta)

    def test_optimized_build_and_verification_refuse_to_run(self):
        for filename in ("build-agent-bridge.py", "test-agent-bridge.py"):
            process=subprocess.run([sys.executable,"-O",str(Path(__file__).resolve().parents[1]/filename)],capture_output=True,text=True)
            self.assertNotEqual(0,process.returncode);self.assertIn("without -O/PYTHONOPTIMIZE",process.stderr)

if __name__=="__main__":unittest.main()
