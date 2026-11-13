# Ревью preparation contract / reports2

Собственное и независимое ревью подтвердили и проверили исправления трёх конкретных дефектов:

1. Общая schema принимала `timeout_ms` при prepare, service отвергал его без имени поля. Теперь один action contract используется схемой, router до workspace, tool и service; wire diagnostics сохраняют rejected_field.
2. RuntimePreparationService имел ABA-race двойного удаления ACTIVE и незашищённый resume. Общий ActiveOperationLeases обеспечивает CAS release поколения; armed gate требует сохранённого receipt до исполнения.
3. Native startup_inspect мог получить уже завершившийся handshake после выдачи next_call. Read-only observer возвращает свежий статус; обрабатывается только отдельный тип завершения startup. Общие ошибки и guard ответа на security question не ослаблены.

Приёмка предыдущих editor/report классов связана с финальными артефактами проверкой class-byte equality. Новые гонки воспроизводятся детерминированными fake backend/wire тестами, без действий в пользовательской среде. Независимое одобрение обусловлено успешным полным прогоном точных исходников. Новых подтверждённых открытых блокеров не найдено.

Это targeted review новых изменений; предыдущие полные архитектурные/Astra проверки описаны в audit2/reports1. Не заявляется повторная построчная проверка всего проекта. Ограничения real OAuth/POST, 8.3.27 и внешних dependency contents сохранены.
