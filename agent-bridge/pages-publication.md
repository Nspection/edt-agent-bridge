# Ручная публикация проверенного Pages-снимка

Сборка/релиз плагина остаётся локальной. `tools/publish-p2-local.sh` или релизный оператор обновляет gh-pages; это ещё не доказательство доступности сайта.

При build_type=workflow запускать **Publish verified Pages snapshot** только вручную из main. Передать точные pages_commit (текущая HEAD ветки gh-pages), release_commit (source_commit из release-manifest.json) и version. Workflow ничего не пересобирает: проверяет чистоту checkout, точный коммит, идентичность релиза и SHA256 файлов инвентаря, затем загружает и развёртывает готовый сайт. Если ветка успела измениться, он отказывает. Actions закреплены по commit SHA.

Pages должен использовать GitHub Actions (build_type=workflow), а существующее окружение github-pages — разрешать main. Сохранять все прочие ограничения окружения. Workflow сам настройки и политики не меняет. Автоматических push/release-триггеров нет; отключённый CI release.yml не включает сборку плагина.

После успешного deploy проверить обычный URL https://nspection.github.io/edt-agent-bridge/: текущую feature в content.jar, SHA256 файлов по release-manifest.json и p2 verifyOnly в отдельном профиле. При сбое не считать публикацию завершённой по одному push/tag/job; сохранить run ID и проверить тот же результат. Пользовательскую EDT/профиль для этой проверки не изменять.

Этот путь добавлен после двух внутренних отказов dynamic/pages/pages-build-deployment (Internal server error до build). Он использует штатные actions/configure-pages, upload-pages-artifact и deploy-pages.
