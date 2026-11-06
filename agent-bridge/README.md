# Самостоятельный EDT Agent Bridge

Поддерживаемая сборка не использует исходные JAR CodePilot, runtime-kernel, LangChain, LangGraph, встроенный чат или его CLI. Список исходников зафиксирован в `source-includes.txt`; удалённые компоненты доступны в истории Git. Исторические runtime/CLI/Maven-материалы не входят в этот продукт и не являются текущим путём сборки. Скрипты ниже компилируют весь включённый состав и используют штатный Eclipse p2 Publisher.

## Сборка Windows / PowerShell 7

Нужны Python 3.10+, установленная EDT 2026.1 и JDK 17. Для тестов дополнительно JUnit 4.13.2 и Hamcrest 2.2: задайте `JUNIT_JAR` и `HAMCREST_JAR`, если их нет в стандартных кешах p2/Maven текущего пользователя.

```powershell
$env:CODEPILOT_EDT_HOME = 'C:\Tools\1C_EDT\1cedt'
$env:JAVA_HOME = 'C:\Tools\jdk-17'
python tools/build-agent-bridge.py --qualifier 20261106development1
python tools/test-agent-bridge.py --build .runs/agent-bridge/20261106development1
python tools/package-agent-bridge.py --build .runs/agent-bridge/20261106development1
pwsh -File tools/test-managed-vanessa.ps1
```

Результат: два самостоятельных JAR и `edt-agent-bridge-<версия>-update-site.zip` в каталоге сборки. p2-пакет содержит только Bridge, без проприетарных SDK-библиотек. При сборке SDK берётся из вашей EDT; исходный CodePilot в classpath исключён.

`test-suite.txt` фиксирует принятый набор из 202 регрессионных тестов, включая проверку авторизации и отсутствия удалённых HTTP-маршрутов. Это не запуск всех исторических тестов исходного проекта: часть старых тестов ожидает прежний ручной fallback форм или действующий OSGi-контейнер. Полный исторический набор не заявлен как проходящий. Живая проверка в EDT обязательна отдельно.

## Установка

Публичный p2 update site: **https://nspection.github.io/edt-agent-bridge/**. В EDT откройте «Справка → Установить новое ПО → Добавить», вставьте этот адрес и выберите EDT Agent Bridge. Исходники и рабочая ветка публикуются отдельно: https://github.com/Nspection/edt-agent-bridge .


Сохраните работу и закройте EDT/EDT Start. В новой EDT пакет можно установить стандартно через «Справка → Установить новое ПО → Добавить → Архив».

Для существующего общего p2-профиля EDT Windows предусмотрена проверяемая замена на месте:

```powershell
# Только проверка p2-плана, без установки:
python tools/install-agent-bridge.py --build .runs/agent-bridge/20261106development1
# Реальная установка после закрытия EDT:
python tools/install-agent-bridge.py --build .runs/agent-bridge/20261106development1 --apply
```

Инсталлятор определяет существующий профиль из EDT, заменяет только feature CodePilot/Bridge, сохраняет timestamp профиля и `bundles.info`. При нестандартном профиле он отказывает, вместо выбора другой установки. Перезапустите EDT с `-clean` после обновления.

Откат этой установки после закрытия EDT:

```powershell
python tools/install-agent-bridge.py --build .runs/agent-bridge/20261106development1 --rollback --apply
```

Откат возвращает прежний p2-профиль и сохранённый `bundles.info`, в том числе прежний локальный override, если он был установлен до Bridge. Имена bundle и каталога настроек сохранены для миграции. Аккаунты LLM не импортируются и не используются; прежние учётные данные стороннего плагина автоматически не удаляются.

## Безопасность и рабочий цикл

MCP работает только на loopback с обязательным bearer-токеном. Входа в ChatGPT/CodePilot нет, OAuth-регистрация и удалённый веб-чат отсутствуют. Проверка `edt_bridge_status` показывает фактически загруженный продукт и собственные страницы настроек без выдачи токенов.

Профили Vanessa хранятся отдельно по проектам workspace. Они переживают обновление. `server_id` от прежнего универсального MCP-клиента не поддерживается; используйте явный локальный endpoint выделенного менеджера Vanessa. Транспортные защитные проверки сохранены.

Снимки предназначены для анализа; действия и отладка выполняются через API. Для условных точек используйте `set_breakpoint(condition=...)`, для исключений — `edt_exception_breakpoint(scope="workspace", ...)`. Точки по исключениям действуют на весь workspace; удаляйте только собственные точки по возвращённому ID.
