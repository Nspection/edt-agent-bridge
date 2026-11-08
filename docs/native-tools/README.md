# Native EDT development tools for CodePilot1C

Ветка подготовлена от `ondysss/codepilot1c-edt` commit `846b37eafa6fa0b41ca5b9c3729148114c10e384`. Источник переносимых возможностей: `Nspection/edt-agent-bridge` commit `b442420db2dd0f3eb918c7c636ff9ae4a9277ea2`.

## Цель

Дать агентам внутри чатов CodePilot те же native-инструменты EDT, которые доступны внешним агентам через Bridge MCP. CodePilot сохраняет свои чаты, модели, аккаунты, авторизацию, профили, GSD, разрешения и сборку. Отдельный Bridge рядом устанавливать не нужно: это ветка исходного CodePilot, не замена его продуктом Bridge.

## Что перенесено

- Типовое создание/удаление, фабричные defaults, свойства метаданных, формы, реквизиты, макеты и предопределённые данные.
- Открытие редакторов, вкладки, прокрутка, предпросмотр, снимки, управление окном.
- Обычные/условные точки, исключения, стек, переменные, шаги и запуск отладки.
- Native compare/merge, план для пользователя, продолжение после ручных правок, визуальное объединение табличных макетов и shared UI/MCP-план.
- Создание файловых/серверных ИБ, DT, привязка приложения, импорт из приложения и FULL-обновление.
- Подготовленные профили Vanessa, управляемый сценарий, состояния/контрольные точки; короткие команды наблюдения/действия в форме.
- Внешние проекты, сборка EPF/ERF, native host external/metadata, BSL/запросы/вызовы функций.
- Обзор изменений edt_check, связанные тестовые данные с ручным/автоматическим шагом и защищённой пометкой удаления, единый отчёт задачи.

51 новый инструмент зарегистрирован через существующий `com.codepilot1c.core.toolProvider`; исходный ToolRegistry не менялся. Чат и MCP используют общий реестр и прежний execution/permission pipeline. `NativeToolsChatRegistryTest` проверяет видимость новых команд в этом реестре. Реальная доступность конкретному агенту по-прежнему определяется профилем/правами, а готовность базы и Vanessa — текущим окружением.

## Что намеренно сохранено

README, имена/версии bundle, весь runtime kernel/agent, модели и провайдеры, учётные записи и OAuth/LLM/remote-web MCP маршруты, профили/GSD, чат и completion UI, исходные startup/activator, настройки и Maven/Tycho-проекты. Никаких массовых удалений или миграции/закрытия прежних панелей Bridge. Две новые UI contributions: startup табличного сравнения и его редактор. В manifests добавлены только SDK imports/exports, исходные зависимости остаются.

## Общие файлы, которые приходится менять

EDT-адаптеры debug/external/forms/metadata/runtime и несколько существующих tool wrappers получают native-операции. BackgroundJobRegistry дополнен прогрессом и безопасным lifecycle. В ToolExecutionService меняются только редактирование журналов/trace, сохраняются GSD gate, context routing и права. MCP JSON decoding сохраняет точность чисел; router сохраняет продуктовую идентичность/политику CodePilot и скрывает sensitive trace. HTTP transport сохраняет OAuth, LLM broker и remote web controller; меняется только числовой JSON decoder.

Контроль сохранности: `python tools/check-native-contribution.py`. Он сравнивает защищённые механизмы с исходным commit, проверяет отсутствие удалений, сохранение исходных XML contributions и manifest contracts.

## Сборка и проверки

Используется существующий Maven/Tycho-процесс автора (pom не менялись). Новые resources/agent-host и scripts включены в core build.properties. Native-компонента необязательна для чата/большинства EDT-команд; её исходники и MIT notices интерфейсных заголовков находятся в native/agent-host, сборка — tools/build-native-host.py. Без готового host исполняемый BSL не запускается сам.

Проверки этой ветки и ограничения фиксируются в VALIDATION.md. Публикация p2-пакета и установка в рабочую EDT пользователя в рамках подготовки ветки не выполняются. Живой end-to-end запрос через LLM-чат CodePilot, полная Tycho/CI-сборка и авторская release pipeline должны быть отдельно подтверждены перед upstream-релизом; успешная Java-компиляция не заменяет их.

## Материалы возможностей

[EDT Agent Bridge source](https://github.com/Nspection/edt-agent-bridge/tree/b442420db2dd0f3eb918c7c636ff9ae4a9277ea2), [обзор изменений](https://github.com/Nspection/edt-agent-bridge/blob/b442420db2dd0f3eb918c7c636ff9ae4a9277ea2/agent-bridge/quick-workflows.md), [тестовые данные](https://github.com/Nspection/edt-agent-bridge/blob/b442420db2dd0f3eb918c7c636ff9ae4a9277ea2/agent-bridge/test-data-ledger.md), [UI формы](https://github.com/Nspection/edt-agent-bridge/blob/b442420db2dd0f3eb918c7c636ff9ae4a9277ea2/agent-bridge/runtime-form-tools.md), [native host](https://github.com/Nspection/edt-agent-bridge/blob/b442420db2dd0f3eb918c7c636ff9ae4a9277ea2/agent-bridge/native-runtime-host.md).

История и лицензия CodePilot сохранены; адаптированный сторонний код и SDK-заголовки описаны в THIRD_PARTY_NOTICES.md. PR/CLA от имени пользователя автоматически не отправляются.
