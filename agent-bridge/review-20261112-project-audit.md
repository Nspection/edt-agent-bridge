# Финальное ревью EDT Agent Bridge — 12–13.11.2026

Кандидат: **1.4.0.20261113audit1**, база main4ea53f553c4ed9853bdfe83bf949eee6c80fe67a. Ревью охватывает активный standalone продукт и его build/install/release tools; исторические удалённые LLM/чат-компоненты не входят в поставку.

## Рецензенты и итог

Собственное ревью выполнено совместно с отдельным независимым рецензентом. По дополнительному поручению пользователя gpt-6-astra с reasoning_effort=medium провела повторный полный архитектурный аудит. Он нашёл пропущенные замечания; прежний вывод audit28 о полноте пересмотрен. Изменения Astra в зависимостях независимо проверил второй рецензент; Astra проверила мой Native/MCP код и его пакет/установщик. Новый PowerShell core и harness прошли независимое чтение и сверку SHA до первого запуска. В финальных проверенных изменениях подтверждённых открытых P1/P2 не осталось.

## Исправления после архитектурного ревью

| Граница | Итоговое поведение |
|---|---|
| Native idempotency/history | Исходный ключ читается до текущих readiness/consent gates. После stop/revoke возвращается прежний request/result; утрата или повреждение известного claim сохраняет unknown и запрет повтора. Key/UUID/digest связаны; результат другого ключа не подставляется. Допустимый полный durable receipt не ограничен ошибочно размером одного HTTP-сообщения. |
| Native preflight/send | Общий неизменяемый NativeExecutionPlan проверяется до task claim. Observational preflight отделён от durable submit; только доказанный отказ до отправки получает not_dispatched. Ошибки send не повышаются до безопасного отказа. |
| Task/Data outcomes | DONE не означает успех BSL. Task/Create/Cleanup/Assertion сохраняют известный отказ без фиктивной runtime receipt, ownership или rollback. После рестарта повтор не отправляется; записи при неудачной уборке сохраняются. |
| Lifecycle | TestClient start/stop сериализованы; терминальная запись монотонна. RuntimeProcessLifecycle владеет точными handles, отменяет поздний запуск и останавливает ресурсы вне общего launch-lock. При обновлении core реально завершён прежний собственный TestClient. |
| Startup | Единый monotonic budget listener/handshake/UI; итоговый wire size до claim. Exact-question broker поднимает только свою группу окон, при необходимости активирует пассивное тело и отвечает после свежих проверок. Java восстанавливает исходный уровень окон после выхода/убийства helper. Неясный input не повторяется. |
| Dependencies | SHA-pinned portable PowerShell7.6.9; незавершённая распаковка убирается, активация атомарна. Старое async generation не может активировать пакет или записать профиль после stop/start. Переполнение обхода целостности — отказ. Исторические очищенные каталоги не блокируют другой проект. |
| Managed controller | Shared production core вместо тестов с реальными системными эффектами. Все7 CLI actions сохранены,33 прежних контракта сопоставлены с решениями и запрещёнными эффектами. Точный аргумент владения вместо substring; пустой/некорректный state вызывает отказ. Контроллер/core имеют byte-parity PDE/JAR и проверяются перед запуском/teardown. |
| Release/install | Автоматические source/JAR verification receipts; все стадии обязательны, Python -O не отключает проверки. Fresh immutable package generation, строгий состав ZIP/p2, SHA и version/run binding. Forward install проверяет пакет; rollback требует точного установленного snapshot и проверенных backups до director. Межпроцессные locks защищают build и target/profile. |

## Проверки финального кандидата

Сборка:698 production Java sources. Общий обязательный прогон: **1172 Java +157 Python** (126 worker,13 provenance,18 package/installer fixtures),33 managed-controller contracts,3 empty-workspace CLI smoke и13 startup input/ABI contracts. OSGi direct-bytecode linkage, состав продукта и равенство обоих поставляемых PS-ресурсов проверены. verification.json passed=true связан с текущими исходниками и двумя JAR; упаковка связана с этим run.

Дополнительные целевые прогоны114 Java и37 dependency/lifecycle Java входят в общее покрытие и не прибавляются к нему. Package/installer18 Python независимо повторены Astra. Проверяющий p2 код принят на трёх сохранённых реальных Publisher outputs. Финальный пакет дополнительно прошёл настоящий p2 director verifyOnly на копии профиля собственной SDK; пользовательская установка не изменена.

## Живая приёмка

Отдельная маркированная EDT2026.1.2.2/JDK17 и собственная файловая TMS_TEST_AGENT на1С8.5.1.1522. UUID и привязка сверены; клиент MCP использовал только private client.toml этой среды. Пользовательская EDT, TMS_GIT/TMS_FILE85, рабочие базы и чужие процессы не использовались.

На финальной версии оба отдельных вопроса EPF и Native component пройдены полностью через MCP, без Computer Use и ручного клика. dialog_departed=true, outcome_unknown=false и owned_visibility_restored=true для обоих. Native достиг running/execution_allowed; исходный read-only query вернул42 с terminal=true/execution_success=true. После stop тот же ключ вернул тот же request и успешный результат. На audit29 дополнительно проверены repeat после revoke и доказанный отказ нового ключа без dispatch; соответствующие Native классы не менялись в финальной версии.

TestClient: финальная версия запустила свой thin client, прочитала реальное дерево формы и штатно выполнила cleanup. Обновление собственного core остановило предыдущий точный клиент. Ранее подтверждены read после156.76с idle тем же PID/start, форма «Языки мира» и отказ manifest-write до Process.start без оставшегося канала.

Close/open/reopen/Clean+rebuild повторены на собственном регрессионном проекте audit29. Все22 merge class entries побайтно совпали с consent11, где реально проверены GET_FROM_OTHER и CUSTOM_MERGE; повтор всех merge-сценариев в финальной версии не заявляется. Source-ошибки NoStore/virtual node и большой spreadsheet limit исправлены ранее и сохранены.

Проверочный harness audit29 сначала передал неправильный compact entry и неподдерживаемые direct view/pagination, затем rebuild для не-clean. Это ошибки тестового оператора, а не дефекты исполнения MCP: они дали лишние отвергнутые вызовы, после исправления сценарий прошёл. Ошибка исходного fixture-пути «Языки» ранее исправлена на фактический «ЯзыкиМира». Эти случаи не выдаются за ускорение/замедление плагина.

## Антивирус и выпуск

Исторический детект PDM:Trojan.Win32.Generic, terminate/delete подтверждены штатным AMSI export. Ложное срабатывание не заявляется. Обнаруженные тест/trace не восстанавливались и не исполнялись. Подробные отчёты и чувствительные данные остаются локальными. [Факты и artifact-specific triage](antivirus-incident-20261112.md) · [33 контракта](managed-controller-contracts.md).

В новой архитектуре сценарии работают в памяти, smoke не выполняет launch/stop/trust. При штатной неизменённой защите полный прогон прошёл; AMSI/File_Monitoring имеют running, новых avp4662 после нового набора не наблюдалось. Независимый review не выявил security-блокера текущего пакета. Допуск относится к точным version/archive SHA/verification run и не переопределяет исторический детект. Publish gate отдельно требует пройденный runtime, уборку и совпадающий triage с неизменённой работающей защитой.

## Ограничения доказательств

Причины прежнего производственного wire-timeout и BM deadlock не воспроизведены; их конкретное исправление не заявляется. Live Vanessa manager/расширения не повторялись: shared controller decisions и безопасный CLI smoke не доказывают сетевой/GUI сценарий Vanessa. Native/TC проверены в своей файловой8.5, не на кластере или пользовательской базе. Metadata FULL-update при этом аудите не выполнялся.

Foreground input кратко использует общий экран/мышь; заблокированная или другая Windows session не поддерживается. Уборка не откатывает бизнес-данные и не отменяет доверие, сохранённое платформой. Диагностический task journal прежнего некорректного запроса сохранён без replay; его исходный Native requests каталог был пуст. Маркированные base/fixtures и прежняя тестовая запись сохранены для регрессий.

Экономия модельных токенов и ускорение всей сегодняшней задачи не измерены. Подтверждённые bytes/calls, устранённые затраты и дорогая проверка portable PowerShell разобраны в [performance-review-20261112.md](performance-review-20261112.md). AgentMetrics включает edt.help, но не начальный tools/list; bytes не равны usage модели.

Evidence: локальные build13.log/tests13.log/package13.log/live13.json, runtime29-proof.json, merge-equivalence29.json, verification.json/package-verification.json, p2-verification.json и triage/runtime/review/cleanup records под .runs. После финальной приёмки Native external project и оба клиента очищены; своя EDT и её служебный Designer штатно завершены. Публичные GitHub/p2 SHA и resolver проверяются после публикации.
