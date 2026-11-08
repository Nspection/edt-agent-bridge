# Проверки contribution-ветки

Дата: 2026-11-08. База: CodePilot `846b37eafa6fa0b41ca5b9c3729148114c10e384`; источник возможностей: Bridge `b442420db2dd0f3eb918c7c636ff9ae4a9277ea2`.

## Выполнено

- Совместно скомпилированы все 1061 production Java-файла: исходные core/UI CodePilot, его runtime kernel и новые EDT-инструменты. Java 17, UTF-8; без подстановки собранного Bridge JAR.
- Скомпилированы все 312 Java-файлов core.tests. Выбранная регрессионная группа: **656 тестов, 0 ошибок**. Полный список классов — [regression-suite.txt](regression-suite.txt).
- В группу входят native EDT адаптеры и инструменты, исходные chat/UI-contract/profile/permission tests, передача structuredContent и sensitive trace. NativeToolsChatRegistryTest загружает новые регистрации из plugin.xml в исходный ToolRegistry и проверяет доступность команд через него.
- Windows x64 native host собран MSVC; NativeContractTest выполнен успешно. Создан release ZIP без диагностического логирования. Бинарники в contribution-ветку не включены.
- `python tools/check-native-contribution.py`: сохранены защищённые исходные механизмы, исходные plugin contributions и manifest contracts; удалений нет.

Для Java-проверки использованы библиотеки исходного CodePilot core/lib и ui/lib, SDK локальной EDT 2026.1 и недостающие в локальном SDK Terminal core/UI из официального Eclipse 2025-03. Эти SDK-зависимости не добавлены в Git и не установлены в рабочую EDT пользователя.

## Что ещё проверить перед upstream-релизом

Это проверка исходников и выбранной регрессионной группы, а не полный прогон всех тестов CodePilot. Не выполнены полная Maven/Tycho/p2-сборка, live LLM-запрос в исходном чате и интеграционная проверка в собранной CodePilot EDT. Проверки Bridge на реальной 1С не переносятся автоматически на эту contribution-ветку. Авторская CI/release pipeline остаётся неизменной и должна подтвердить сборку и установку.

Новые команды проходят через исходные ToolRegistry/ToolExecutionService и профильные ограничения. Тест общей регистрации подтверждает этот путь, но не заменяет live chat acceptance. Внешние Vanessa и native-компонента требуют подготовки окружения; перенос исходников не устанавливает их автоматически.

## Воспроизводимые точки входа

- Сборка CodePilot: существующие команды и target platform исходного README/pom, без новых override-скриптов Bridge.
- Проверка сохранности исходных механизмов: `python tools/check-native-contribution.py` из корня checkout.
- Native host: `python tools/build-native-host.py --output .runs/native-host/release` (MSVC x64 + Windows SDK); скрипт также выполняет NativeContractTest.
- После авторской сборки Java-тесты из regression-suite.txt можно передать JUnitCore либо использовать исходный Tycho test bundle.

Тестовые информационные базы, сеансы 1С и проекты EDT при подготовке этой ветки не создавались. Рабочая EDT пользователя и x5 не изменялись. Временные результаты компиляции находятся в ignored .runs родительского репозитория; в contribution попадают только исходники и документация.
