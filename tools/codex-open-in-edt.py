"""Open a local BSL location from the Codex Open in menu through EDT Agent Bridge."""
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


class NavigationError(Exception):
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
    if not source.is_absolute() or source.suffix.lower() != ".bsl":
        raise NavigationError("Выберите локальный модуль BSL.")
    source = source.resolve(strict=True)
    if not source.is_file():
        raise NavigationError("Файл модуля не найден.")
    for root in source.parents:
        descriptor = root / ".project"
        if descriptor.is_file():
            relative = source.relative_to(root)
            if relative.parts[0] == "src":
                project = ET.parse(descriptor).getroot().findtext("name")
                if project and project.strip():
                    break
    else:
        raise NavigationError("В каталоге модуля не найден проект EDT.")
    coordinates = payload.get("location")
    if coordinates is not None and not isinstance(coordinates, dict):
        raise NavigationError("Некорректная позиция в модуле.")
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


def open_source(bridge, payload):
    root, project, file_path, line = location(payload)
    contract = bridge.contract("edt_editor_open")
    args = {"project": project, "file_path": file_path}
    if "expected_project_path" in contract["contract"]["inputSchema"]["properties"]:
        args["expected_project_path"] = str(root)
    else:
        verify_legacy_project(bridge, root, project)
    if line is not None:
        args["line"] = line
    result = bridge.operation(contract, args)
    if not result.get("ready") or (line is not None and result.get("selected_line") != line):
        raise NavigationError("EDT не подтвердил открытие модуля на нужной строке.")
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
        message = "Не удалось открыть модуль. Проверьте, что EDT запущен и локальное MCP-подключение работает."
    else:
        if sys.stdout is not None:
            print(json.dumps({key: result[key] for key in
                              ("ready", "selected_line", "navigation_state", "window_foreground", "window_restored") if key in result}))
        return 0
    if sys.stderr is not None:
        print(message, file=sys.stderr)
    elif sys.platform == "win32":
        ctypes.windll.user32.MessageBoxW(None, message, "Открыть в 1С:EDT", 0x30)
    return 1


if __name__ == "__main__":
    raise SystemExit(main())
