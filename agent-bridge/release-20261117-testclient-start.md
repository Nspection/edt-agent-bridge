# EDT Agent Bridge 1.4.0.20261117start1

Исправлен отказ запуска прямого TestClient после накопления остановленных, но не очищенных записей. Вместо строки без client_id возвращается state=capacity_blocked, blocking_clients с точными IDs и сохранёнными состояниями, лимит и status/cleanup next_calls для распознанных записей. Для повреждённых/непроверенных записей сохраняется unproven без выдуманного безопасного cleanup. Process liveness по сохранённой записи не утверждается.

Место под запуск атомарно резервируется до SDK binding, Python и расходования once-consent. Два параллельных старта не превышают лимит4 и не расходуют два согласия на последнее место. При capacity-отказе: process_started=false, action_dispatched=false, execution_state=launch_not_dispatched, outcome_unknown=false, consent_consumed=false, retry_safe=true. Готовность Python проверяется до claim; отсутствие согласия сохраняет прежний awaiting_consent/decision контракт.

Остановка процесса не равнозначна cleanup: stopped-записи продолжают занимать место до явной уборки. Автоматический stop/cleanup не добавлен. Собственная пустая reservation освобождается по nonce-marker с проверкой пути; Windows fileKey может быть null. Удаление нерекурсивное, изменённые marker/foreign files сохраняются. Durable start receipt остаётся при сбоях после резервирования.

Структурированная диагностика сохраняется и в единой prepare-операции без ложного outcome_unknown из-за уже готовой Python-среды. Для прочих startup/transport failures безопасность повтора не расширяется, mutation barrier не ослабляется. Ранее использованные once-grants автоматически не восстанавливаются.

Проверено до запуска в пустом собственном SDK и Windows regression suite; реальные 1С-клиенты и базы не запускались. Native DLL/EPF не изменены. Пользовательские EDT, процессы, x5 и базы не затронуты. Новый отдельный отчёт о TimeoutError в preflight команды формы этим релизом исправленным не объявляется.
