# SPDX-License-Identifier: AGPL-3.0-only
"""Private stdio worker, no server/manager and no arbitrary BSL or wire commands."""
import ctypes
from datetime import datetime, timezone
from ctypes import wintypes
import hashlib
import json
from pathlib import Path
import re
import sys
import time
import uuid

sys.dont_write_bytecode = True
sys.path.insert(0, str(Path(__file__).parent / "vendor"))
MAX_LINE = 128 * 1024
MAX_WIRE = 4 * 1024 * 1024
CHILD = b"\xe1" + b"\x81" * 7
COLLECTION = b"\xe0\x4b\x55"

class Rejected(ValueError):
    pass


def port_owner(port):
    """TCP_TABLE_OWNER_PID_LISTENER, IPv4 loopback only; never accept another PID."""
    size = wintypes.ULONG(0)
    api = ctypes.WinDLL("iphlpapi").GetExtendedTcpTable
    api(None, ctypes.byref(size), False, 2, 3, 0)
    if size.value > 8 * 1024 * 1024:
        raise Rejected("TCP ownership table exceeds limit")
    buffer = ctypes.create_string_buffer(size.value)
    if api(buffer, ctypes.byref(size), False, 2, 3, 0):
        raise Rejected("TCP ownership unavailable")
    data = buffer.raw
    import struct
    count = struct.unpack_from("<I", data)[0]
    matches = set()
    for i in range(count):
        state, address, local_port, remote, remote_port, pid = struct.unpack_from("<6I", data, 4 + i * 24)
        actual_port = int.from_bytes(local_port.to_bytes(4, "little")[:2], "big")
        if state == 2 and actual_port == port:
            if address not in (0, int.from_bytes(b"\x7f\x00\x00\x01", "little")):
                raise Rejected("TestClient listener is unavailable on loopback")
            matches.add(pid)
    if len(matches) != 1:
        raise Rejected("TestClient listener ownership is unavailable or ambiguous")
    return next(iter(matches))


class BoundedSocket:
    def __init__(self, socket):
        self.socket = socket
        self.received = 0
    def recv(self, size):
        data = self.socket.recv(min(size, 65536))
        self.received += len(data)
        if self.received > MAX_WIRE:
            self.socket.close()
            raise OSError("TestClient response exceeds limit")
        return data
    def __getattr__(self, name):
        return getattr(self.socket, name)


class Worker:
    def __init__(self):
        self.client = None
        self.port = None
        self.pid = None
        self.snapshots = {}
        self.dispatched = False
        self.broken = False

    def identity(self):
        if self.broken or self.client is None:
            raise Rejected("Client disconnected; do not replay an unknown action")
        if port_owner(self.port) != self.pid:
            self.broken = True
            self.client.close()
            raise Rejected("Listening process changed")
        self.client.s.received = 0

    def connect(self, request):
        if self.client is not None:
            raise Rejected("Worker already connected")
        port, pid = request["port"], request["pid"]
        if type(port) is not int or not 1024 <= port <= 65535 or type(pid) is not int or pid <= 0:
            raise Rejected("Invalid owned client")
        if port_owner(port) != pid:
            raise Rejected("Port belongs to another process")
        import tc1c
        import guids
        self.wire, self.guids = tc1c, guids
        self.port, self.pid = port, pid
        client = tc1c.TestClient("127.0.0.1", port)
        client.platform_version = request["platform_version"]
        client.CONNECT_TIMEOUT = 5
        client.RECV_TIMEOUT = 10
        client._io_deadline = time.monotonic() + 20
        original = tc1c.socket.create_connection
        tc1c.socket.create_connection = lambda *a, **kw: BoundedSocket(original(*a, **kw))
        try:
            client.connect(retries=1)
            client.attach()
            client._io_deadline = None
        except Exception:
            client.close()
            raise
        finally:
            tc1c.socket.create_connection = original
        self.client = client
        self.identity()
        return {"success": True, "transport": "testclient", "platform_version": client.platform_version}

    def send(self, guid, key=None, kind="read", middle=b"", handle=None):
        if kind != "read":
            self.dispatched = True
        result = self.client.send_cmd(guid, key, kind=kind, middle=middle, handle=handle)
        if not result.get("ok"):
            raise RuntimeError("TestClient did not confirm operation")
        return result["raw"]

    def collection(self, guid, parent=None, middle=CHILD):
        return self.wire.decode_collection(self.send(guid, parent, middle=middle), parent)

    def form(self):
        windows = self.collection(self.guids.GET_ACTIVE_WINDOW, middle=COLLECTION)
        if len(windows) > 1:
            raise Rejected("Ambiguous active TestClient window")
        window = windows[0] if windows else {"key": None}
        forms = ([item for item in self.collection(self.guids.GET_CHILD_OBJECTS, window["key"])
                 if item["class"] == "ManagedForm"] if windows else [])
        if len(forms) > 1:
            raise Rejected("Ambiguous active managed form")
        form = forms[0] if forms else None
        public = {"id": hashlib.sha256((window["key"] or "application").encode()).hexdigest()[:20],
                  "title": (form or {}).get("title") or window.get("title") or "",
                  "form_name": (form or {}).get("form_name"), "url": window.get("url")}
        return window, form, public

    def elements(self, form):
        if not form:
            return []
        pending = [(form["key"], 0)]
        found, visited = [], set()
        while pending:
            parent, depth = pending.pop(0)
            if parent in visited:
                raise Rejected("Cyclic UI tree")
            visited.add(parent)
            if len(visited) > 100 or depth > 10:
                raise Rejected("UI tree exceeds stage1 limits")
            for item in self.collection(self.guids.GET_CHILD_OBJECTS, parent):
                if len(found) >= 200:
                    raise Rejected("UI tree exceeds 200 elements; use a simpler form")
                item["parent"] = parent
                found.append(item)
                if item["class"] in {"Group", "ManagedForm"}:
                    pending.append((item["key"], depth + 1))
        return found

    @staticmethod
    def one(elements, name, expected=None):
        matches = [item for item in elements if item.get("name") == name]
        if len(matches) != 1 or (expected and matches[0]["class"] not in expected):
            raise Rejected("Element is absent, ambiguous or unsupported: " + name)
        return matches[0]

    def value(self, item):
        raw = self.send(self.guids.GET_PROPERTY, item["key"], middle=COLLECTION, handle=item["handle"])
        text = self.wire.decode_field_text(raw, property_value=True)
        # The empty scalar has a known, typed InputField envelope; unknown is never empty.
        if text is None and item.get("type") == "InputField":
            end = self.wire.result_end(raw)
            if end >= 7 and raw[:end].endswith(b"\x81\x81\x81\xe0\x4b\x53\x81\x20"):
                text = ""
        if text is None or len(text) > 4096:
            raise Rejected("Field value unavailable or exceeds limit: " + item.get("name", ""))
        return text

    def capture(self, request):
        window, form, public = self.form()
        elements = self.elements(form)
        names = request.get("fields", [])
        if not isinstance(names, list) or len(names) > 16 or any(not isinstance(n, str) or len(n) > 128 for n in names):
            raise Rejected("fields must contain up to 16 exact element names")
        view = request.get("view", "elements")
        if view not in {"elements", "values", "windows"}:
            raise Rejected("Direct stage1 supports elements, values and windows")
        if view == "values" and not names:
            raise Rejected("Explicit fields are required for values")
        values = {name: self.value(self.one(elements, name, {"EditField"})) for name in names}
        # Recheck active form after traversal, not just the caption.
        again_window, again_form, again_public = self.form()
        signature = (window["key"], (form or {}).get("handle"), public["title"])
        if signature != (again_window["key"], (again_form or {}).get("handle"), again_public["title"]):
            raise Rejected("Active form changed during observation")
        identifier = "tc-form-" + uuid.uuid4().hex
        self.snapshots[identifier] = {"signature": signature, "elements": elements, "window": public}
        while len(self.snapshots) > 16:
            del self.snapshots[next(iter(self.snapshots))]
        result = {"success": True, "transport": "testclient", "snapshot_id": identifier,
                  "window": public, "values": values, "scope": "ordinary_controls", "complete": True,
                  "captured_at": datetime.now(timezone.utc).isoformat(),
                  "live_state": True}
        if view == "elements":
            result["elements"] = [{k: item.get(k) for k in ("name", "title", "class", "type")} for item in elements]
        self.snapshots[identifier]["result"] = result
        return result

    def mutate(self, guid, key, middle=b"", handle=None):
        for kind in ("action", "commit"):
            self.send(guid, key, kind, middle, handle)

    def act(self, request, batch=False):
        window, form, public = self.form()
        if not batch:
            snapshot = self.snapshots.get(request.get("snapshot_id"))
            if not snapshot or snapshot["signature"] != (window["key"], (form or {}).get("handle"), public["title"]):
                raise Rejected("Snapshot is stale; observe the exact active form again")
        if request.get("expected_window") != public["title"]:
            raise Rejected("Active window differs from expected_window")
        if request.get("expected_form") is not None and request["expected_form"] != public["form_name"]:
            raise Rejected("Active form differs from expected_form")
        action = request.get("action")
        if action == "open":
            link = request.get("navigation_link", "")
            if not isinstance(link, str) or len(link) > 2048 or not re.fullmatch(r"e1cib/(?:list|app|data)/[^\s\x00-\x1f]+", link):
                raise Rejected("Use an in-base e1cib list/app/data navigation link")
            self.mutate(self.guids.EXECUTE_COMMAND, window["key"], self.wire.mk_command(link))
        elif action == "close":
            if not form:
                raise Rejected("No managed work form to close; application close is unsupported")
            self.mutate(self.guids.CLOSE, window["key"])
        elif action in {"input", "command"}:
            elements = self.elements(form)
            item = self.one(elements, request.get("element"), {"EditField"} if action == "input" else {"Button"})
            if not batch:
                prior = self.one(snapshot["elements"], request.get("element"))
                if (prior["key"], prior["handle"]) != (item["key"], item["handle"]):
                    raise Rejected("Element changed since observation")
            if action == "input":
                text = request.get("value")
                if not isinstance(text, str) or len(text) > 4096 or item.get("type") != "InputField":
                    raise Rejected("Only ordinary InputField text up to 4096 characters is supported")
                for guid in (self.guids.CURRENT_VISIBLE, self.guids.CURRENT_ENABLE):
                    if self.wire.decode_bool(self.send(guid, item["key"], middle=b"\xe1", handle=item["handle"])) is not True:
                        raise Rejected("Input field is not visible/enabled")
                if self.wire.decode_bool(self.send(self.guids.CURRENT_READONLY, item["key"], middle=b"\xe1", handle=item["handle"])) is not False:
                    raise Rejected("Input field is read-only or unknown")
                self.mutate(self.guids.INPUT_TEXT if text else self.guids.CLEAR, item["key"],
                            self.wire.mk_input_text(text) if text else b"", item["handle"])
                self.mutate(self.guids.FORM_GOTO_NEXT_ITEM, form["key"], handle=form["handle"])
            else:
                self.mutate(self.guids.CLICK, item["key"], handle=item["handle"])
        else:
            raise Rejected("Unsupported direct stage1 action")
        observed = self.capture({"view": "values" if request.get("expect", {}).get("fields") else "windows",
                                 "fields": list(request.get("expect", {}).get("fields", {}))})
        expect = request.get("expect", {})
        verified = bool(expect)
        if "window" in expect and observed["window"]["title"] != expect["window"]:
            verified = False
        if any(observed["values"].get(k) != v for k, v in expect.get("fields", {}).items()):
            verified = False
        observed.update(success=not expect or verified, action=action, action_dispatched=True,
                        postcondition_verified=verified)
        return observed

    def dispatch(self, request):
        self.dispatched = False
        if request.get("action") == "probe":
            import tc1c
            import cryptography
            return {"success": True, "python_version": sys.version.split()[0]}
        if request.get("action") == "connect":
            return self.connect(request)
        self.identity()
        if request.get("operation") == "details":
            snapshot = self.snapshots.get(request.get("snapshot_id"))
            if not snapshot:
                raise Rejected("Snapshot expired or belongs to another client")
            return dict(snapshot["result"], live_state=False)
        if request.get("operation") == "observe":
            return self.capture(request)
        if request.get("operation") == "batch":
            steps = request.get("steps")
            if not isinstance(steps, list) or not 1 <= len(steps) <= 16:
                raise Rejected("Batch requires 1..16 explicit steps")
            window, form, public = self.form()
            snapshot = self.snapshots.get(request.get("snapshot_id"))
            if not snapshot or snapshot["signature"] != (window["key"], (form or {}).get("handle"), public["title"]):
                raise Rejected("Initial batch snapshot is stale")
            if request.get("expected_window") != public["title"]:
                raise Rejected("Initial batch window changed")
            if request.get("expected_form") is not None and request["expected_form"] != public["form_name"]:
                raise Rejected("Initial batch form changed")
            results = self.partial = []
            for step in steps:
                self.dispatched = False
                self.identity()
                result = self.act(step, batch=True)
                results.append(result)
                if not result["success"]:
                    return {"success": False, "results": results, "postcondition_verified": False}
            return {"success": True, "results": results,
                    "postcondition_verified": all(r["postcondition_verified"] for r in results)}
        if request.get("operation") == "action":
            return self.act(request)
        raise Rejected("Unsupported worker request")


    def handle(self, request):
        self.dispatched = False
        self.partial = []
        try:
            return self.dispatch(request)
        except Exception as error:
            unknown = self.dispatched
            if unknown or not isinstance(error, Rejected):
                self.broken = True
                if self.client:
                    self.client.close()
            return {"success": False, "outcome_unknown": unknown, "action_dispatched": unknown,
                    "state": "outcome_unknown" if unknown else "rejected" if isinstance(error, Rejected) else "transport_failed", "reason": type(error).__name__,
                    "results": self.partial,
                    "message": str(error)[:400] if isinstance(error, Rejected) else "TestClient transport failed; inspect the original operation, never replay it"}


def main():
    worker = Worker()
    for line in iter(lambda: sys.stdin.buffer.readline(MAX_LINE + 1), b""):
        if len(line) > MAX_LINE or not line.endswith(b"\n"):
            break
        try:
            request = json.loads(line)
            result = worker.handle(request)
        except (ValueError, TypeError):
            result = {"success": False, "state": "rejected", "message": "Invalid worker request"}
        payload = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
        if len(payload) > 256 * 1024:
            payload = b'{"success":false,"outcome_unknown":true,"state":"outcome_unknown","message":"Worker result exceeds limit"}'
            worker.broken = True
            if worker.client:
                worker.client.close()
        sys.stdout.buffer.write(payload + b"\n")
        sys.stdout.buffer.flush()
    if worker.client:
        worker.client.close()

if __name__ == "__main__":
    main()
