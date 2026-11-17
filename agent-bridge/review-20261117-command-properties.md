# Ревью свойств команд и CommandModule.bsl — 17.11.2026

Версия `1.4.0.20261117vcommands1`. Verification `9f2b2d40ec2c4d85a8ecdc48299bf688`.
Core SHA256 `760eb971979f39a0b24ef518606cbd564b33ae70f208d473f71db199f08a56d3`.
UI SHA256 `277c45dcf266638a2378525fcc5840d1f9dabbeb4d8f5806b3c66d0bf647c0bc`.

## Причины и изменение

Создание дочернего Command ранее применяло common fields и BasicFeature defaults, но не переданные свойства команды. Теперь single и batch вызывают общий applyObjectChanges после attach/native fillReferences, в той же BM-транзакции. Используются существующие setters стандартных групп, TypeDescription и scalar properties. Ссылочные типы предварительно разрешаются для корневого properties и каждого batch entry. Неизвестные/невалидные свойства отказываются; native defaults при отсутствии свойств сохранены. Ручных замен стандартных групп/типов нет.

ModuleTarget терял роль и имя дочерней команды. В нём сохраняется имя фактически разрешённого BasicCommand; кандидат для такого владельца ровно один: Commands/<name>/CommandModule.bsl. auto/module/command согласованы, object/manager отклоняются до filesystem. Родительские Module/ObjectModule/ManagerModule не рассматриваются. Для CommonCommand псевдоним module также выбирает CommandModule.bsl; прежние form/CommonModule mappings сохранены. Автоматического переноса существующего ошибочного пользовательского файла нет.

## Проверки

1422 Java-теста, включая восемь новых содержательных регрессий: пути/владельцы/alias и несовместимые kind, form/CommonModule mappings, pre-resolve structured types, scalar setter, unknown/UUID refusal, omitted defaults и malformed type preflight. 168 direct TestClient Python-тестов и все обязательные PowerShell/product boundary/OSGi/provenance стадии прошли. В первом полном прогоне уборка временного .tmp файла в другом NativeHostChannelTest завершилась FileSystemException из-за занятого файла; полный повтор прошёл без изменения или ослабления тестов.

Реальный приватный MCP в новой собственной EDT 2026.1.2.2, проект 8.5.1 без ИБ: создание Run и batch команд с group=FormNavigationPanelImportant, representation=Auto и двумя CatalogRef типами; XML сохранил все значения. Неверные group/property/nonexistent reference type отказаны; ошибка второго batch entry откатила первый. В XML и после холодного native BM-перечитывания ровно Run/BatchOne/BatchTwo/Omitted, без partial records. Независимый reviewer сам проверил XML и SHA модулей владельца.

После холодного перезапуска отдельный read-only BM harness проверил разрешённый StandardCommandGroup и оба непроксированных native TypeItem; для Omitted — native group и отсутствие параметра. При существующих owner Module/ObjectModule/ManagerModule запрос create_if_missing=false для отсутствующего BatchOne module дал METADATA_NOT_FOUND. Создание Run module попало в правильный каталог, повтор auto/module/command сохранил его байты, object/manager отказаны. Все три owner sentinel SHA остались прежними. CommonCommand module alias проверен через MCP.

Отдельная попытка commandParameterType со String не разрешилась текущим typed route и откатилась. Это не доказательство общего запрета SDK на String; исправление произвольных скалярных command parameter types этим выпуском не заявляется. Положительная приёмка относится к запрошенным ссылочным типам. Исполнение прикладного обработчика и работа базы не заявляются.

Собственный SDK остановлен; база и клиент не создавались. Пользовательские EDT, x5 и базы не использовались. Внешние source fixtures и квитанции сохранены для воспроизводимости, в Git артефакты прогонов не включены. Native DLL/EPF не менялись. Локальный p2 verifyOnly в копии профиля прошёл без установки в пользовательскую EDT; AV и публичная публикация отражены в release manifest.

Собственное и независимое source/runtime review: открытых подтверждённых P1/P2 в объёме изменения нет. Финальный artifact signoff сверяет source snapshot/JAR и обязательную verification.

Доказательства во внешнем run 2026-11-17-command-properties-modules: mcp-acceptance.json, cold-native-proof.json, cold-no-owner-fallback.json, inspect-*.json, bad-*-rejection.json, runtime-proof.json, review-signoff.json, p2-resolver.json, antivirus-triage.json.
