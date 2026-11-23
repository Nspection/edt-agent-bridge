"""Open a local EDT source location from the Codex Open in menu through EDT Agent Bridge."""
import argparse
import ctypes
import http.client
import ipaddress
import json
from pathlib import Path
import sys
import time
import tomllib
from urllib.parse import urlsplit
import xml.etree.ElementTree as ET
from xml.parsers import expat


SOURCE_TYPES = {".bsl", ".form", ".mdo"}
FORM_PAGE = "editors.form.pages.main"
FORM_ITEMS = {"items", "contextMenu", "extendedTooltip", "autoCommandBar", "searchStringAddition",
              "viewStatusAddition", "searchControlAddition"}


class NavigationError(Exception):
    pass


class FormPreviewLoading(NavigationError):
    pass


class Bridge:
    def __init__(self, config, server_name):
        settings = tomllib.loads(config.read_text(encoding="utf-8-sig"))
        server = settings.get("mcp_servers", {}).get(server_name, {})
        endpoint = urlsplit(server.get("url", ""))
        try:
            loopback = endpoint.hostname == "localhost" or ipaddress.ip_address(endpoint.hostname).is_loopback
        except ValueError:
            loopback = False
        if (server.get("enabled") is False or endpoint.scheme != "http" or not loopback
                or endpoint.username or endpoint.password or endpoint.fragment or endpoint.query):
            raise NavigationError("Настройте локальное MCP-подключение EDT Agent Bridge в Codex.")
        self.connection = http.client.HTTPConnection(endpoint.hostname, endpoint.port, timeout=15)
        self.route = endpoint.path or "/mcp"
        self.headers = dict(server.get("http_headers", {}))
        self.headers.update({
            "Accept": "application/json, text/event-stream",
            "Content-Type": "application/json",
        })
        self.request_id = 0
        self.rpc("initialize", {
            "protocolVersion": "2025-03-26",
            "capabilities": {},
            "clientInfo": {"name": "edt-open-in-codex", "version": "1"},
        })

    def rpc(self, method, params):
        self.request_id += 1
        payload = {"jsonrpc": "2.0", "id": self.request_id, "method": method, "params": params}
        self.connection.request("POST", self.route, body=json.dumps(payload).encode(), headers=self.headers)
        with self.connection.getresponse() as response:
            if response.status != 200:
                raise NavigationError("Bridge не принял MCP-запрос. Проверьте подключение в EDT и Codex.")
            session = response.getheader("Mcp-Session-Id")
            if session:
                self.headers["Mcp-Session-Id"] = session
            raw = response.read(1024 * 1024 + 1)
        if len(raw) > 1024 * 1024:
            raise NavigationError("Bridge вернул слишком большой ответ.")
        body = raw.decode("utf-8")
        if body.lstrip().startswith("{"):
            result = json.loads(body)
        else:
            result = next((row for line in body.splitlines() if line.startswith("data:")
                           if (row := json.loads(line[5:])).get("id") == self.request_id), {})
        if result.get("id") != self.request_id or "result" not in result:
            raise NavigationError("Bridge не подтвердил MCP-запрос.")
        return result["result"]

    def call(self, name, arguments):
        reply = self.rpc("tools/call", {"name": name, "arguments": arguments})
        if reply.get("isError"):
            if arguments.get("op") == "edt_editor_form_preview" and any(
                block.get("text", "").endswith("IllegalStateException: Open a ready Form page first")
                for block in reply.get("content", [])
            ):
                raise FormPreviewLoading()
            raise NavigationError(f"Bridge отклонил {arguments.get('op', name)}. Проверьте открытый проект и диалоги в EDT.")
        return json.loads(next(block["text"] for block in reply["content"] if block["type"] == "text"))

    def contract(self, op):
        return self.call("edt.help", {"args": {"action": "contract", "op": op}})

    def operation(self, contract, args):
        return self.call(contract["entry"], {"op": contract["op"], "args": args})


def location(payload):
    if not isinstance(payload, dict):
        raise NavigationError("Codex не передал выбранный файл.")
    if payload.get("remotePath") or payload.get("remoteWorkspaceRoot"):
        raise NavigationError("Для открытия в EDT требуется локальный файл.")
    source = Path(payload["path"])
    if not source.is_absolute() or source.suffix.lower() not in SOURCE_TYPES:
        raise NavigationError("Выберите локальный файл EDT (.bsl, .form или .mdo).")
    source = source.resolve(strict=True)
    if not source.is_file() or source.suffix.lower() not in SOURCE_TYPES:
        raise NavigationError("Файл EDT не найден или имеет неподдерживаемый тип.")
    for root in source.parents:
        descriptor = root / ".project"
        if descriptor.is_file():
            relative = source.relative_to(root)
            if relative.parts[0] == "src":
                project = ET.parse(descriptor).getroot().findtext("name")
                if project and project.strip():
                    break
    else:
        raise NavigationError("В каталоге файла не найден проект EDT.")
    coordinates = payload.get("location")
    if coordinates is not None and not isinstance(coordinates, dict):
        raise NavigationError("Некорректная позиция в файле.")
    line = coordinates.get("line") if coordinates else None
    if line is not None and (type(line) is not int or line < 1):
        raise NavigationError("Некорректный номер строки.")
    return root, project.strip(), relative.as_posix(), line


def verify_legacy_project(bridge, root, project):
    lifecycle = bridge.contract("edt_project_lifecycle")
    waiter = bridge.contract("edt_operation_status")
    request = bridge.operation(lifecycle, {"project": project, "action": "status"})
    deadline = time.monotonic() + 20
    while time.monotonic() < deadline:
        status = bridge.operation(waiter, {"job_id": request["job_id"], "timeout_ms": 2000})
        if status.get("terminal"):
            break
    else:
        raise NavigationError("EDT не подтвердил состояние проекта.")
    registered = status.get("result", {})
    registered_path = registered.get("project_path")
    if not registered.get("open") or not registered_path or Path(registered_path).resolve() != root:
        raise NavigationError(f"Откройте в EDT проект {project} из каталога выбранного файла.")


def form_element_at(source, line):
    """Map an XML line to the innermost form item, without reading an EDT editor buffer."""
    parser = expat.ParserCreate(namespace_separator=" ")
    stack = []
    candidates = []

    def start(tag, _attributes):
        tag = tag.rsplit(" ", 1)[-1]
        item = tag in FORM_ITEMS and bool(stack) and (len(stack) == 1 or stack[-1]["item"])
        field = tag in {"name", "id"} and bool(stack) and stack[-1]["item"]
        stack.append({"tag": tag, "item": item, "start": parser.CurrentLineNumber,
                      "text": [] if field else None})

    def data(value):
        if stack and stack[-1]["text"] is not None:
            stack[-1]["text"].append(value)

    def end(_tag):
        entry = stack.pop()
        if entry["text"] is not None:
            stack[-1][entry["tag"]] = "".join(entry["text"]).strip()
        if entry["item"] and entry["start"] <= line <= parser.CurrentLineNumber:
            name, identifier = entry.get("name"), entry.get("id", "")
            if name and identifier.isascii() and identifier.isdecimal() and len(identifier) <= 10:
                item_id = int(identifier)
                if 0 < item_id <= 2147483647:
                    candidates.append((len(stack), name, item_id))

    def reject_doctype(*_args):
        raise ValueError("A form source must not contain a document type declaration.")

    parser.StartElementHandler = start
    parser.EndElementHandler = end
    parser.CharacterDataHandler = data
    parser.StartDoctypeDeclHandler = reject_doctype
    try:
        with source.open("rb") as stream:
            parser.ParseFile(stream)
    except (expat.ExpatError, OSError, ValueError):
        return None
    if not candidates:
        return None
    depth = max(candidate[0] for candidate in candidates)
    matches = [(name, item_id) for level, name, item_id in candidates if level == depth]
    return matches[0] if len(matches) == 1 else None


def select_form_element(bridge, editor, target):
    name, item_id = target
    editor_args = {"editor_id": editor["editor_id"]}
    if not any(tab.get("id") == FORM_PAGE and tab.get("active") for tab in editor["tabs"]):
        tab_contract = bridge.contract("edt_editor_select_tab")
        bridge.operation(tab_contract, {**editor_args, "tab_id": FORM_PAGE})
    preview_contract = bridge.contract("edt_editor_form_preview")
    selection_args = {**editor_args, "mode": "select", "element_id": item_id, "limit": 1}
    # EDT exposes the Form tab before its native renderer has finished initializing.
    transient = {"projection_unavailable", "native_rebuild_busy", "native_rebuild_not_ready"}
    for delay in (0, 0.25, 0.5, 1, 2):
        if delay:
            time.sleep(delay)
        try:
            selection = bridge.operation(preview_contract, selection_args)
        except FormPreviewLoading:
            continue
        if selection.get("reason") in transient:
            continue
        return selection.get("ready") and any(
            item.get("id") == item_id and item.get("name") == name
            for item in selection.get("selected", [])
        )
    return False


def open_source(bridge, payload):
    root, project, file_path, line = location(payload)
    source = root / file_path
    suffix = source.suffix.lower()
    contract = bridge.contract("edt_editor_open")
    properties = contract["contract"]["inputSchema"]["properties"]
    args = {"project": project, "file_path": file_path}
    if "expected_project_path" in properties:
        args["expected_project_path"] = str(root)
    else:
        verify_legacy_project(bridge, root, project)
    if suffix == ".bsl" and line is not None:
        args["line"] = line
    if suffix != ".bsl" and "activate_window" in properties:
        args["activate_window"] = True
    result = bridge.operation(contract, args)
    if suffix == ".bsl":
        if not result.get("ready") or (line is not None and result.get("selected_line") != line):
            raise NavigationError("EDT не подтвердил открытие модуля на нужной строке.")
        return result

    editor = result
    if not editor.get("ready") and editor.get("editor_id"):
        tabs = bridge.contract("edt_editor_tabs")
        deadline = time.monotonic() + 10
        while not editor.get("ready") and time.monotonic() < deadline:
            time.sleep(0.2)
            editor = bridge.operation(tabs, {"editor_id": result["editor_id"]})
    if not editor.get("ready"):
        raise NavigationError("EDT не подтвердил готовность редактора.")
    result = {**result, **editor}
    if suffix == ".form" and line is not None:
        target = form_element_at(source, line)
        result["form_element_state"] = "no_unique_element"
        if target is not None:
            result["form_element_state"] = "selection_unverified"
            try:
                if select_form_element(bridge, result, target):
                    result.update(form_element_state="selected", selected_element=target[0])
            except (NavigationError, OSError, http.client.HTTPException):
                # The native editor is open even if its preview cannot select an item.
                pass
    return result


def main():
    parser = argparse.ArgumentParser()
    parser.add_argument("--config", required=True, type=Path)
    parser.add_argument("--server", default="edt_bridge")
    parser.add_argument("target")
    args = parser.parse_args()
    try:
        result = open_source(Bridge(args.config, args.server), json.loads(args.target))
    except NavigationError as error:
        message = str(error)
    except (OSError, ValueError, KeyError, TypeError, StopIteration, ET.ParseError, http.client.HTTPException):
        message = "Не удалось открыть файл. Проверьте, что EDT запущен и локальное MCP-подключение работает."
    else:
        if sys.stdout is not None:
            print(json.dumps({key: result[key] for key in
                              ("ready", "selected_line", "form_element_state", "selected_element",
                               "navigation_state", "window_foreground", "window_restored") if key in result}))
        return 0
    if sys.stderr is not None:
        print(message, file=sys.stderr)
    elif sys.platform == "win32":
        ctypes.windll.user32.MessageBoxW(None, message, "Открыть в 1С:EDT", 0x30)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
