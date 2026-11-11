# Python для прямого TestClient

С 1.4.0.20261111zzzzzzzpython1 обычная команда edt.run op=edt_test_client action=prepare_environment сначала ищет стандартный CPython3.11–3.14 Windows x64 в PATH/реестре. Если его нет, получает закреплённый официальный пакет Python Software Foundation (CPython3.13.17 NuGet) в собственное хранилище workspace и создаёт managed venv с hash-locked wheels. Отдельный системный установщик больше не нужен. Официальный формат описан в https://docs.python.org/3.13/using/windows.html#the-nuget-org-packages.

Явный python_path сохраняет прежний выбор и не подменяется другим интерпретатором. Неподтверждённое завершение процесса проверки не приводит к скачиванию или повторному запуску. start по-прежнему использует готовое окружение; не устанавливает пакеты сам. Для единой prepare готовность к загрузке Python учитывается как доступный маршрут TestClient.

Настройки MCP → «Python / прямой TestClient» → «Подготовить Python и TestClient» используют тот же prepareJob, что агент. Статус и ошибки относятся к исходному заданию; сеть/процессы и проверка файлов выполняются вне UI.

```json
{"op":"edt_test_client","args":{"action":"environment_status"}}
{"op":"edt_test_client","args":{"action":"prepare_environment"}}
{"op":"edt_test_client","args":{"action":"runtime_status"}}
{"op":"edt_test_client","args":{"action":"prepare_runtime"}}
```

runtime_status/prepare_runtime проверяют/получают только собственный интерпретатор, не запускают 1С. prepare_environment дополнительно создаёт venv; job_id наблюдается через next_call/edt.task edt_operation_status. После ready запускать TestClient без python_path. cleanup_environment удаляет только подтверждённый собственный venv и отказывает для активных клиентов или изменённых файлов.

Пакет закреплён SHA256 архива и entrypoint; источники HTTPS ограничены. Zip-slip, дубликаты имён, лишние файлы, объём и число generations проверяются. Inventory каждый раз выводится из проверенного архива, а не доверяется редактируемому журналу. Исправление повреждённого runtime создаёт новую generation: прежний каталог сохраняется по прежнему пути, чтобы не подменять interpreter у существующего venv. После четырёх generations нужна проверка и уборка прежних файлов; автоматического широкого удаления нет.

Runtime хранится в LOCALAPPDATA/EDT-Agent-Bridge/runtime/<workspace_hash>/python, окружения — в прежнем py/<workspace_hash>. Это разные ресурсы. PATH, реестр, глобальные пакеты и Python пользователя не меняются. Служебная запись bytecode отключена, чтобы обычное создание venv не меняло проверенный runtime. Python и wheel-файлы загружаются по явной подготовке, не при каждом запуске EDT. Пакет содержит собственную лицензию PSF; он скачивается отдельно, не включён в p2 ZIP.

Проверка данного этапа: пакет → venv → закреплённые зависимости → повторное использование → уборка в собственной EDT. Запуск 1С/бизнес-сценария здесь не заявлен; защита базы и платформы сохраняется.
