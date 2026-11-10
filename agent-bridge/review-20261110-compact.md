# Ревью компактного MCP и UUID-привязки

10 ноября 2026. Основное и независимое read-only ревью выполнены до публикации.
Независимый проверяющий: отдельный агент compact_release_review; исходники не менял,
EDT и базы не запускал. Подтверждённых P1/P2 не обнаружено; исправления по итогам
этих ревью не потребовались.

Проверены маршрутизация через прежний dispatcher, canonical authorization,
validation tokens, DENY/ASK, фильтры exposure и facade deny, сохранение старых
вызовов, короткое discovery и раскрытие точного контракта, блокировка маршрута
при running/outcome_unknown, read-only Python probe без установки пакетов.

Для UUID-привязки проверены current EDT branch context, hashed target identity,
чужие владельцы, явная замена primary, stale preview, durable claim/replay после
рестарта, частичный исход без автоматического повтора и верификация результата SDK.
Новые классы включены в source inventory и plugin.xml, тесты — в test inventory.

Граница проверки: живая приёмка охватывает переключение уже ассоциированной
зарегистрированной базы в primary. Первичная ассоциация, изменение контекста/цели
и частичный сбой проверены fake-gateway контрактами. Это не заявлено как живая
проверка всех вариантов native SDK. Остальные доказательства —
[compact-validation.md](compact-validation.md).
