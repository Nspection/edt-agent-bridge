# EDT Agent Bridge 1.4.0.20261113audit1

Результаты полного архитектурного и независимого ревью, включая Astra medium.

- TestClient: единый startup budget, проверка полного payload до claim, защита гонок start/stop и остановка своих процессов при обновлении core.
- Native: оба стартовых вопроса проходят через MCP с exact scope/nonce и восстановлением уровня своих окон. Повтор ключа после stop/revoke сохраняет исходную операцию; неизвестный исход не повторяется.
- Task/Create/Cleanup/Assertion: действительный результат отделён от постановки/job DONE; доказанный отказ до отправки не превращается в вечный unknown и не даёт ложного ownership/rollback.
- Меньше лишней работы: схемы не разбираются для обычного compact call, Native использует одно наблюдение совместимости и адресный claim, batch wait освобождает session lock. Ускорение всей задачи и фактические токены модели пока не измерены.
- Подготовка зависимостей защищена generation/atomic activation/cleanup. Доступен закреплённый portable PowerShell7.6.9 без системной установки.
- Shared managed-controller core сохраняет7 CLI actions;33 контракта проверяются через эффекты в памяти. Исправлены похожие чужие маркеры процессов и некорректный state. Старый обнаруженный AV тест не восстанавливался; исторический детект не объявляется ложным.
- Сборка/пакет/установка связаны с source/JAR/SHA receipts; p2 generation неизменяема, rollback отказывается при посторонних изменениях профиля.

Сохранены выпущенные project open/close/reopen/Clean и исправления native merge. Пользовательская EDT и базы при разработке не изменялись.

Проверки:1172 Java,157 Python,33 controller contracts,3 CLI smoke,13 startup guards; настоящий p2 verifyOnly на копии профиля. Финальная живая приёмка своей EDT/8.5: Native EPF/component confirmations полностью MCP, query42, исходный результат после stop; TestClient start/read/cleanup, остановка клиента при обновлении core. Собственные процессы завершены.

[Полное ревью](review-20261112-project-audit.md) · [Производительность и границы измерений](performance-review-20261112.md) · [Контроллер](managed-controller-contracts.md) · [AV triage](antivirus-incident-20261112.md).

Причины старых wire-timeout/BM deadlock не воспроизведены. Полный live Vanessa сценарий не повторялся; проверка своей файловой8.5 не заменяет проверку пользовательской/серверной ИБ. Unknown/replay и платформенная защита сохранены.
