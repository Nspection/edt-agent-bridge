"""Verify a prebuilt Pages tree; never build or change its release artifacts."""
import hashlib
import json
import os
from pathlib import Path
import re
import subprocess
import sys


def verify(root, pages_commit, release_commit, version):
    root = Path(root).resolve(strict=True)
    for value in (pages_commit, release_commit):
        if not re.fullmatch(r"[a-f0-9]{40}", value):
            raise ValueError("An exact 40-character commit is required")
    if not re.fullmatch(r"1[.]4[.]0[.][A-Za-z0-9_-]+", version):
        raise ValueError("Invalid Bridge version")
    actual = subprocess.check_output(["git", "-C", str(root), "rev-parse", "HEAD"], text=True).strip()
    if actual != pages_commit:
        raise ValueError("gh-pages changed; inspect the new snapshot before publishing")
    changed = subprocess.run(["git", "-C", str(root), "diff", "--quiet", "HEAD", "--"], check=False)
    untracked = subprocess.check_output(["git", "-C", str(root), "ls-files", "--others", "--exclude-standard"], text=True)
    if changed.returncode != 0 or untracked.strip():
        raise ValueError("Pages snapshot must be clean")
    manifest = json.loads((root / "release-manifest.json").read_text(encoding="utf-8"))
    if manifest.get("version") != version or manifest.get("source_commit") != release_commit:
        raise ValueError("Release identity differs from the requested snapshot")
    hashes = manifest.get("files_sha256", {})
    required = {"content.jar", "artifacts.jar", "index.html", "downloads/agent-host-package.json"}
    if not isinstance(hashes, dict) or not required.issubset(hashes):
        raise ValueError("Incomplete release inventory")
    for name, expected in hashes.items():
        relative = Path(name)
        if relative.is_absolute() or ".." in relative.parts or not re.fullmatch(r"[a-f0-9]{64}", str(expected)):
            raise ValueError("Invalid manifest entry")
        target = root / relative
        if any(parent.is_symlink() for parent in (target, *target.parents) if parent != root.parent):
            raise ValueError("Links are not publishable")
        if not target.resolve(strict=True).is_relative_to(root) or not target.is_file():
            raise ValueError("Manifest path escaped the snapshot")
        digest = hashlib.sha256()
        with target.open("rb") as stream:
            for chunk in iter(lambda: stream.read(65536), b""):
                digest.update(chunk)
        if digest.hexdigest() != expected:
            raise ValueError("Published bytes changed: " + name)
    print("Verified Pages snapshot", pages_commit, version, len(hashes), "files")


if __name__ == "__main__":
    verify(sys.argv[1], os.environ["EXPECT_PAGES_COMMIT"], os.environ["EXPECT_RELEASE_COMMIT"], os.environ["EXPECT_VERSION"])
