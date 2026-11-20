# SPDX-License-Identifier: AGPL-3.0-only
"""Fixed native UI backend, independent of TestClient RPC actions; no automatic fallback."""
from datetime import datetime, timezone
from pathlib import Path
import hashlib
import json
import os
import secrets
import subprocess
import time
import uuid

SCRIPT = Path(__file__).with_name("window-ui.ps1")
SCRIPT_SHA256 = hashlib.sha256(SCRIPT.read_bytes()).hexdigest()
TABLE_VIEWPORT_ACTIONS = {"table_scroll_horizontal", "table_reveal_column"}
ACTIONS = {"menu_open", "menu_close", "system_dialog_close"}
DIALOGS = {
    "ФормаИзменитьФорму": {"Настройка формы", "Form customization", "Customize Form"},
    "FormCustomize": {"Настройка формы", "Form customization", "Customize Form"},
    "ФормаНастройкаСписка": {"Настройка списка", "List settings"},
}

class NotStarted(RuntimeError):
    pass

class Unknown(RuntimeError):
    pass


def run_helper(request, timeout):
    import win32job
    raw = json.dumps(request, ensure_ascii=False).encode("utf-8")
    shell = Path(os.environ.get("SystemRoot", "C:/Windows")) / "System32/WindowsPowerShell/v1.0/powershell.exe"
    if len(raw) > 65536 or not shell.is_file() or hashlib.sha256(SCRIPT.read_bytes()).hexdigest() != SCRIPT_SHA256:
        raise NotStarted("window_ui_helper_unavailable")
    job = win32job.CreateJobObject(None, "")
    child = None
    try:
        info = win32job.QueryInformationJobObject(job, win32job.JobObjectExtendedLimitInformation)
        info["BasicLimitInformation"]["LimitFlags"] |= win32job.JOB_OBJECT_LIMIT_KILL_ON_JOB_CLOSE
        win32job.SetInformationJobObject(job, win32job.JobObjectExtendedLimitInformation, info)
        try:
            child = subprocess.Popen([str(shell), "-NoLogo", "-NoProfile", "-NonInteractive", "-STA", "-File", str(SCRIPT.resolve())],
                                     stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                                     creationflags=subprocess.CREATE_NO_WINDOW, close_fds=True)
            # Script waits for stdin before inspecting or acting on any window.
            win32job.AssignProcessToJobObject(job, int(child._handle))
        except Exception as error:
            if child is not None:
                child.kill()
                child.communicate()
            raise NotStarted("window_ui_helper_ownership_failed") from error
        try:
            stdout, stderr = child.communicate(raw, timeout=timeout)
        except subprocess.TimeoutExpired as error:
            child.kill()
            child.communicate()
            raise Unknown("window_ui_helper_timeout") from error
        if child.returncode != 0 or len(stdout) > 131072 or len(stderr) > 65536:
            raise Unknown("window_ui_helper_invalid_reply")
        result = json.loads(stdout.decode("utf-8-sig"))
        if (not isinstance(result, dict) or result.get("nonce") != request["nonce"]
                or result.get("mode") != request["mode"] or result.get("op") != request["op"]
                or result.get("backend") != "windows_header_ui" or type(result.get("success")) is not bool
                or type(result.get("action_dispatched")) is not bool):
            raise Unknown("window_ui_helper_unbound_reply")
        return result
    except (NotStarted, Unknown):
        raise
    except Exception as error:
        raise Unknown("window_ui_helper_failed") from error
    finally:
        job.Close()  # Also kills compiler/provider helper descendants if the parent exits.
        if child is not None and child.poll() is None:
            child.kill()
            child.communicate()


def menu_identity(reference):
    menu = reference.get("menu")
    return None if not menu else (reference["frame"], menu["window"]["Handle"], menu["runtime_id"])


def dialog_identity(reference):
    dialog = reference.get("dialog")
    if not dialog:
        return None
    w = dialog["window"]
    frame = reference["frame"]
    return (w["Handle"], w["Process"], w["Thread"], w["Owner"], w["Class"], dialog["runtime_id"],
            frame["window"]["Handle"], frame["root_rid"], frame["caption_rid"], frame["header_rid"])


class WindowEngine:
    def __init__(self, worker, rejected, helper=None):
        self.w, self.reject, self.helper = worker, rejected, helper or run_helper

    def source(self, view, form, public):
        if view == "menu":
            if not form or not public.get("form_name"):
                raise self.reject("Header menu requires an observed managed source form")
            return dict(title=public["title"], form_name=public["form_name"])
        source = getattr(self.w, "window_ui_source", None)
        if (form or not source or public.get("title") not in DIALOGS.get(source.get("element"), set())
                or not source.get("operation_id") or public.get("id") != source.get("dialog_window_id")):
            self.w.window_ui_source = None
            raise self.reject("Only a known system dialog opened by this client's recorded command is supported")
        return source

    def bind_dialog(self, source, observed):
        # Called only after a successful, observed standard command. Never adopt a later dialog by title.
        self.w.window_ui_source = None
        if (not observed.get("success") or observed.get("window", {}).get("form_name")
                or observed.get("window", {}).get("title") not in DIALOGS.get(source.get("element"), set())
                or not source.get("operation_id")):
            return False
        saved = self.w.snapshots.get(observed.get("snapshot_id"), {})
        if not saved.get("signature"):
            return False
        window, form, public = self.w.form()
        if saved["signature"] != (window["key"], (form or {}).get("handle"), public["title"]):
            return False
        reply = self.invoke("system_dialog", source, public)
        identity = dialog_identity(reply["reference"])
        again_window, again_form, again_public = self.w.form()
        if not identity or saved["signature"] != (again_window["key"], (again_form or {}).get("handle"), again_public["title"]):
            return False
        self.w.window_ui_source = dict(source, dialog_window_id=public["id"], dialog_identity=identity)
        return True

    def invoke(self, view, source, public, mode="probe", reference=None, table_options=None):
        started = getattr(self.w, "process_started_at", None)
        if not isinstance(started, str) or not started:
            raise self.reject("Native UI requires the original process start identity")
        remaining = getattr(self.w, "deadline", time.monotonic() + 30) - time.monotonic()
        if remaining < 12:
            raise self.reject("Native UI needs at least12seconds of remaining operation budget; no input sent")
        request = dict(nonce=secrets.token_hex(16), op=view if view in {"menu", "table"} else "dialog", mode=mode,
                       pid=self.w.pid, started=started, source_title=source["title"])
        if view == "system_dialog":
            request["dialog_title"] = public["title"]
        if table_options:
            request.update(table_options)
        if reference is not None:
            request["reference"] = reference
        prior = self.w.dispatched
        if mode != "probe":
            # Durable Java operation intent already exists. Lost helper replies never permit retry.
            self.w.dispatched = True
        try:
            reply = self.helper(request, min(12, remaining))
        except NotStarted as error:
            self.w.dispatched = prior
            raise self.reject(str(error)) from error
        except Exception as error:
            self.w.window_ui_failure = dict(phase=mode, reason=str(error), action_dispatch_state="unknown" if mode != "probe" else "not_dispatched")
            raise self.reject("Native UI helper outcome is unproven; inspect original operation") from error
        if mode != "probe" and not reply["action_dispatched"]:
            self.w.dispatched = prior
        if not reply["success"]:
            self.w.window_ui_failure = {k: reply.get(k) for k in ("phase", "reason", "action_dispatched")}
            raise self.reject("Native UI refused or unproven: " + str(reply.get("reason")))
        if not isinstance(reply.get("reference"), dict) or not isinstance(reply["reference"].get("frame"), dict):
            raise self.reject("Native UI reference is unavailable")
        return reply

    def scroll_table(self, request, public, signature, column_titles, column_title):
        options = dict(column_titles=column_titles, column_title=column_title)
        for key in ("direction", "horizontal_percent"):
            if key in request:
                options[key] = request[key]
        observed = self.invoke("table", public, public, mode="scroll", table_options=options)
        window, form, current = self.w.form()
        if (window["key"], (form or {}).get("handle"), current["title"]) != signature:
            raise self.reject("Active form changed after table viewport operation")
        viewport = observed.get("viewport")
        if not isinstance(viewport, dict) or observed.get("foreground_changed"):
            raise self.reject("Table viewport receipt is unproven")
        viewport["table"] = request["table"]
        if column_title is not None:
            viewport["column"] = request["column"]
        return dict(success=True, action=request["action"], action_dispatched=self.w.dispatched,
                    postcondition_verified=True, window=current, viewport=viewport)

    def capture(self, request, window, form, public):
        view = request["view"]
        source = self.source(view, form, public)
        observed = self.invoke(view, source, public)
        again_window, again_form, again_public = self.w.form()
        signature = (window["key"], (form or {}).get("handle"), public["title"])
        if signature != (again_window["key"], (again_form or {}).get("handle"), again_public["title"]):
            raise self.reject("Native UI source changed during observation")
        reference = observed["reference"]
        if view == "system_dialog" and dialog_identity(reference) != source.get("dialog_identity"):
            self.w.window_ui_source = None
            raise self.reject("Original system dialog instance disappeared or changed; a later dialog is not adopted")
        owned = getattr(self.w, "owned_header_menu", None)
        proven = bool(owned and menu_identity(reference) == menu_identity(owned))
        native = dict(backend="window_message_click" if view == "menu" else "window_message_close",
                      view=view, header_menu_open=bool(reference.get("menu")), header_menu_proven=proven,
                      scope="visible_top_level_menu_provider" if view == "menu" else "known_owned_system_dialog",
                      provider_scope_complete=True, submenus_enumerated=False,
                      foreground_changed=observed.get("foreground_changed", False),
                      reference_sha256=hashlib.sha256(json.dumps(reference, sort_keys=True).encode()).hexdigest())
        if view == "menu":
            target = (reference.get("menu") or {}).get("window")
            if target:
                native["capture_target"] = dict(process_id=self.w.pid, process_started_at=self.w.process_started_at,
                    window_id=target["Handle"], window_class=target["Class"], ui_thread_id=target["Thread"],
                    owner_window_id=target["Owner"], left=target["X"], top=target["Y"], width=target["Width"], height=target["Height"])
                native["capture_call"] = dict(tool="edt_process_snapshot", arguments=dict(action="snapshot",
                    process_id=self.w.pid, process_started_at=self.w.process_started_at, window_id=target["Handle"], include_popups=False))
            native["opening_operation_id"] = getattr(self.w, "owned_header_menu_operation", None) if proven else None
            native["items"] = [{"name": item["name"], "enabled": item["enabled"], "visible": True} for item in (reference.get("menu") or {}).get("items", [])]
            native["potential_actions"] = (["menu_close"] if proven else [] if reference.get("menu") else ["menu_open"])
        else:
            native.update(dialog_kind="form_customization" if public["title"] in DIALOGS.get("ФормаИзменитьФорму", set()) else "list_settings",
                          opening_operation_id=source["operation_id"], return_form=source["form_name"], potential_actions=["system_dialog_close"])
        identifier = "tc-form-" + uuid.uuid4().hex
        result = dict(success=True, transport="testclient", snapshot_id=identifier, window=public, values={},
                      scope=native["scope"], complete=True, live_state=True, captured_at=datetime.now(timezone.utc).isoformat(), native_ui=native)
        self.w.snapshots[identifier] = dict(signature=signature, elements=[], window=public,
                                          window_ui=dict(reference=reference, source=source, view=view), result=result)
        while len(self.w.snapshots) > 16:
            del self.w.snapshots[next(iter(self.w.snapshots))]
        return result

    def run(self, request, snapshot, window, form, public):
        action = request["action"]
        view = "system_dialog" if action == "system_dialog_close" else "menu"
        saved = snapshot.get("window_ui") if snapshot else None
        if not saved or saved.get("view") != view:
            raise self.reject("Observe the exact native UI view before this action")
        source = self.source(view, form, public)
        if source != saved["source"]:
            raise self.reject("Native UI opening/source provenance changed")
        if action == "menu_close":
            owned = getattr(self.w, "owned_header_menu", None)
            if not owned or menu_identity(owned) != menu_identity(saved["reference"]):
                raise self.reject("Only the exact header menu opened by this worker can be closed")
        if action == "system_dialog_close" and request.get("return_form") != source["form_name"]:
            raise self.reject("return_form must match the recorded source of this dialog")
        # Apply helper checks this immutable reference twice, immediately before native input.
        # Do not spawn an additional redundant UIA probe process here.
        before = saved["reference"]
        if view == "system_dialog" and dialog_identity(before) != source.get("dialog_identity"):
            self.w.window_ui_source = None
            raise self.reject("System dialog instance changed before close")
        again_window, again_form, again_public = self.w.form()
        if snapshot["signature"] != (again_window["key"], (again_form or {}).get("handle"), again_public["title"]):
            raise self.reject("TestClient source changed before native input")
        self.w.identity()
        result = self.invoke(view, source, public, "open" if action == "menu_open" else "close", before)
        if action == "menu_open":
            if not result["reference"].get("menu"):
                raise self.reject("Header opening was not proven")
            self.w.owned_header_menu = result["reference"]
            self.w.owned_header_menu_operation = getattr(self.w, "current_operation_id", None)
        elif action == "menu_close":
            self.w.owned_header_menu = None
            self.w.owned_header_menu_operation = None
        if action == "system_dialog_close":
            observed = self.w.wait_capture({"view": "windows"}, {"window": source["title"]},
                                          request.get("wait_timeout_ms", 2000), source["form_name"])
            actual = self.w.snapshots.get(observed.get("snapshot_id"), {})
            if not observed.get("success") or actual.get("signature") != source["signature"]:
                raise self.reject("Dialog close requested, but original source form was not restored; no auto-answer or replay")
            self.w.window_ui_source = None
        else:
            observed = self.capture({"view": "menu"}, *self.w.form())
            after = self.w.snapshots.get(observed.get("snapshot_id"), {})
            if after.get("signature") != snapshot["signature"]:
                raise self.reject("Source form identity changed after menu input")
            final = observed.get("native_ui", {})
            if action == "menu_open" and (not final.get("header_menu_open") or not final.get("header_menu_proven")
                    or menu_identity(after.get("window_ui", {}).get("reference", {})) != menu_identity(result["reference"])):
                raise self.reject("Opened header menu disappeared or changed before final verification")
            if action == "menu_close" and final.get("header_menu_open"):
                raise self.reject("A menu is still present after close; inspect original operation")
        observed.update(action=action, action_dispatched=self.w.dispatched, postcondition_verified=True,
                        ui_backend=request["ui_backend"], close_semantics="request_close_not_discard" if action.endswith("close") else None,
                        foreground_changed=result.get("foreground_changed", False))
        return observed
