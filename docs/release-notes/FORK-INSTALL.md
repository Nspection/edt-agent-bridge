# Установка расширений этого форка

Это исходники разработки, а не готовый проверенный p2-релиз. Сначала установите исходный CodePilot1C 1.3.9 по README. Локальный override проверялся с 1C:EDT 2026.1, Java 17 и платформой 1С 8.5.1.1522. Другие версии требуют проверки совместимости.

## Локальная сборка и тесты (Windows / PowerShell 7)

Укажите реальные пути своего компьютера. Для тестов нужны JUnit 4.13.2 и Hamcrest 2.2: скрипт ищет их в локальных кешах p2/Maven текущего пользователя; при другом расположении задайте `JUNIT_JAR` и `HAMCREST_JAR` явно.

```powershell
$env:CODEPILOT_EDT_HOME = 'C:\Tools\1C_EDT\1cedt'
$env:JAVA_HOME = 'C:\Tools\jdk-17'
pwsh -File tools/build-edt-debug-patch.ps1 -Qualifier '20261106development'
pwsh -File tools/test-edt-debug-patch.ps1
pwsh -File tools/test-managed-vanessa.ps1
```

Корень репозитория скрипты определяют относительно своего расположения. `CODEPILOT_EDT_HOME` должен содержать `configuration/org.eclipse.equinox.simpleconfigurator/bundles.info` с установленным исходным CodePilot. Сборка читает библиотеки вашей установленной EDT. Полная Tycho/p2-сборка новых расширений пока не подтверждена.

Сохраните работу и закройте EDT перед установкой:

```powershell
pwsh -File tools/install-edt-debug-patch.ps1 -CheckOnly
pwsh -File tools/install-edt-debug-patch.ps1
# Для замены ранее установленного override добавьте -Upgrade.
# Для восстановления исходного бандла:
# pwsh -File tools/install-edt-debug-patch.ps1 -Rollback
```

После перезапуска начните с `edt_capabilities`. Для Vanessa настройте `edt_runner_profile` с собственной тестовой базой и отдельной базой менеджера, путём к внешнему EPF и ожидаемым SHA256. Подробности: [EDT-AI-AUTOMATION.md](EDT-AI-AUTOMATION.md), [MANAGED-VANESSA.md](MANAGED-VANESSA.md). Платформа, EDT, Vanessa и базы в этот репозиторий не входят.

Для прямого вызова runner-скрипта передавайте параметры `Workspace`, `PlatformBin`, `Infobase`, `VanessaEpf` явно либо задайте `CODEPILOT_WORKSPACE`, `ONEC_PLATFORM_BIN`, `CODEPILOT_TEST_INFOBASE`, `VANESSA_EPF`. MCP-профиль передаёт проверенные параметры автоматически. Используйте отдельные тестовые базы; не копируйте значения путей из старых отчётов.
