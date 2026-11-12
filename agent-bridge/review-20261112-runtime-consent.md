# Приёмка runtime consent, native merge и lifecycle — 12.11.2026

Кандидат `1.4.0.20261112zzzzzzzzconsent11`. Положительные runtime-сценарии проверены на consent6; новые merge/lifecycle/readiness и финальный пакет — на consent7/11, с неизменным кодом проверенного startup/consent. Работа только в собственной EDT/маркированной TMS_TEST_AGENT и одноразовых проектах; Windows x64, 1С 8.5.1.1522, compare SDK 28. Пользовательская EDT, TMS_FILE85, порт отладки 1552, исходники и ресурсы соседней задачи не использовались.

## Ревью

Своя проверка и независимое ревью выполнены до публикации. Исправлены замечания о ревизии разрешения, неизвестном исходе input, генерации HWND, самостоятельном scope стартового вопроса, continuation только при доказанном отсутствии input, единственном TC handshake, no-replay resume aggregate, lifecycle dirty/busy guards, sleeping build jobs, OSGi Debug import и передаче pre-reservation proof в compact. Открытых подтверждённых P1/P2 нет. Независимый рецензент отдельно повторил 128 целевых тестов; финальную дельту проверил отдельно.

## Проверенные сценарии

Отсутствие разрешения не запускает процесс. Once допускает исходный сеанс, повторный start без нового разрешения блокируется. Remember и revoke проверены; после отзыва наблюдение сохранено, новые действия запрещены. Новая ревизия не возобновляет прежний lease.

В собственной Native external последовательно проверены реальный EPF trust, отдельный вопрос Native-компоненты, exact allow, отказ Windows в foreground без input и ограниченное продолжение тем же ключом после активации только собственного окна. Дерево/хеш вопроса стабильны между помощниками, temp-storage URI не публикуется. После resume канал подключён; info завершён terminal=true/state=completed/execution_success=true. Host очищен штатным cleanup.

Реальный TestClient once стартовал; наблюдение прошло. Startup route после готовности отвергается. Revoke оставляет чтение и блокирует mutation. Unified UI остановился до первого действия на awaiting_consent, затем исходные prepare.resume/ui.resume завершили единственный read-only wait-пакет: test_passed=true/cleanup_verified=true. Прежний Python сохранён. Реального TC security prompt в среде не было; автоматический ответ для него не заявлен.

Лог пользователя подтвердил No store 255 из virtual nodeId=-1 и прямой fatal Comparison exceeds spreadsheet validation limit. Регрессии исключают BM lookup virtual row, обход children/native changed-index, проверяют restore callback session, stale ID, изоляцию сессий, lifecycle состояния модели. В двух одноразовых проектах фактически выполнены GET_FROM_OTHER и CUSTOM_MERGE; сохранённые MXLX проверены. Связь остальных BM deadlock с плагином не доказана.

В финальной consent11 фактически проверены close/open/reopen/CLEAN_BUILD+FULL_BUILD своего проекта. Close удалил только целевой чистый редактор; редактор второго проекта сохранился. Повтор прежнего close-key после нового open вернул журнал и не закрыл проект заново. Хеши всех src-файлов обоих проектов неизменны. Native prepare на закрытом одноразовом проекте вернул not_started/retry_safe=true/outcome_unknown=false; до/после совпали host-каталоги и workspace-регистрации. QA steps_search отработал без прежней ошибки resource not found; отдельный каталог установленной Vanessa в минимальной QA-конфигурации не задан, поэтому наличие её шагов не заявляется.

Native pre-allocation classification ограничена первым ensureReady до host reservation. Shared Native ZIP может быть подготовлен ранее; отсутствие host не выдаётся за отсутствие shared cache. READY/AFTER_BUILD с пустыми active segments не становится ready: SDK isIdle принимает только BLOCKED/EMPTY. Поздние ошибки подготовки, запуска и refresh не повышаются до безопасного повтора.

## Тесты, пакет и границы

1107 Java + 126 Python регрессий и standalone product boundary. Проверяются реальные JAR: QA steps catalog присутствует и читается, Python cache не поставляется. Native DLL/протокол 2, host template и pinned vendor не менялись. Каталог QA был подтверждённо пропущен standalone builder и добавлен адресно. Сообщение об отсутствии PowerShell 7 в пользовательской EDT не обходилось использованием приватной копии Codex; для managed Vanessa нужен доступный pwsh, для нового маршрута страниц TestClient Vanessa не требуется.

Производственный preflight wire-timeout из соседней задачи: данные указывают not_dispatched и broken transport, без отправки первого действия. Причина зависания не воспроизведена; автоматическое переподключение, replay и ослабление барьеров не добавлялись. Этот случай не объявлен исправленным.

Собственные временные Native/TestClient очищены штатными командами; оба одноразовых проекта закрыты. Их исходники оставлены в собственной маркированной среде для регресса. TMS_TEST_AGENT и ранее отмеченная запись сохранены как прежняя разрешённая среда. Собственная EDT остановлена с проверкой PID и argfile. Локальный p2 resolver на отдельной копии профиля: verifyOnly exit 0; пользовательская установка не обновлялась. Публичные хеши, GitHub release assets и resolver обычного публичного p2 URL проверяются после публикации в отдельном release evidence.

Локальные evidence: `.runs/runtime-consent/live6.json`, `merge-live.json`, `tests11.log`, `package11.log`; публикация и p2-проверки — `.runs/release-runtime-consent`. В evidence не сохраняются токены и аргументы Java с секретами.
