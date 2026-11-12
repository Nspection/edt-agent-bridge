"""Verify/install an exact verified package. Refuse rollback after any target drift."""
from pathlib import Path
from contextlib import contextmanager
import argparse, gzip, json, os, shutil, subprocess, urllib.parse, urllib.request, uuid
import xml.etree.ElementTree as ET
from bridge_package import require_package, regular_tree
from bridge_provenance import sha256, write_json

FEATURE = "com.codepilot1c.feature.feature.group"

def properties(file):
    result = {}
    raw = Path(file).read_text(encoding="utf-8-sig").replace("\\\n", "")
    for line in raw.splitlines():
        if "=" in line and not line.startswith(("#", "!")):
            key, value = line.split("=", 1)
            result[key] = value.replace("\\:", ":").replace("\\ ", " ").replace("\\\\", "\\")
    return result

def file_path(value):
    return Path(urllib.request.url2pathname(urllib.parse.urlsplit(value).path)) if value.startswith("file:") else Path(value)

def target(edt):
    edt = Path(edt).resolve()
    props = properties(edt / "configuration/config.ini")
    profile, area = props.get("eclipse.p2.profile"), props.get("eclipse.p2.data.area", "")
    if not profile or not area.startswith("file:") or Path(profile).name != profile:
        raise RuntimeError("Cannot determine exact existing p2 profile")
    shared = file_path(area).resolve()
    registry = shared / "org.eclipse.equinox.p2.engine/profileRegistry" / (profile + ".profile")
    profiles = sorted(registry.glob("*.profile.gz"), key=lambda p: int(p.name.split(".")[0]))
    if not profiles:
        raise RuntimeError("Existing p2 profile is not readable")
    current = profiles[-1]
    with gzip.open(current) as stream:
        xml = ET.parse(stream).getroot()
    old = [x.get("version") for x in xml.findall("./units/unit") if x.get("id") == FEATURE]
    if len(old) > 1:
        raise RuntimeError("Multiple Bridge features are installed")
    return {"edt_home": str(edt), "profile": profile, "shared": str(shared),
            "bundlepool": str(file_path(props.get("eclipse.p2.cache", str(shared / "pool"))).resolve()),
            "registry": registry, "profile_file": current, "previous_feature": old[0] if old else None}

def snapshot(context):
    edt = Path(context["edt_home"])
    return {"profile_timestamp": context["profile_file"].name.split(".")[0],
            "profile_sha256": sha256(context["profile_file"]),
            "bundles_info_sha256": sha256(edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"),
            "configuration_sha256": sha256(edt / "configuration/config.ini")}

def identity(context):
    return {key: context[key] for key in ("edt_home", "profile", "shared", "bundlepool")}

def package_identity(receipt):
    return {key: receipt[key] for key in ("version", "archive_sha256", "verification_run_id")}

def require_prepared(state, context, package):
    if state.get("state") != "prepared" or state.get("target") != identity(context) or state.get("package") != package_identity(package) or state.get("before") != snapshot(context):
        raise RuntimeError("Prepared installation target/package changed; no retry performed")

def require_rollback(state, context, backup):
    if state.get("state") != "installed" or state.get("target") != identity(context) or state.get("after") != snapshot(context):
        raise RuntimeError("Installation changed after Bridge apply; full-profile rollback refused")
    require_backups(state, context, backup)

def require_backups(state, context, backup):
    backup = Path(backup)
    before = state["before"]
    # Check every rollback input BEFORE invoking the director, not after reverting.
    if (sha256(backup / "bundles.info") != before["bundles_info_sha256"]
        or sha256(backup / "previous.profile.gz") != before["profile_sha256"]
        or sha256(context["registry"] / (before["profile_timestamp"] + ".profile.gz")) != before["profile_sha256"]):
        raise RuntimeError("Rollback backup/history changed; no revert performed")

def equivalent_profile(previous, current):
    def canonical(path):
        with gzip.open(path) as stream:
            tree = ET.parse(stream).getroot()
        tree.attrib.pop("timestamp", None)
        for prop in tree.findall("./properties/property"):
            if prop.get("name") == "org.eclipse.equinox.p2.roaming":
                prop.set("value", "P2_METADATA")
        return ET.tostring(tree)
    return canonical(previous) == canonical(current)

def unrelated_units(path):
    with gzip.open(path) as stream:
        tree = ET.parse(stream).getroot()
    return sorted(ET.tostring(unit) for unit in tree.findall("./units/unit") if not unit.get("id", "").startswith("com.codepilot1c."))

def configuration(edt, directory):
    # A fresh director configuration from the target; never reuse mutable publisher caches.
    entries, lines = {}, []
    for line in (edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info").read_text(encoding="utf-8-sig").splitlines():
        fields = line.split(",")
        if line.startswith("#") or len(fields) < 3:
            continue
        location = file_path(fields[2]) if fields[2].startswith("file:") else edt / fields[2]
        location = location.resolve()
        if not location.exists():
            raise RuntimeError("Missing installed target bundle: " + fields[0])
        entries[fields[0]] = location
        fields[2] = location.as_uri()
        if not fields[0].startswith(("com._1c.", "com.e1c.", "com.codepilot1c.", "tormozit.", "com.company1c.")):
            lines.append(",".join(fields))
    simple = directory / "org.eclipse.equinox.simpleconfigurator"
    simple.mkdir(parents=True, exist_ok=False)
    (simple / "bundles.info").write_text("\n".join(lines) + "\n", encoding="utf-8")
    settings = {"osgi.bundles": "reference:" + entries["org.eclipse.equinox.simpleconfigurator"].as_uri() + "@1:start",
                "osgi.framework": entries["org.eclipse.osgi"].as_uri(), "osgi.bundles.defaultStartLevel": "4",
                "osgi.configuration.cascaded": "false", "eclipse.application": "org.eclipse.equinox.p2.director"}
    escape = lambda value: value.replace("\\", "\\\\").replace(":", "\\:")
    (directory / "config.ini").write_text("\n".join(key + "=" + escape(value) for key, value in settings.items()) + "\n", encoding="utf-8")
    return entries["org.eclipse.equinox.launcher"]

@contextmanager
def file_lock(file):
    # OS locks are released if the installer dies; no stale lock-file deletion or PID guessing.
    stream = Path(file).open("a+b")
    acquired = False
    try:
        stream.seek(0, os.SEEK_END)
        if stream.tell() == 0:
            stream.write(b"0");stream.flush()
        stream.seek(0)
        try:
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_NBLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_EX | fcntl.LOCK_NB)
            acquired = True
        except OSError as busy:
            raise RuntimeError("Another installer owns this build; no director dispatched") from busy
        yield
    finally:
        if acquired:
            stream.seek(0)
            if os.name == "nt":
                import msvcrt
                msvcrt.locking(stream.fileno(), msvcrt.LK_UNLCK, 1)
            else:
                import fcntl
                fcntl.flock(stream.fileno(), fcntl.LOCK_UN)
        stream.close()

def install_lock(build):
    return file_lock(Path(build) / ".install.lock")

def target_lock(context):
    # Shared across build qualifiers and EDT paths that use the same native profile.
    return file_lock(context["registry"] / ".edt-agent-bridge.install.lock")

def main(argv=None):
    if not __debug__:
        raise RuntimeError("Optimized Python is not supported for release tools")
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--build", required=True)
    parser.add_argument("--edt-home", default=os.environ.get("CODEPILOT_EDT_HOME"))
    parser.add_argument("--java-home", default=os.environ.get("JAVA_HOME"))
    parser.add_argument("--apply", action="store_true")
    parser.add_argument("--rollback", action="store_true")
    parser.add_argument("--resume", action="store_true")
    args = parser.parse_args(argv)
    if not args.edt_home or not args.java_home:
        parser.error("Set CODEPILOT_EDT_HOME and JAVA_HOME")
    if args.resume and (not args.apply or args.rollback):
        parser.error("--resume is only for a prepared install apply")
    if args.apply:
        with install_lock(Path(args.build).resolve()):
            locked = target(Path(args.edt_home).resolve())
            with target_lock(locked):
                execute(args, identity(locked))
    else:
        execute(args)

def execute(args, locked_target=None):
    root, build, edt, java = Path(__file__).resolve().parent.parent, Path(args.build).resolve(), Path(args.edt_home).resolve(), Path(args.java_home).resolve()
    meta = package = site = None
    if not args.rollback:
        meta = json.loads((build / "build.json").read_text(encoding="utf-8"))
        package, site, _ = require_package(root, build, meta)
    context = target(edt)
    if locked_target is not None and identity(context) != locked_target:
        raise RuntimeError("Installation identity changed before acquiring its lock")
    initial = snapshot(context)
    state_file, backup = build / "install-state.json", build / "install-backup"
    state = None
    if args.rollback:
        state = json.loads(state_file.read_text(encoding="utf-8"))
        require_rollback(state, context, backup)
    if args.apply:
        if os.name != "nt":
            raise RuntimeError("This installer applies only to Windows EDT")
        running = subprocess.run(["powershell.exe", "-NoProfile", "-NonInteractive", "-Command", "@(Get-Process -Name 1cedt,1cedtstart -ErrorAction SilentlyContinue).Count"], capture_output=True, text=True, check=True)
        if int(running.stdout.strip() or "0"):
            raise RuntimeError("Save work and close EDT and EDT Start before applying")
        if not args.rollback:
            if state_file.exists():
                if not args.resume:
                    raise RuntimeError("Installation record exists; inspect before --resume")
                state = json.loads(state_file.read_text(encoding="utf-8"))
                require_prepared(state, context, package)
            else:
                backup.mkdir(exist_ok=False)
                shutil.copyfile(edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info", backup / "bundles.info")
                shutil.copyfile(context["profile_file"], backup / "previous.profile.gz")
                state = {"state": "prepared", "target": identity(context), "package": package_identity(package), "before": initial}
                write_json(state_file, state)
    attempt = build / "install-attempts" / uuid.uuid4().hex
    attempt.mkdir(parents=True, exist_ok=False)
    repository = None
    if not args.rollback:
        repository = attempt / "repository"
        shutil.copytree(site, repository)
        if repository is not None and regular_tree(repository) != package["files_sha256"]:
            raise RuntimeError("Installation repository differs from verified package")
    launcher = configuration(edt, attempt / "configuration")
    cmd = [str(java / "bin/java.exe"), "-jar", str(launcher), "-install", str(edt), "-configuration", str(attempt / "configuration"), "-data", str(attempt / "workspace"), "-nosplash", "-application", "org.eclipse.equinox.p2.director", "-destination", str(edt), "-shared", context["shared"], "-bundlepool", context["bundlepool"], "-profile", context["profile"]]
    if repository is not None:
        cmd += ["-repository", repository.as_uri()]
    if args.rollback:
        cmd += ["-revert", state["before"]["profile_timestamp"]]
    else:
        if context["previous_feature"]:
            cmd += ["-uninstallIU", FEATURE + "/" + context["previous_feature"]]
        cmd += ["-installIU", FEATURE + "/" + meta["version"], "-roaming"]
    if not args.apply:
        cmd += ["-verifyOnly"]
    # Recheck after all preparation, immediately before the irreversible director boundary.
    if not args.rollback:
        require_package(root, build, meta)
    current = target(edt)
    if snapshot(current) != initial or identity(current) != identity(context):
        raise RuntimeError("Installation changed while preparing director")
    if args.rollback:
        require_rollback(state, current, backup)
    if args.apply:
        require_backups(state, current, backup)
        state["state"] = "rolling_back" if args.rollback else "applying"
        write_json(state_file, state)
    log_path = attempt / "director.log"
    try:
        with log_path.open("wb") as log:
            result = subprocess.run(cmd, stdout=log, stderr=subprocess.STDOUT, timeout=900)
        if result.returncode:
            raise RuntimeError("p2 director failed; inspect " + str(log_path))
        if repository is not None and regular_tree(repository) != package["files_sha256"]:
            raise RuntimeError("Director repository changed during execution")
        if args.apply:
            current = target(edt)
            if identity(current) != identity(context):
                raise RuntimeError("Director changed installation/profile identity")
            if args.rollback:
                if not equivalent_profile(backup / "previous.profile.gz", current["profile_file"]):
                    raise RuntimeError("p2 rollback did not restore the exact previous profile")
                if sha256(backup / "bundles.info") != state["before"]["bundles_info_sha256"]:
                    raise RuntimeError("Rollback backup changed during director execution")
                shutil.copyfile(backup / "bundles.info", edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info")
                if sha256(edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info") != state["before"]["bundles_info_sha256"]:
                    raise RuntimeError("Rollback bundles.info readback differs from backup")
                final_context = target(edt)
                if identity(final_context) != identity(context) or not equivalent_profile(backup / "previous.profile.gz", final_context["profile_file"]):
                    raise RuntimeError("Rollback target changed during restoration")
                state["state"] = "rolled_back"
            else:
                if unrelated_units(backup / "previous.profile.gz") != unrelated_units(current["profile_file"]):
                    raise RuntimeError("Unrelated profile units changed during Bridge apply")
                info = (edt / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info").read_text(encoding="utf-8-sig")
                for bundle in ("com.codepilot1c.core", "com.codepilot1c.ui"):
                    if bundle + "," + meta["version"] + "," not in info:
                        raise RuntimeError("Installed bundle version is unexpected")
                if current["previous_feature"] != meta["version"]:
                    raise RuntimeError("Installed feature version is unexpected")
                state["state"], state["after"] = "installed", snapshot(current)
            write_json(state_file, state)
    except BaseException:
        if args.apply:
            state["state"] = "rollback_failed" if args.rollback else "apply_failed"
            write_json(state_file, state)
        raise
    print(json.dumps({"action": "rollback" if args.rollback else "install", "applied": args.apply, "verified": True, "version": state["package"]["version"] if args.rollback else meta["version"], "log": str(log_path)}))

if __name__ == "__main__":
    main()
