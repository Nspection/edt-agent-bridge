"""Verified immutable package generations; no publication or installer side effects."""
from pathlib import Path
import json, re, zipfile
from bridge_provenance import require_verified, sha256, write_json

SCHEMA = 1

def regular_tree(directory):
    directory = Path(directory)
    if directory.is_symlink() or directory.resolve() != directory.absolute():
        raise RuntimeError("Linked package generation")
    files = {}
    for path in directory.rglob("*"):
        if path.is_symlink() or path.resolve() != path.absolute():
            raise RuntimeError("Linked package member")
        if path.is_file():
            files[path.relative_to(directory).as_posix()] = sha256(path)
    return files

def allowed_site(version, artifact_hashes, feature_hash):
    expected = {"plugins/" + Path(p).name: digest for p, digest in artifact_hashes.items()}
    wanted = {"com.codepilot1c.core_" + version + ".jar", "com.codepilot1c.ui_" + version + ".jar"}
    if {Path(p).name for p in artifact_hashes} != wanted or len(artifact_hashes) != 2:
        raise RuntimeError("Exactly the tested core and UI artifacts are required")
    expected["features/com.codepilot1c.feature_" + version + ".jar"] = feature_hash
    return expected

def verify_metadata(site, version):
    import xml.etree.ElementTree as ET
    roots = {}
    for archive, entry in (("content.jar", "content.xml"), ("artifacts.jar", "artifacts.xml")):
        with zipfile.ZipFile(site / archive) as jar:
            if jar.namelist() != [entry]:
                raise RuntimeError("Unexpected p2 metadata archive members")
            roots[archive] = ET.fromstring(jar.read(entry))
        if roots[archive].find("references") is not None:
            raise RuntimeError("External p2 repository references are not allowed")
    expected_units = {"com.codepilot1c.core", "com.codepilot1c.ui", "com.codepilot1c.feature.feature.jar", "com.codepilot1c.feature.feature.group"}
    seen = set()
    for unit in roots["content.jar"].findall("./units/unit"):
        name = unit.get("id")
        category = name and name.startswith("category.edt-agent-bridge") and any(p.get("name") == "org.eclipse.equinox.p2.type.category" and p.get("value") == "true" for p in unit.findall("./properties/property"))
        if name in seen or (name not in expected_units and not category) or (not category and unit.get("version") != version) or (category and unit.find("artifacts") is not None):
            raise RuntimeError("Unexpected p2 installable unit")
        seen.add(name)
    if not expected_units.issubset(seen):
        raise RuntimeError("Missing tested p2 installable units")
    expected = {("osgi.bundle", "com.codepilot1c.core", version), ("osgi.bundle", "com.codepilot1c.ui", version), ("org.eclipse.update.feature", "com.codepilot1c.feature", version)}
    artifacts = [(a.get("classifier"), a.get("id"), a.get("version")) for a in roots["artifacts.jar"].findall("./artifacts/artifact")]
    if len(artifacts) != len(expected) or set(artifacts) != expected:
        raise RuntimeError("Unexpected p2 artifact descriptors")
    allowed_outputs = {"${repoUrl}/plugins/${id}_${version}.jar", "${repoUrl}/plugins/${id}_${version}.jar.pack.gz", "${repoUrl}/features/${id}_${version}.jar", "${repoUrl}/binary/${id}_${version}"}
    if any(rule.get("output") not in allowed_outputs for rule in roots["artifacts.jar"].findall("./mappings/rule")):
        raise RuntimeError("Unexpected p2 artifact mapping")

def verify_site(site, version, artifact_hashes, feature_hash):
    files = regular_tree(site)
    expected = allowed_site(version, artifact_hashes, feature_hash)
    metadata = {"content.jar", "artifacts.jar"}
    if set(files) != set(expected) | metadata:
        raise RuntimeError("Unexpected or missing p2 site members")
    for name, digest in expected.items():
        if files.get(name) != digest:
            raise RuntimeError("Untested p2 artifact: " + name)
    verify_metadata(Path(site), version)
    return files

def verify_archive(archive, site_files):
    with zipfile.ZipFile(archive) as package:
        names = package.namelist()
        if len(names) != len(set(names)) or set(names) != set(site_files):
            raise RuntimeError("Package archive members differ from the verified site")
        import hashlib
        for name, digest in site_files.items():
            if hashlib.sha256(package.read(name)).hexdigest() != digest:
                raise RuntimeError("Package archive bytes differ from the verified site")

def commit_package(root, build, meta, generation, feature_hash):
    build, generation = Path(build).resolve(), Path(generation).resolve()
    verification = require_verified(root, build, meta)
    relative = generation.relative_to(build).as_posix()
    if not re.fullmatch(r"packages/package-[a-f0-9]{32}", relative):
        raise RuntimeError("Invalid package generation")
    site = generation / "update-site"
    archive = generation / ("edt-agent-bridge-" + meta["version"] + "-update-site.zip")
    files = verify_site(site, meta["version"], meta["artifact_sha256"], feature_hash)
    verify_archive(archive, files)
    receipt = {"schema": SCHEMA, "passed": True, "version": meta["version"],
               "verification_run_id": verification["run_id"], "artifact_sha256": meta["artifact_sha256"],
               "generation": relative, "feature_sha256": feature_hash, "files_sha256": files,
               "archive_sha256": sha256(archive)}
    # The sole publication point. Failed attempts never replace the last verified generation.
    write_json(build / "package-verification.json", receipt)
    return receipt

def require_package(root, build, meta):
    build = Path(build).resolve()
    verified = require_verified(root, build, meta)
    receipt = json.loads((build / "package-verification.json").read_text(encoding="utf-8"))
    if (receipt.get("schema") != SCHEMA or receipt.get("passed") is not True
        or receipt.get("version") != meta["version"]
        or receipt.get("verification_run_id") != verified["run_id"]
        or receipt.get("artifact_sha256") != meta["artifact_sha256"]
        or not re.fullmatch(r"packages/package-[a-f0-9]{32}", receipt.get("generation", ""))):
        raise RuntimeError("Package is not bound to this complete verification")
    generation = build / receipt["generation"]
    if generation.is_symlink() or generation.resolve() != generation.absolute():
        raise RuntimeError("Linked package generation")
    site = generation / "update-site"
    archive = generation / ("edt-agent-bridge-" + meta["version"] + "-update-site.zip")
    if archive.is_symlink() or not archive.is_file():
        raise RuntimeError("Invalid package archive")
    files = verify_site(site, meta["version"], meta["artifact_sha256"], receipt["feature_sha256"])
    if files != receipt.get("files_sha256") or sha256(archive) != receipt.get("archive_sha256"):
        raise RuntimeError("Verified package changed")
    verify_archive(archive, files)
    return receipt, site, archive
