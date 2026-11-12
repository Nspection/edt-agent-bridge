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
sys.path.insert(0, str(Path(__file__).parent))
from dialogs import DialogEngine
from tables import TableEngine
from controls import ControlEngine, ACTIONS as CONTROL_ACTIONS, potential_actions
MAX_LINE = 128 * 1024
MAX_WIRE = 4 * 1024 * 1024
DEFAULT_OPERATION_SECONDS = 60
CHILD = b"\xe1" + b"\x81" * 7
COLLECTION = b"\xe0\x4b\x55"

class ConditionPending(ValueError):
    pass


class ReadNotReady(Exception):
    """A complete negative reply to an observational command; no transport loss."""
    pass


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
        self.mutations_blocked = False

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

    def send(self, guid, key=None, kind="read", middle=b"", handle=None, pad=None):
        before = self.dispatched
        if kind not in {"read", "read818"}:
            self.dispatched = True  # conservative until the wire proves no frame was sent
        self.rpc_count = getattr(self, "rpc_count", 0) + 1
        started = time.monotonic()
        self.last_rpc = {"kind": kind, "phase": "awaiting_reply", "started": started}
        try:
            result = self.client.send_cmd(guid, key, kind=kind, middle=middle, handle=handle, pad=pad)
        except Exception as error:
            if isinstance(error, getattr(self.wire, "CommandNotSentTimeout", ())):
                self.dispatched = before
                self.last_rpc["phase"] = "not_sent"
            elif isinstance(error, getattr(self.wire, "OperationError", ())):
                self.last_rpc["phase"] = "reply_rejected"
            else:
                self.last_rpc["phase"] = "incomplete_exchange"
            raise
        self.last_rpc["phase"] = "reply_complete"
        if not result.get("ok"):
            raise (ReadNotReady("TestClient read is not ready") if kind in {"read", "read818"} else Rejected("TestClient did not confirm operation"))
        return result["raw"]

    def diagnostics(self, started, failure_kind):
        client = self.client
        rpc = getattr(self, "last_rpc", {})
        socket = getattr(client, "s", None)
        # Metadata only: never include frame bytes, GUID payloads, field values or titles.
        return {"failure_kind": failure_kind, "worker_pid": __import__("os").getpid(),
                "owned_client_pid": self.pid, "port": self.port,
                "worker_alive": True, "socket_open": socket is not None,
                "elapsed_ms": max(0, int((time.monotonic()-started)*1000)),
                "operation_timeout_s": self.operation_timeout_s,
                "receive_timeout_s": getattr(client, "RECV_TIMEOUT", None),
                "rpc_count": getattr(self, "rpc_count", 0),
                "last_rpc_kind": rpc.get("kind"), "last_rpc_phase": rpc.get("phase"),
                "last_rpc_elapsed_ms": max(0, int((time.monotonic()-rpc.get("started",time.monotonic()))*1000)),
                "bytes_received": getattr(socket, "received", None),
                "buffered_bytes": len(client._buf) if socket is not None and hasattr(client, "_buf") else None,
                "pending_replies": getattr(client, "_pending", None) if socket is not None else None}

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
        if not matches:
            raise ConditionPending("Element is not present yet: " + str(name))
        if len(matches) != 1 or (expected and matches[0]["class"] not in expected):
            raise Rejected("Element is absent, ambiguous or unsupported: " + name)
        return matches[0]

    def value(self, item):
        if item.get("type") == "CalendarField":
            raise Rejected("Calendar has no supported scalar getter; verify the date through a bound scalar field in expect")
        displayed = item.get("type") == "CheckBoxField"
        raw = self.send(self.guids.GET_DISPLAYED_TEXT if displayed else self.guids.GET_PROPERTY,
                        item["key"], middle=b"\xe1" if displayed else COLLECTION, handle=item["handle"])
        text = self.wire.decode_field_text(raw, property_value=not displayed)
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
        if view not in {"elements", "values", "windows", "table"}:
            raise Rejected("Direct stage1 supports elements, values and windows")
        if view == "values" and not names:
            raise Rejected("Explicit fields are required for values")
        table = TableEngine(self, Rejected).describe(request) if view == "table" else None
        values = {name: self.value(self.one(elements, name, {"EditField"})) for name in names}
        # Recheck active form after traversal, not just the caption.
        again_window, again_form, again_public = self.form()
        signature = (window["key"], (form or {}).get("handle"), public["title"])
        if signature != (again_window["key"], (again_form or {}).get("handle"), again_public["title"]):
            raise ConditionPending("Active form changed during observation")
        identifier = "tc-form-" + uuid.uuid4().hex
        self.snapshots[identifier] = {"signature": signature, "elements": elements, "window": public}
        while len(self.snapshots) > 16:
            del self.snapshots[next(iter(self.snapshots))]
        result = {"success": True, "transport": "testclient", "snapshot_id": identifier,
                  "window": public, "values": values, "scope": "ordinary_controls", "complete": True,
                  "captured_at": datetime.now(timezone.utc).isoformat(),
                  "live_state": True}
        if table is not None:
            result["table"] = table
            result["scope"] = "current_table_row"
        if view == "elements":
            result["elements"] = [dict({k: item.get(k) for k in ("name", "title", "class", "type")}, potential_actions=potential_actions(item)) for item in elements]
        self.snapshots[identifier]["result"] = result
        return result

    def wait_capture(self, request, expect=None, timeout_ms=1500, expected_form=None, *, max_timeout_ms=None):
        """Repeat only observations; an incomplete wire reply always escapes and breaks the pipe."""
        if max_timeout_ms is None:
            max_timeout_ms = int(getattr(self, "operation_timeout_s", DEFAULT_OPERATION_SECONDS) * 1000)
        if type(timeout_ms) not in (int, float) or not 0 <= timeout_ms <= max_timeout_ms or timeout_ms % 1:
            raise Rejected("wait_timeout_ms exceeds the operation budget")
        timeout_ms = int(timeout_ms)
        expect = expect or {}
        started = time.monotonic()
        deadline = min(started + timeout_ms / 1000, getattr(self, "deadline", float("inf")))
        last = None
        attempts = 0
        previous_deadline = getattr(self.client, "_io_deadline", None)
        try:
            while True:
                if (attempts and time.monotonic() >= deadline) or time.monotonic() >= getattr(self, "deadline", float("inf")):
                    break
                self.identity()
                # Polling expiry must not cut a reply in half and desynchronise the pipe.
                # Finish one bounded observation under the overall I/O budget; only
                # scheduling another observation is controlled by the condition deadline.
                self.client._io_deadline = getattr(self, "deadline", started + DEFAULT_OPERATION_SECONDS)
                attempts += 1
                try:
                    last = self.capture(request)
                    ready = bool(last["window"]["title"])
                    matched = ready and (expected_form is None or last["window"].get("form_name") == expected_form)
                    matched = matched and ("window" not in expect or last["window"]["title"] == expect["window"])
                    matched = matched and all(last["values"].get(k) == v for k, v in expect.get("fields", {}).items())
                    if matched and expect.get("elements"):
                        snapshot = self.snapshots.get(last.get("snapshot_id"))
                        if not snapshot:
                            raise Rejected("Condition snapshot expired")
                        window, form, public = self.form()
                        signature = (window["key"], (form or {}).get("handle"), public["title"])
                        if signature != snapshot["signature"]:
                            raise ConditionPending("Active form instance changed before condition read")
                        elements = snapshot["elements"]
                        for name, states in expect["elements"].items():
                            found = [x for x in elements if x.get("name") == name]
                            if len(found) > 1:
                                raise Rejected("Ambiguous condition element: " + name)
                            if not found:
                                matched = False
                                break
                            item = found[0]
                            for state, value in states.items():
                                guid = {"visible": self.guids.CURRENT_VISIBLE, "enabled": self.guids.CURRENT_ENABLE,
                                        "readonly": self.guids.CURRENT_READONLY}[state]
                                actual = self.wire.decode_bool(self.send(guid, item["key"], middle=b"\xe1", handle=item["handle"]))
                                matched = matched and actual is value
                        # Do not certify a state that was read across a form transition.
                        window, form, public = self.form()
                        after = (window["key"], (form or {}).get("handle"), public["title"])
                        if after != snapshot["signature"]:
                            raise ConditionPending("Active form instance changed during condition read")
                    if matched:
                        last.update(wait={"satisfied": True, "attempts": attempts, "elapsed_ms": int((time.monotonic()-started)*1000)})
                        return last
                except Exception as error:
                    if isinstance(error, (ReadNotReady, ConditionPending)):
                        last = None
                    elif isinstance(error, getattr(self.wire, "CommandNotSentTimeout", ())):
                        # Vendor guarantees no frame bytes were sent. Earlier replies were complete.
                        # Receive/socket/other protocol timeouts must escape and break the pipe.
                        break
                    else:
                        raise
                # Absent exact fields are a supported not-yet condition, but malformed/ambiguous trees are not.
                if time.monotonic() >= deadline:
                    break
                time.sleep(min(.1, max(0, deadline-time.monotonic())))
            result = last or {"window": None, "values": {}, "complete": False}
            result.update(success=False, state="condition_timeout", outcome_unknown=False,
                          action_dispatched=self.dispatched, postcondition_verified=False,
                          wait={"satisfied": False, "attempts": attempts, "elapsed_ms": int((time.monotonic()-started)*1000)})
            return result
        finally:
            self.client._io_deadline = previous_deadline

    def postcondition(self, request):
        self.stage = "postcondition"
        expect = request.get("expect", {})
        observed = self.wait_capture({"view": "values" if expect.get("fields") else "windows",
                                      "fields": list(expect.get("fields", {}))}, expect,
                                     request.get("wait_timeout_ms", 10000 if expect else 1500))
        observed.update(action=request["action"], action_dispatched=self.dispatched,
                        postcondition_verified=observed["success"] and bool(expect))
        return observed

    def mutate(self, guid, key, middle=b"", handle=None):
        for kind in ("action", "commit"):
            self.send(guid, key, kind, middle, handle)

    def act(self, request, batch=False):
        if request.get("action") == "wait":
            self.stage = "wait"
            if not batch and request.get("snapshot_id") not in self.snapshots:
                raise Rejected("Wait snapshot belongs to another client or expired")
            expect = dict(request.get("expect", {}), window=request["expected_window"])
            observed = self.wait_capture({"view": "values" if expect.get("fields") else "windows",
                                          "fields": list(expect.get("fields", {}))}, expect,
                                         request.get("wait_timeout_ms", 5000), request.get("expected_form"))
            observed.update(action="wait", action_dispatched=False, postcondition_verified=observed["success"])
            return observed
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
        self.stage = "dispatch"
        if action in {"table_read", "table_select", "table_add", "table_set"}:
            table_result = TableEngine(self, Rejected).run(request, None if batch else snapshot)
            expect = request.get("expect", {})
            observed = self.postcondition(request)
            passed = table_result["success"] and observed["success"]
            passed = passed and all(observed["values"].get(n)==v for n,v in expect.get("fields",{}).items())
            observed.update(success=passed,action=action,action_dispatched=self.dispatched,
                            postcondition_verified=passed and (bool(expect) or table_result["postcondition_verified"]),table=table_result)
            return observed
        elements = self.elements(form) if action == "command" else None
        if action in CONTROL_ACTIONS or (action == "command" and self.one(elements, request.get("element")).get("class") != "Button"):
            if not form: raise Rejected("Managed form is required for control actions")
            if elements is None: elements = self.elements(form)
            control = ControlEngine(self, Rejected).run(request, form, elements, None if batch else snapshot)
            observed = self.postcondition(request)
            # Only explicitly acknowledged operations lack an intrinsic state predicate.
            intrinsic = control["state_verified"] or "ack_only" in control.get("verification", "")
            passed = observed["success"] and intrinsic
            explicit_proof = bool(request.get("expect", {}).get("fields")) if action == "set_date" else bool(request.get("expect"))
            observed.update(success=passed, action=action,
                            action_dispatched=self.dispatched, control=control,
                            postcondition_verified=passed and (control["state_verified"] or explicit_proof))
            return observed
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
            if elements is None: elements = self.elements(form)
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
        return self.postcondition(request)

    def dispatch(self, request):
        self.dispatched = False
        if request.get("action") == "probe":
            import tc1c
            import cryptography
            return {"success": True, "python_version": sys.version.split()[0]}
        if request.get("action") == "listener":
            try:
                listening = port_owner(request["port"]) == request["pid"]
            except Rejected:
                listening = False
            return {"success": True, "listening": listening, "action_dispatched": False}
        if request.get("action") == "connect":
            return self.connect(request)
        self.identity()
        if request.get("operation") == "dialog_inspect":
            return DialogEngine(self, Rejected).inspect(request.get("blocking_operation_id"))
        if request.get("operation") == "dialog_answer":
            return DialogEngine(self, Rejected).answer(request)
        if request.get("operation") == "details":
            snapshot = self.snapshots.get(request.get("snapshot_id"))
            if not snapshot:
                raise Rejected("Snapshot expired or belongs to another client")
            return dict(snapshot["result"], live_state=False)
        if request.get("operation") == "observe":
            if request.get("snapshot_id"):
                raise Rejected("Use details for a saved snapshot")
            return self.wait_capture(request, timeout_ms=request.get("wait_timeout_ms", 1500))
        if request.get("operation") == "ready":
            if "startup_timeout_ms" not in request:
                return self.wait_capture({"view": "windows"}, timeout_ms=request.get("wait_timeout_ms", 10000), max_timeout_ms=10000)
            budget = request["startup_timeout_ms"]
            if type(budget) not in (int, float) or not 1 <= budget <= 180000 or budget % 1:
                raise Rejected("Invalid startup observation budget")
            # Keep one complete reply within the startup budget. A ten-second wire
            # timeout during cold startup would otherwise destroy the channel.
            self.deadline = time.monotonic() + budget / 1000
            previous_timeout = self.client.RECV_TIMEOUT
            self.client.RECV_TIMEOUT = budget / 1000
            try:
                return self.wait_capture({"view": "windows"}, timeout_ms=budget, max_timeout_ms=180000)
            finally:
                self.client.RECV_TIMEOUT = previous_timeout
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
            for index, step in enumerate(steps):
                self.step_index = index
                self.dispatched = False
                self.identity()
                result = self.act(step, batch=True)
                result["step_index"] = index
                results.append(result)
                if not result["success"]:
                    return {"success": False, "results": results, "outcome_unknown": False,
                            "action_dispatched": any(r.get("action_dispatched") for r in results),
                            "failed_step_index": index, "postcondition_verified": False}
            return {"success": True, "results": results, "outcome_unknown": False,
                    "action_dispatched": any(r.get("action_dispatched") for r in results),
                    "postcondition_verified": all(r["postcondition_verified"] for r in results)}
        if request.get("operation") == "action":
            return self.act(request)
        raise Rejected("Unsupported worker request")


    def handle(self, request):
        self.dispatched = False
        self.partial = []
        self.stage = "preflight"
        self.step_index = None
        previous_client = self.client
        previous_timeout = getattr(previous_client, "RECV_TIMEOUT", None)
        started = time.monotonic()
        self.rpc_count = 0
        self.last_rpc = {}
        self.operation_timeout_s = DEFAULT_OPERATION_SECONDS
        self.deadline = started + DEFAULT_OPERATION_SECONDS
        try:
            budget = request.get("operation_timeout_s", DEFAULT_OPERATION_SECONDS)
            if type(budget) not in (int, float) or not 5 <= budget <= 300 or budget % 1:
                raise Rejected("operation_timeout_s must be an integer in5..300")
            for item in [request] + (request.get("steps", []) if request.get("action") == "batch" else []):
                if "wait_timeout_ms" in item:
                    wait = item["wait_timeout_ms"]
                    if type(wait) not in (int, float) or not 0 <= wait <= budget * 1000 or wait % 1:
                        raise Rejected("wait_timeout_ms exceeds operation_timeout_s or is not an integer")
                if item is not request and "operation_timeout_s" in item:
                    raise Rejected("Set operation_timeout_s on the whole batch")
            self.operation_timeout_s = int(budget)
            self.deadline = time.monotonic() + self.operation_timeout_s
            if self.client:
                self.client._io_deadline = self.deadline
                self.client.RECV_TIMEOUT = self.operation_timeout_s
            if self.mutations_blocked and request.get("operation") in {"action", "batch"}:
                raise Rejected("Previous mutation outcome is unknown; only observe/status/result are allowed")
            result = self.dispatch(request)
            result["transport_state"] = "broken" if self.broken else "usable"
            if not result.get("success"):
                result["diagnostics"] = self.diagnostics(started, result.get("state", "operation_failed"))
            return result
        except Exception as error:
            local_expiry = isinstance(error, getattr(getattr(self, "wire", None), "CommandNotSentTimeout", ()))
            protocol_rejection = isinstance(error, getattr(getattr(self, "wire", None), "OperationError", ()))
            logical = isinstance(error, (Rejected, ConditionPending, ReadNotReady))
            unknown = self.dispatched
            broken = (self.broken or (self.client is not None and hasattr(self.client, "s") and self.client.s is None)
                      or not (local_expiry or protocol_rejection or logical))
            failure_kind = ("local_budget_expired" if local_expiry else "protocol_rejected" if protocol_rejection
                            else "validation_rejected" if logical else "wire_timeout" if isinstance(error, TimeoutError)
                            else "connection_or_protocol_failure")
            diagnostics = self.diagnostics(started, failure_kind)
            if broken:
                self.broken = True
                if self.client:
                    self.client.close()
            if unknown:
                self.mutations_blocked = True
                # Capture only a fully rejected mutation with a proven frame boundary.
                # A later unrelated window cannot become the blocked operation's dialog.
                if not broken and diagnostics.get("last_rpc_phase") in {"reply_rejected", "reply_complete"} and diagnostics.get("last_rpc_kind") in {"action", "commit"} and request.get("_operation_id") and request.get("operation") in {"action", "batch"}:
                    try:
                        captured = DialogEngine(self, Rejected).read()
                        captured["operation_id"] = request["_operation_id"]
                        self.blocked_dialog = captured
                    except Exception as capture_error:
                        if not isinstance(capture_error, (Rejected, ConditionPending, ReadNotReady, getattr(self.wire, "OperationError", Rejected), getattr(self.wire, "CommandNotSentTimeout", Rejected))):
                            broken = self.broken = True
                            self.client.close()
            any_action = unknown or any(r.get("action_dispatched") for r in self.partial)
            state = ("outcome_unknown" if unknown else "operation_timeout" if local_expiry
                     else "operation_rejected" if protocol_rejection else "rejected" if logical else "transport_failed")
            return {"success": False, "outcome_unknown": unknown, "action_dispatched": any_action,
                    "execution_state": "not_proven" if unknown else "not_dispatched" if not any_action else "partial_completed",
                    "transport_state": "broken" if broken else "usable", "retry_safe": not any_action and not broken and not self.mutations_blocked,
                    "mutation_blocked": self.mutations_blocked,
                    "dialog_available": bool(getattr(self, "blocked_dialog", None)) and not self.blocked_dialog.get("answer_attempted", False) and not broken,
                    "state": state, "reason": type(error).__name__, "diagnostics": diagnostics,
                    "results": self.partial, "stage": self.stage, "failed_step_index": self.step_index,
                    "message": str(error)[:400] if logical else "Inspect original operation and diagnostics; no action was automatically replayed"}

        finally:
            if self.client:
                self.client._io_deadline = None
                if self.client is previous_client and previous_timeout is not None:
                    self.client.RECV_TIMEOUT = previous_timeout


def bounded_reply(result, request, worker):
    payload = json.dumps(result, ensure_ascii=False, separators=(",", ":")).encode("utf-8")
    if len(payload) <= 256 * 1024:
        return payload
    mutation = request.get("operation") in {"action", "batch"}
    dispatched = result.get("action_dispatched")
    if type(dispatched) is not bool:
        dispatched = None if mutation else False
    unknown = bool(result.get("outcome_unknown")) or (mutation and dispatched is None)
    if unknown:
        worker.mutations_blocked = True
    # Payload delivery is not an execution failure and never proves a lost channel.
    projected = {"success": False, "state": "outcome_unknown" if unknown else "result_too_large",
                 "outcome_unknown": unknown, "transport_state": result.get("transport_state", "broken" if worker.broken else "usable"),
                 "mutation_blocked": worker.mutations_blocked, "retry_safe": False,
                 "payload_truncated": True, "execution_success": result.get("success") is True,
                 "postcondition_verified": result.get("postcondition_verified") is True,
                 "execution_state": "not_proven" if unknown else "not_dispatched" if dispatched is False else "completed_or_failed",
                 "reason": "worker_result_limit", "message": "Read a narrower form scope; never replay a mutation to retrieve its result",
                 "diagnostics": {"failure_kind": "worker_result_limit", "result_bytes": len(payload), "limit_bytes": 256 * 1024}}
    if dispatched is not None:
        projected["action_dispatched"] = dispatched
    for key in ("snapshot_id", "stage", "state"):
        if isinstance(result.get(key), str) and len(result[key]) <= 128:
            projected["original_state" if key == "state" else key] = result[key]
    if type(result.get("failed_step_index")) is int:
        projected["failed_step_index"] = result["failed_step_index"]
    if isinstance(result.get("results"), list):
        projected["finished_steps"] = len(result["results"])
        projected["passed_steps"] = sum(item.get("success") is True for item in result["results"] if isinstance(item, dict))
        projected["verified_steps"] = sum(item.get("postcondition_verified") is True for item in result["results"] if isinstance(item, dict))
    return json.dumps(projected, ensure_ascii=False, separators=(",", ":")).encode("utf-8")


def main():
    worker = Worker()
    for line in iter(lambda: sys.stdin.buffer.readline(MAX_LINE + 2), b""):
        if len(line) > MAX_LINE + 1 or not line.endswith(b"\n"):
            break
        request = {}
        try:
            request = json.loads(line)
            result = worker.handle(request)
        except (ValueError, TypeError):
            result = {"success": False, "state": "rejected", "message": "Invalid worker request"}
        payload = bounded_reply(result, request, worker)
        sys.stdout.buffer.write(payload + b"\n")
        sys.stdout.buffer.flush()
    if worker.client:
        worker.client.close()

if __name__ == "__main__":
    main()
