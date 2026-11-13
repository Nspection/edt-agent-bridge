# Ревью reports1

Область: новый диагностический канал в GitHub, согласие/учётные данные/публикация, устойчивость outbox, native form projection, сроки и lifecycle, привязка редактора к настоящему source, поставка.

Независимое ревью исходников выполнял отдельный агент; собственное ревью и исправления — основной агент. Предыдущие Astra high/medium ревью TestClient и архитектуры сохранены в [audit2](review-20261113-architecture-audit.md); это не заявление о новом полном построчном аудите всего проекта.

Исправлены до публикации:

- Authority-read вне общего deadline и закрытый executor после stop/start → bounded epoch-owned reader, cancellation и общий бюджет UI.
- Ложное подтверждение актуальности после задержки → causal native generation и сравнение source/model/native identities.
- Restored editor с owner `.mdo` → native IResourceLookup для Form, проверка привязки внутри BM-транзакции; распознавание FormEditor без cache открытий.
- Сеть под policy/outbox lock → короткий preflight/CAS, отзыв до claim блокирует POST.
- Stored hash без пересчёта → регенерация безопасного текста из typed envelope и проверка exact bytes.
- Смена credential между account/POST → один credential для проверки автора и отправки.
- Поздний timeout перезаписывает submitted → монотонный CAS с identity snapshot и регрессионным concurrent-тестом.
- Blocking body read → capped subscriber и отмена полного exchange по deadline.
- Пожизненный лимит успешных отчётов → отдельный pending-лимит, суточный budget и сохранение квитанций дедупликации.
- ABA при завершении consent-паузы и resume → ownership lease поколения с CAS release; отказ очереди защищён armed gate, добавлены детерминированные регрессии.
- Посторонние metadata поля в ответе → явный публичный список; production epoch проверяется до новых отправок.

Финальное независимое одобрение обусловлено успешными source-matched тестами и live restored-editor приёмкой. Доказательства относятся к точным JAR/verification run, закреплённым в release manifest. Новые подтверждённые блокирующие замечания не оставляются открытыми.

Ограничения: source fingerprint не читает содержимое внешних ссылок; native adapter требует повторной верификации для другого SDK; OAuth/реальный GitHub POST не выполнены. Старое антивирусное срабатывание остаётся классифицированным как unknown, false positive не установлен, исключения и настройки защиты не менялись.
