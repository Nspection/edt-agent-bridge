# Ревью единой подготовки runtime и thin startup

Проверен весь diff ветки codex/unified-runtime-preparation относительно7091653:
журнал/идемпотентность, текущая привязка/UUID/EQUAL, выбор исполнителя,
политика exposure/mutation, резервирование IDs до запуска, borrowed/owned
ресурсы и порядок уборки, таймауты TestClient и отсутствие повторов мутаций.

Независимое ревью выполнено отдельным агентом read-only без EDT/баз.

| Приоритет | Дефект | Исправление и проверка |
|---|---|---|
| P2 | Native status сообщал ready, но журнал оставался awaiting_runtime и блокировал UI-подготовку рядом | Подтверждённая готовность сохраняется; nativeFirstBecomesDurablyReadyAndAllowsUiAlongside |
| P2 | Исторический ready разрешал другой engine после потери исходного UI-канала | current()+backend.ready() перед допуском помощника; disconnectedUiCannotAuthorizeAnotherEngine |

Оба замечания исправлены и повторно проверены независимым ревьюером.
Дополнительных подтверждённых блокирующих замечаний нет. Основное ревью
также исправило устаревшие current-release ссылки/описание README.

Код runtime-приёмки thinfix1 и release unified1 сравнивается по JAR entries:
изменились только version manifests и RuntimePreparationService.class,
который содержит два review fixes выше. TestClient/worker launch path остаётся
тем же, который прошёл четыре thin-запуска на TMS_FILE85. Повторные business
сценарии не заявляются; review fixes проверяются контрактами fake backend.

Регрессии:775 Java-тестов,35 worker- и16 table-тестов. Полный live Native/UUID
fixture описан в unified-runtime-preparation-validation.md; TMS thin acceptance
и границы причинного вывода — tms-testclient-install-validation.md.

Релиз выполняется из merge commit main: отдельная сборка/p2, точный tag,
SHA256 GitHub assets и manifest Pages; проверка обычного p2 URL отдельно от
успешного push. Пользовательские EDT/базы на этапе ревью/релиза не запускаются,
не обновляются и не перезапускаются. Их исходники в коммит плагина не входят.
