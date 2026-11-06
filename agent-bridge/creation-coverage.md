# Матрица создания EDT Agent Bridge

Проверено 06.11.2026: EDT 2026.1, платформа модели 8.5.1, Bridge `1.4.0.20261106coverage3`. Проверка выполнялась через MCP в одноразовых проектах, без подключения или обновления информационных баз.

## Что проверено

103 сценария создания прошли. Выполнено 796 сравнений скалярных свойств с новой моделью из фабрики EDT: неожиданных отличий нет. Разобраны 53 XML-файла, повторяющихся UUID нет. Проверены классы ExtInfo всех 20 содержательных типов полей. `NONE` — служебное значение, не отдельный вид создаваемого поля.

Это проверка каркасов и значений модели, а не готовности пустой конфигурации к эксплуатации. Ссылки, containment, новые UUID и явные параметры теста учитываются отдельно. После фабрики штатно заполняются ссылки (`fillDefaultReferences`), поэтому пустые ссылки detached-объекта нельзя напрямую сравнивать с сохранённым объектом.

## Объекты верхнего уровня

| Тип `create_metadata` | Создание | Примечание |
|---|---|---|
| `Catalog` | created | Фабричные скалярные значения проверены. |
| `Document` | created | Фабричные скалярные значения проверены. |
| `InformationRegister` | created | Фабричные скалярные значения проверены. |
| `AccumulationRegister` | created | Нужны регистраторы. |
| `AccountingRegister` | created | Нужны ресурсы, регистраторы и прикладные связи. |
| `CalculationRegister` | created | Нужны план видов расчёта и регистраторы. |
| `CommonModule` | created | Фабричные флаги совпали; требуется выбор контекстов выполнения под проект (диагностика SU200). |
| `CommonAttribute` | created | Фабричные скалярные значения проверены. |
| `Enum` | created | Фабричные скалярные значения проверены. |
| `Report` | created | Фабричные скалярные значения проверены. |
| `DataProcessor` | created | Фабричные скалярные значения проверены. |
| `Constant` | created | Фабричные скалярные значения проверены. |
| `CommandGroup` | created | Фабричные скалярные значения проверены. |
| `Interface` | created | Фабричные скалярные значения проверены. |
| `Language` | created | Встроен в Configuration.mdo; явный languageCode=en; исправлены BM relink и лишняя content-ссылка. |
| `Style` | created | Фабричные скалярные значения проверены. |
| `StyleItem` | created | Фабричные скалярные значения проверены. |
| `SessionParameter` | created | Требуется явный прикладной тип. |
| `SettingsStorage` | created | Фабричные скалярные значения проверены. |
| `XDTOPackage` | created | Фабричные скалярные значения проверены. |
| `WSReference` | created | Нужен locationURL. |
| `Role` | created | Фабричные скалярные значения проверены. |
| `Subsystem` | created | Фабричные скалярные значения проверены. |
| `ExchangePlan` | created | Фабричные скалярные значения проверены. |
| `ChartOfAccounts` | created | Фабричные скалярные значения проверены. |
| `ChartOfCharacteristicTypes` | created | Фабричные скалярные значения проверены. |
| `ChartOfCalculationTypes` | created | Фабричные скалярные значения проверены. |
| `BusinessProcess` | created | Нужна связь с задачей. |
| `Task` | created | Фабричные скалярные значения проверены. |
| `CommonForm` | created | Исправлено: содержимое Form.form генерируется штатным IFormGenerator; редактор открыт и проверен снимком. |
| `CommonCommand` | created | Фабричные скалярные значения проверены. |
| `CommonTemplate` | created | Создан дескриптор; содержимое и редактор макета отдельно не проверялись. |
| `CommonPicture` | created | Фабричные скалярные значения проверены. |
| `ScheduledJob` | created | Нужен methodName. |
| `FilterCriterion` | created | Фабричные скалярные значения проверены. |
| `DefinedType` | created | Фабричные скалярные значения проверены. |
| `Sequence` | created | Нужны участвующие документы. |
| `DocumentJournal` | created | Нужны регистрируемые документы. |
| `DocumentNumerator` | created | Фабричные скалярные значения проверены. |
| `EventSubscription` | created | Нужны event и handler. |
| `FunctionalOption` | created | Нужно хранилище значения. |
| `FunctionalOptionsParameter` | created | Нужно использование параметра. |
| `WebService` | created | Нужно пространство имён. |
| `HTTPService` | created | Нужен прикладной rootURL. |
| `ExternalDataSource` | created | Фабричные скалярные значения проверены. |
| `IntegrationService` | created | Фабричные скалярные значения проверены. |
| `Bot` | created | Фабричные скалярные значения проверены. |
| `WebSocketClient` | created | Фабричные скалярные значения проверены. |

## Дочерние объекты

| Вид | Проверенный родитель | Результат |
|---|---|---|
| `Attribute` | `Catalog` | created |
| `Tabular_Section` | `Document` | created |
| `Command` | `Catalog` | created |
| `Form` | `Catalog` | created |
| `Template` | `DataProcessor` | created |
| `Dimension` | `InformationRegister` | created |
| `Resource` | `AccumulationRegister` | created |
| `Requisite` | `InformationRegister` | created (EDT Attribute, исправлен псевдоним) |
| `EnumValue` | `Enum` | created |
| `URLTemplate` | `HTTPService` | created |
| `HTTPMethod` | `HTTPService → URLTemplate` | created |

## Формы и макеты

| Группа | Проверенное покрытие | Ограничение |
|---|---|---|
| Роли форм | OBJECT, LIST, CHOICE, AUXILIARY | Проверены у Catalog, не у каждого возможного владельца |
| Реквизиты формы | String(30), Boolean, Number(15,3), Date, ValueTable с колонками | Не все типы данных платформы и не все их свойства |
| Группы | USUAL_GROUP, PAGES, PAGE, COMMAND_BAR, BUTTON_GROUP, POPUP, COLUMN_GROUP | PAGE внутри PAGES; BUTTON_GROUP/POPUP внутри COMMAND_BAR; COLUMN_GROUP внутри Table |
| AUTO_COMMAND_BAR | Автоматически создан EDT внутри формы/таблицы | Прямое add_group отклоняется EDT; использовать существующую командную панель |
| Таблица | ValueTable, автоматические колонки Label/Quantity | ValueTree и все виды таблиц этим прогоном не проверялись |
| Команды/кнопки | FormCommand, USUAL_BUTTON, HYPERLINK, COMMAND_BAR_BUTTON, COMMAND_BAR_HYPERLINK | Обработчик создан; выполнение BSL в базе не проверялось |
| Макеты | spreadsheet, html, text, binary, dcs, active_document | Для spreadsheet создаётся MXL; для остальных проверен дескриптор. Полное содержимое не заявлено |

Проверенные поля: `INPUT_FIELD`, `LABEL_FIELD`, `CHECK_BOX_FIELD`, `RADIO_BUTTON_FIELD`, `PICTURE_FIELD`, `HTML_DOCUMENT_FIELD`, `TEXT_DOCUMENT_FIELD`, `SPREADSHEET_DOCUMENT_FIELD`, `CHART_FIELD`, `GANTT_CHART_FIELD`, `PROGRESS_BAR_FIELD`, `TRACK_BAR_FIELD`, `CALENDAR_FIELD`, `PERIOD_FIELD`, `PDF_DOCUMENT_FIELD`, `GEOGRAPHICAL_SCHEMA_FIELD`, `GRAPHICAL_SCHEMA_FIELD`, `DENDROGRAM_FIELD`, `FORMATTED_DOCUMENT_FIELD`, `PLANNER_FIELD`.

## Повторное чтение и уборка

После штатного перезапуска EDT повторно прочитаны 59 объектов (48 верхнего уровня и 11 дочерних): отличий скалярных свойств нет, полный результат inspect_form_layout совпал. Общая форма и тестовая форма открыты штатными редакторами, MCP-снимки просмотрены. Три проекта BridgeCreationAudit, BridgeCreationVerified и BridgeCreationFinal удалены через API workspace EDT и с диска; отчёты сохранены вне каталогов проектов. Постоянный стенд Vanessa сохранён.

## Исправления

- Language больше не прикрепляется как самостоятельный BM top object и не добавляет битую ссылку в Configuration.content; сохранение проверяется по встроенному XML-определению.
- Requisite нормализуется в Attribute; исторический псевдоним Requisite у Enum по-прежнему означает EnumValue.
- Общая форма получает реальное содержимое через штатный генератор EDT.
- HTML/text/binary/active_document макеты больше не получают ложный бинарный Template.mxl. Их содержимое создаётся соответствующими редакторами/сервисами, а не подменяется таблицей.
- Инспектор defaults показывает URI неразрешённой ссылки вместо ложных нулевых свойств proxy; null сериализуется явно.

## Воспроизведение

```powershell
python tools/audit-edt-creation.py --project BridgeCreationAuditRun --output .runs/creation-audit/run --apply
# После штатного перезапуска EDT:
python tools/audit-edt-creation.py --project BridgeCreationAuditRun --output .runs/creation-audit/run --verify-only
```

Для скрипта аудита нужен Python 3.11+ (tomllib). Без `--apply` команда показывает план. Повторный запуск не перезаписывает проект. Подключение по умолчанию `edt_bridge`; токен читается только из локального config.toml. Подробные локальные результаты — audit.json, они не входят в Git. Сводка без локальных путей — [creation-coverage.json](creation-coverage.json).

Для уборки предусмотрена `edt_creation_audit_cleanup`: по умолчанию dry_run, затем явный dry_run=false и проверка edt_operation_status. Она принимает только BridgeCreation-проекты внутри .runs/creation-audit/.../project с маркером аудита, точным путём и совпадающим UUID конфигурации. Обычные проекты и базы не удаляет.

## Границы доказательств

Набор standalone-регрессий: 208 тестов. Все исторические тесты исходного CodePilot не запускались. Полный перебор всех комбинаций свойств, владельцев, расширений, внешних обработок/отчётов и конструкторов СКД не выполнен. Пустые метаданные с обязательными прикладными ссылками закономерно имеют диагностику EDT; она сохранена в локальном отчёте. Отсутствие XML-свойства само по себе не считается ошибкой.
