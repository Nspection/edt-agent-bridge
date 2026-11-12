"""Bind build, verification and packaging to the exact local inputs and artifact bytes."""
from pathlib import Path
from contextlib import contextmanager
from datetime import datetime, timezone
import hashlib, json, os, uuid

SCHEMA = 1
REQUIRED_STAGES = frozenset(("java", "python", "product_boundary", "osgi_linkage", "managed_powershell", "startup_powershell", "provenance"))

def sha256(path):
    h = hashlib.sha256()
    with Path(path).open("rb") as stream:
        for chunk in iter(lambda: stream.read(1024 * 1024), b""):
            h.update(chunk)
    return h.hexdigest()

def source_snapshot(root):
    root = Path(root).resolve()
    folders = [root / "tools"]
    for bundle in ("com.codepilot1c.core", "com.codepilot1c.ui", "com.codepilot1c.core.tests"):
        base = root / "bundles" / bundle
        folders += [base / name for name in ("src", "resources", "scripts", "META-INF", "OSGI-INF", "schema")]
    files = {root / name for name in ("LICENSE", "THIRD_PARTY_NOTICES.md", "agent-bridge/source-includes.txt", "agent-bridge/test-suite.txt")}
    files |= {root / "bundles" / name / "plugin.xml" for name in ("com.codepilot1c.core", "com.codepilot1c.ui")}
    for folder in folders:
        if folder.is_dir():
            files.update(p for p in folder.rglob("*") if p.is_file() and "__pycache__" not in p.parts and p.suffix.lower() not in (".pyc", ".pyo"))
    return {p.relative_to(root).as_posix(): sha256(p) for p in sorted(files) if p.is_file()}

def artifacts_snapshot(meta):
    return {str(Path(p).resolve()): sha256(p) for p in meta["artifacts"]}

def write_json(path, value):
    path = Path(path)
    temporary = path.with_name(path.name + "." + uuid.uuid4().hex + ".tmp")
    try:
        temporary.write_text(json.dumps(value, indent=2, sort_keys=True) + "\n", encoding="utf-8")
        os.replace(temporary, path)
    finally:
        temporary.unlink(missing_ok=True)

def require_build(root, meta):
    if meta.get("source_snapshot") != source_snapshot(root):
        raise RuntimeError("Build inputs changed or have no provenance; rebuild before verification")
    if meta.get("artifact_sha256") != artifacts_snapshot(meta):
        raise RuntimeError("Build artifact bytes changed; rebuild before verification")

class VerificationRun:
    def __init__(self, root, build, meta):
        self.root, self.build, self.meta = Path(root), Path(build), meta
        self.receipt = {"schema": SCHEMA, "run_id": uuid.uuid4().hex, "version": meta["version"], "passed": False,
                        "state": "running", "started_at": datetime.now(timezone.utc).isoformat(), "stages": {}}
    def __enter__(self):
        # Invalidate an earlier pass even when preflight fails or the process is killed.
        write_json(self.build / "verification.json", self.receipt)
        try:
            require_build(self.root, self.meta)
            self.receipt.update(source_snapshot=self.meta["source_snapshot"], artifact_sha256=self.meta["artifact_sha256"])
            write_json(self.build / "verification.json", self.receipt)
            return self
        except BaseException as error:
            self.__exit__(type(error), error, error.__traceback__)
            raise
    @contextmanager
    def stage(self, name):
        if name not in REQUIRED_STAGES or name in self.receipt["stages"]:
            raise RuntimeError("Invalid or repeated verification stage: " + name)
        stages = self.receipt["stages"]
        stages[name] = {"state": "running"}
        write_json(self.build / "verification.json", self.receipt)
        try:
            yield
        except BaseException as error:
            stages[name] = {"state": "failed", "error_type": type(error).__name__}
            raise
        else:
            stages[name] = {"state": "passed", "exit_code": 0}
        finally:
            write_json(self.build / "verification.json", self.receipt)
    def __exit__(self, kind, error, traceback):
        if kind is not None:
            self.receipt.update(state="failed", passed=False, error_type=kind.__name__)
            write_json(self.build / "verification.json", self.receipt)
            return False
        try:
            if set(self.receipt["stages"]) != REQUIRED_STAGES or any(s.get("state") != "passed" for s in self.receipt["stages"].values()):
                raise RuntimeError("Mandatory verification stages are incomplete")
            require_build(self.root, self.meta)
        except BaseException as failure:
            self.receipt.update(state="failed", passed=False, error_type=type(failure).__name__)
            write_json(self.build / "verification.json", self.receipt)
            raise
        self.receipt.update(state="passed", passed=True, finished_at=datetime.now(timezone.utc).isoformat())
        write_json(self.build / "verification.json", self.receipt)
        return False

def require_verified(root, build, meta):
    require_build(root, meta)
    receipt = json.loads((Path(build) / "verification.json").read_text(encoding="utf-8"))
    if (receipt.get("schema") != SCHEMA or receipt.get("passed") is not True or receipt.get("state") != "passed"
        or receipt.get("version") != meta["version"] or receipt.get("source_snapshot") != meta["source_snapshot"]
        or receipt.get("artifact_sha256") != meta["artifact_sha256"]
        or set(receipt.get("stages", {})) != REQUIRED_STAGES
        or any(s != {"state": "passed", "exit_code": 0} for s in receipt["stages"].values())):
        raise RuntimeError("Complete verification of these exact sources and artifacts is required")
    return receipt
