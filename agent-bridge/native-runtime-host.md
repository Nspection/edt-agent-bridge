# Native runtime host без расширения

`edt_runtime_host` добавляет второй транспорт исполнения BSL, независимый от Vanessa и VAExtension. Команды старого `edt_runtime_execute` продолжают работать прежним способом. Native host не заменяет механизм тестирования произвольных элементов интерфейса.

## Подготовка и режимы

Нужны Windows x64, разрешённая тестовая база, её корректная привязка к базовому проекту EDT и доступ в EDT. Команда использует установленную платформу и сохранённые параметры доступа EDT, не принимает пароль в собственных аргументах. Если проект ещё загружается, дождаться готовности модели. Native ZIP автоматически получается и проверяется общим сервисом плагина, если не указана явная пара native_path/expected_native_sha256. [Автоподготовка и MCP](native-package.md). Получать точную схему установленного инструмента через `edt_capabilities`.

```json
{"action":"prepare","project":"TestConfiguration","mode":"external"}
```

`prepare` возвращает `job_id`; `edt_operation_status` после DONE содержит `host_id`. `mode=external` создаёт отдельный внешний проект и EPF штатными фабрикой и dumper EDT, вне Git. Целевая конфигурация не изменяется.

`mode=metadata` создаёт только внутреннюю обработку с уникальным именем в основном проекте. **Первый start выполняет полное обновление выбранной базы из проекта: это включает все текущие изменения проекта, а не только служебную обработку.** Использовать только в разрешённой для этого базе. Метаданные создаются фабрикой EDT с её defaults; модули и событие формы — через модель/ресурсы EDT. Стартовые модули конфигурации не изменяются. Новые metadata-host (workflow12) открывают внутреннюю обработку напрямую через навигационную ссылку EDT runtime. Внешний проект и bootstrap EPF не создаются. Старые host сохраняют прежний маршрут совместимости.

```json
{"action":"start","host_id":"<host_id>"}
```

Дождаться DONE фоновой операции, затем опрашивать `action=status`. Исполнитель готов при `state=running` и `channel.connected=true`; возвращаются идентичность сеанса и этап `startup_phase`. Новый собственный клиент запускается через EDT runtime/access API. Имеющиеся пользовательские клиенты не используются и не закрываются. При вопросе первого доверия к EPF готовность не наступает: смотреть окно через `edt_process_snapshot`. Команда сама не отключает защиту базы или Windows. Точечная подготовка доверия относится к локальному окружению, а не к выдаче прав через BSL.

Компонента передаётся через временное хранилище с владельцем-формой, без InstallAddIn/COM-регистрации и постоянной установки в ExtCompT. У неё нет универсального доступа к объектной модели: BSL выполняет форма в потоке 1С, серверный BSL — её серверный метод. Poll выполняется раз в секунду; на остановленном отладчиком сеансе команды могут ждать продолжения. Отладчик остаётся отдельным каналом Bridge.

## Исполнение

```json
{"action":"execute","host_id":"<host_id>","idempotency_key":"task-probe-001","execution_mode":"execute","context":"server","parameters":{"amount":41},"code":"Результат = ПараметрыВызова.amount + 1;"}
```

Ответ содержит `request_id`; получить результат через `action=result` с host_id/request_id. Успех — `terminal=true`, `state=completed`, `execution.success=true`. Ошибка BSL возвращается как `state=failed` с текстом/стеком, а не успехом транспорта. Поддержаны execution_mode `info`, `execute`, `query` и серверный вызов экспортной функции EPF/ERF `external`, как у `edt_runtime_execute`. Функция EPF/ERF принимает один аргумент parameters. Код/параметры передаются JSON, без подстановок Vanessa; параметры доступны как `ПараметрыВызова`, результат присваивается `Результат`.

Ограничения: суммарное задание до 128 KiB UTF-8, ответ транспорта до 2 MiB, не более 128 заданий на host, включая задания до перезапуска. Поэтому транспортируемая EPF/ERF может упереться в лимит задания раньше общего лимита файла старого runtime API. query limit ограничивает выдачу, а не работу запроса. Результат должен поддерживать JSON-сериализацию. Это контекст собственной формы исполнителя, а не доступ к локальным переменным произвольной формы пользователя.

Ключ идемпотентности действует **в пределах host_id**. Команда сохраняет claim до передачи BSL и не отправляет его повторно при потере ответа. После перезапуска неопределённый исход отражается как `outcome_unknown`, без автоматического повтора. Создание нового host не отменяет уже выполненные записи. Не повторять неизвестную изменяющую операцию через новую сессию без проверки её результата.

## Остановка и полная уборка

`action=stop` завершает только собственный клиент; невыданные задания становятся `cancelled`, выданные без ответа — `outcome_unknown` (оба состояния терминальные). Отмена сохраняет свой статус после перезапуска EDT; отмена не является откатом деловых данных. `action=cleanup` — фоновая операция:

1. Остановить собственный клиент и проверить неизменность привязки базы, если требуется её обновление.
2. Проверить исходники по хешам. Правки пользователя или новые неизвестные файлы блокируют удаление и сохраняются для просмотра.
3. В metadata-режиме удалить объект native-рефакторингом EDT. Журнал пофайловых хешей позволяет продолжить после частичного удаления; оставшиеся файлы проверяются повторно и удаляются ресурсами Eclipse под правилом планирования проекта; после подтверждения отсутствия модели допускается удалить только совпадающие с журналом файловые остатки.
4. Выполнить FULL reload и проверить EQUAL. Для новых внутренних host получить CF и XML-инвентарь в одной native Designer-сессии с одинаковым поколением до/после экспорта; сравнить этот неизменяемый CF с DBConfiguration штатным Designer CompareCfg. Только известный отчёт без различий и отсутствие обработки в инвентаре подтверждают уборку. Внешняя EPF не нужна. Старые host сохраняют EPF-верификацию. Удаление исходников само по себе не считается удалением из базы.
5. Если внешний проект существует, остановить контекст внешнего проекта через IWorkspaceOrchestrator и дождаться остановки, затем удалить собственный внешний проект, EPF, ZIP, payload/result, журнал клиента и секретный session manifest. Остаётся неисполняемый ownership tombstone без BSL/параметров/токенов.

Успех metadata-уборки: DONE, `cleaned=true`, `source_removed=true`, `database_verified_absent=true`. При прерывании повторить cleanup того же host_id; не создавать замену для скрытия незавершённой уборки. Если хеши не совпали, сначала просмотреть изменения. Уборка host не удаляет базовый проект, саму тестовую ИБ или чужие записи запуска: ими управляет отдельный lifecycle проекта/ИБ.

## Пакеты релиза

Native API исходники: `native/agent-host`, сборка: `tools/build-native-host.py`. К GitHub Release можно приложить `agent-host-native-windows-x64.zip`, agent-host-package.json/SHA256SUMS.txt и собранный `AgentHost.epf`; бинарники не должны попадать в Git рабочих конфигураций. EPF можно собрать для текущего базового проекта самим prepare через native EDT. Лицензии и происхождение SDK-заголовков указаны в THIRD_PARTY_NOTICES.md.

[Проверка релиза и ограничения](native-runtime-host-validation.md).


## Recovery and cleanup on 8.5

The BSL template consumes and validates Configure/Hello/Complete/Stop return values. It installs its polling timer only after a verified Hello. The channel identity includes the actual managed-form name, so metadata mode can be distinguished from the external bootstrap at runtime. Three consecutive transport failures stop polling and expose startup_phase=transport_failed; execute refuses hosts which are not running.

Cleanup checks both the Eclipse project registration and EDT project models independently of directory existence, including old cleaned tombstones. Status exposes external_registration_present and external_files_present. A missing directory is not proof that the project registration was removed.

`edt_runtime_host(action=cleanup_external_project, host_id=...)` removes only that host's external project and its unchanged owned sources. It stops the owned client, does not update the base and does not claim database absence or full cleanup. The compiled verifier EPF and Native ZIP remain available for pending metadata cleanup. This action is suitable for external project remnants after start or verification failures.

After source removal and a successful FULL/EQUAL reload, cleanup persists database_removal_synchronized. A verifier retry with that receipt and a currently synchronized base does not repeat FULL. It still executes an independent metadata lookup before setting database_verified_absent. A failed verifier is stopped in finally; unknown database verification remains unknown. Base identity, source hashes and request claims are preserved.

On 8.5, allowing an EPF to open and allowing that EPF to connect a binary component are separate platform permission requests. Metadata execution uses the internal form; the existing post-removal verifier uses an external EPF. A permission prompt can therefore block verification after the internal execution succeeded. Read startup_phase and capture the exact owned client before diagnosing a transport failure. Do not automate permission prompts or disable protection globally.

Private EDT instances used alongside a user's EDT must have a separate APPDATA at process startup. The EDT 2026.1 SDK stores synchronization flows under APPDATA/.1cedt/ib-sync/flows and uses a process-local integer counter. Workspace separation alone does not isolate this directory. This is an SDK collision risk; the original missing-flow incident has no trace identifying the deleting process. Never blindly repeat a failed FULL reload.


A pending metadata cleanup can pass `verification_host_id` to reuse another already running native host on the same synchronized base. The service checks UUID and connection identity, verifies that the removed source has not reappeared, submits a read-only metadata-absence query and records its request ID. It never FULL-reloads or stops that verifier. Unknown, failed, malformed or merely queued responses cannot certify cleanup. Both host locks are acquired in consistent order. This route also recovers old hosts whose compiled verifier EPF predates the acknowledgement fix.

The borrowed-verifier absence request uses a stable key within that verifier host. Retrying with the same verifier inspects the original request rather than issuing another lookup. If that request already ended with failure or false, resolve the cause and use ordinary cleanup or a different verified host; do not hide an unknown mutating outcome by replacing its key.


## Workflow12: запуск, пакеты и снимки

Первый metadata-start обновляет весь проект. Повторный запуск ранее загруженного неизменённого host при текущем native EQUAL пропускает обновление (`base_update_skipped=true`). Это не разрешение применять чужие изменения. Подробности пакетов, ограничений снимков и восстановления синхронизации: [workflow12.md](workflow12.md).

[Native: ожидания, проверки, выборочное чтение и частичные результаты](native-batch-controls.md).
