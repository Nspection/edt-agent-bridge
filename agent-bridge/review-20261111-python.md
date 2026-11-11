# Ревью Python bootstrap

Diff относительно 848d6d9 просмотрен собственным и независимым ревью: официальный pinned NuGet, архив/entrypoint SHA256, безопасное извлечение, inventory из доверенного архива, immutable generations, квоты, отсутствие системной установки, unknown/отмена, fallback и shared UI/MCP.

Независимое ревью подтвердило P2: штатное создание venv добавляло 164 pyc в базовый runtime. Исправлено PYTHONDONTWRITEBYTECODE=1 в окружении дочерних процессов; непосредственные Python/worker-вызовы сохраняют -I -B. Живая приёмка подтвердила точное совпадение runtime после venv. Дополнительно unknown probe пробрасывается без fallback. UI получает Display в конструкторе и проверяет disposal перед обновлением.

927 Java +41 worker +16 table тестов и standalone boundary. В своей EDT через MCP проверены загрузка официального CPython3.13.17, создание managed venv и pinned wheels, свежая готовность, повторное использование, целостность runtime и cleanup окружения. Пользовательские EDT/базы не использованы; новых баз/клиентов 1С не создавали. Собственный runtime удаляется, настройки private EDT восстанавливаются. Релиз проверяется p2 resolver, merged JAR equivalence, GitHub SHA256 и обычным публичным URL.
