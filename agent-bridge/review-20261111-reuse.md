# Ревью повторного использования TestClient

Основное и отдельное независимое read-only ревью проверили весь diff относительно
3e76cf3: владельца/заимствование, UUID/EQUAL/kind/live identity, same-key,
неизвестные исходы, блокировки start/cleanup, краткие ответы и discovery.

Подтверждён P2: known failed direct job не становился collected, закрытие его
внутренней сессии отказывало; UiRunService тем не менее возвращал cleanup_verified.
Это подтверждено сохранённым живым журналом прежней dev2, не предположением.
Исправление: узкая collection только owned direct adapter + job DONE + точный
operation_id + явный outcome_unknown=false; business success не повышается.
Независимая перепроверка заметила отсутствие operation_id в FULL фоне —
исправлена server-side корреляция ответа и добавлена соответствующая регрессия.

Теперь borrower cleanup подтверждается после close; ошибка блокирует новый reuse.
Восстановление уборки лишь перечитывает исходный batch-status, не dispatch.
Unknown, несовпавший operation_id и другой адаптер остаются закрыты защитой.
Флаг client_retained очищается после успешной уборки владельца.

Независимая окончательная перепроверка новых P1/P2 не обнаружила. 821 Java-тест,
41 worker и16 table-контрактов, standalone boundary, частный p2 verifyOnly и
живой повтор на релизной сборке прошли. Реальная сессия failed condition закрыта,
collected=true, но test_passed=false; после owner cleanup клиент cleaned=true.

Пределы: собственная синтетическая база8.5.1.1522, не карта/ЦО TMS, не кластер
и не crash-тест реальной EDT посреди записи. Пользовательские EDT/базы не
использовались. Бизнес-данные не откатываются закрытием runtime.
