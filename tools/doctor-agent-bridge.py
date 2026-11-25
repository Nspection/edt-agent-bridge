#!/usr/bin/env python3
"""Read-only diagnostics for a local EDT Agent Bridge installation."""
import argparse
import http.client
import json
import os
from pathlib import Path
import re


def installed(home):
    table = home / "configuration/org.eclipse.equinox.simpleconfigurator/bundles.info"
    try:
        with table.open(encoding="utf-8-sig") as stream:
            return {
                fields[0]: fields[1]
                for line in stream
                if len(fields := line.rstrip().split(",", 2)) == 3
                and fields[0] in {"com.codepilot1c.core", "com.codepilot1c.ui"}
            }
    except (OSError, UnicodeError):
        return {}


def probe(port):
    conn = http.client.HTTPConnection("127.0.0.1", port, timeout=1)
    try:
        conn.request("GET", "/health/ready")
        response = conn.getresponse()
        if response.status not in (200, 503):
            return "unexpected_http"
        payload = json.loads(response.read(16385))
        if (
            not isinstance(payload, dict)
            or payload.get("services") not in ("ready", "starting", "degraded")
            or not isinstance(payload.get("projects"), list)
        ):
            return "unexpected_http"
        if response.status == 200 and payload.get("ready") is True and payload.get("status") == "ready":
            return "ready"
        if response.status == 503 and payload.get("ready") is False and payload.get("status") == "not_ready":
            return "degraded" if payload["services"] == "degraded" else "starting"
        return "unexpected_http"
    except ConnectionRefusedError:
        return "not_listening"
    except (http.client.HTTPException, OSError, ValueError):
        return "unverified"
    finally:
        conn.close()


def registrations(directory):
    try:
        paths = directory.iterdir()
        for count, path in enumerate(paths):
            if count >= 100:
                break
            if not re.fullmatch(r"[0-9a-fA-F-]{36}\.json", path.name) or path.is_symlink():
                continue
            try:
                with path.open("rb") as stream:
                    record = json.loads(stream.read(16385))
                port = record.get("port")
                if record.get("schemaVersion") == 1 and type(port) is int and 1 <= port <= 65535:
                    yield port
            except (OSError, ValueError, AttributeError):
                continue
    except OSError:
        return


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--edt-home", type=Path, action="append", default=[])
    parser.add_argument("--registry", type=Path, default=Path.home() / ".codepilot1c/instances")
    parser.add_argument("--port", type=int, action="append", default=[])
    args = parser.parse_args()
    if any(not 1 <= port <= 65535 for port in args.port):
        parser.error("--port must be between 1 and 65535")
    homes = args.edt_home
    if not homes and os.environ.get("LOCALAPPDATA"):
        root = Path(os.environ["LOCALAPPDATA"]) / "1C/1cedtstart/installations"
        homes = list(root.glob("*/1cedt"))[:30]
    ports = sorted(set(args.port or registrations(args.registry)))[:12]
    observations = [{"port": port, "status": probe(port)} for port in ports]
    installs = [{"edt_home": str(home), "registered_bridge_bundles": installed(home)} for home in homes]
    if any(row["status"] == "ready" for row in observations):
        state = "mcp_ready"
    elif any(row["status"] == "degraded" for row in observations):
        state = "mcp_degraded"
    elif any(row["status"] == "starting" for row in observations):
        state = "mcp_starting"
    elif observations:
        state = "mcp_not_verified"
    elif any(row["registered_bridge_bundles"] for row in installs):
        state = "bridge_registered_no_endpoint"
    else:
        state = "bridge_not_found"
    print(json.dumps({"state": state, "installations": installs, "endpoints": observations},
                     ensure_ascii=True, indent=2))


if __name__ == "__main__":
    main()
