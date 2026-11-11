# Ревью Native batch controls

Проверены явные wait/assert/read, deadline и отдача управления UI между ticks, сохранение и append-only prefix progress, stop/restart, no-replay acknowledgement, лимиты и старые code-only шаги. Native DLL/ABI/endpoints и WinHTTP не изменены. Новый шаблон сообщает batch_controls_v1 до submission; screenshot_png проверяется отдельно.

Независимый reviewer testclient_stability_review нашёл и перепроверил три P2: timeout должен проверяться раньше interval и после probe; terminal receipt должен сохраняться до подтверждения; длинные error/snapshot_error должны ограничиваться до progress. Все исправлены, открытых P1/P2 нет. Reviewer отдельно выполнил реальный HTTP fault harness: первый отказ записи оставляет delivered, повтор сохраняет completed до ответа 200. EDT/базы reviewer не использовал.

968 Java, 58 worker и 16 table тестов. Живая приёмка в собственной базе 8.5 с реальным metadata-host: 32-секундное условие готовности, title/read/assert, остановка зависимых шагов, failure screenshot, deadline при interval10s/timeout1s, probe-overrun, тип Boolean, missing select, server-context, большой value/error и progress после stop. Оригинал и сетка просмотрены. Код не получает универсальный доступ к произвольным формам: он исполняется в собственной форме host; select проецирует JSON указанного BSL. Карты TMS и пользовательская среда не проверялись.

В ходе предварительной уборки обработка была доказанно удалена из базы, но временная блокировка process.log прервала удаление файлов. После terminal FAILED и database_verified_absent безопасное продолжение той же уборки завершилось; без повторного обновления базы. После рестарта первый prepare получил Not an EDT project до готовности модели; исходный job FAILED до резервирования host, после edt_ready подготовка повторена. Эти события сохранены как диагностические ограничения, не скрыты как зелёная приёмка.

Standalone EPF пересобрана из текущего HostForm.bsl и проверена по хешу; Native ZIP сохранён побайтно. Перед публикацией проверяются совпадение merged классов с кандидатом, p2 verifyOnly, SHA256 GitHub assets и обычные URL Pages. Своё окружение убирается, настройки private EDT восстанавливаются.

Дополнительно проверены сохранение JSON null на выходе MCP и defaults у legacy code-only шагов: отсутствие kind/screenshot_on_failure не должно оставлять Неопределено вместо execute/False. Финальный live-прогон native3 включает legacy failure → следующий здоровый запрос.
