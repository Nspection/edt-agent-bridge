# EDT Agent Bridge 1.4.0.20261113reports2

Исправлены последние сообщения об ошибках подготовки runtime и устаревшем Native `next_call`. Включает [багрепорты в GitHub и native preview из reports1](release-20261113-reports-preview.md).

- `timeout_ms` относится только к `action=wait`; `readiness_timeout_s` — к `prepare`. Общий контракт теперь задаёт схему по действиям и validation в router/tool/service. Ошибка содержит `rejected_field`, `action` и `allowed_fields`; значения аргументов не выводятся. Ошибочное поле отклоняется до dependency workspace и claim; исправленный payload может использовать тот же ключ.
- Runtime preparation использует ту же модель ownership leases, что UI-run. Поздний finally предыдущей подготовки не может снять активность возобновлённой. Отказ очереди или записи нового receipt не допускает исполнение resume и не оставляет вечный active-флаг.
- Native может подключиться между выдачей `startup_inspect` и вызовом. Read-only inspection теперь возвращает свежий статус того же host. Обрабатывается только typed phase-ended; ошибки базы, процесса и согласия не скрываются. `startup_answer` сохраняет прежние строгие startup-only проверки и не отвечает на диалог автоматически.

Отдельный FULL-update уже доступен через `edt_diagnostics(command=update_infobase, project_name=..., full_reload=true, async=true)`, с предварительным `dry_run=true` и статусом `update_infobase_status(job_id)`. Vanessa/metadata host не нужны. Это описание существующего маршрута, базы этим релизом не обновлялись.

`expected_window` для direct `open` и первого шага `edt_ui_run` — текущий заголовок до открытия. После открытия проверяется `expect.window`, `expect.fields` или `expect.elements`; при неизвестном заголовке цели нужен иной проверяемый признак.

Проверки: 1230 Java-тестов, 179 Python-тестов, 33 PowerShell-контракта контроллера, 3 CLI smoke и 13 startup-контрактов; OSGi, standalone boundary и source/artifact provenance. Новые регрессии охватывают schema/dispatch agreement, canonical/compact wire `rejected_field` до workspace, ключ без claim, отказ resume и Native hello между status/inspect. Собственное и независимое ревью выполнены.

Живые editor/report классы соответствуют ранее проверенным bytes собственной EDT из reports1. Новые preparation/startup-race сценарии проверены на fake backend и wire fixtures без запуска ИБ. OAuth/реальный GitHub Issue POST и живой 1С 8.3.27 в этом релизе не проверялись. Пользовательская EDT, базы и x5 не изменялись.
