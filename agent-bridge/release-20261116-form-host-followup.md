# EDT Agent Bridge 1.4.0.20261116reports4

Исправлены три подтверждённые жалобы на managed-form инструменты и Native host.

- Генератор событий выбирает контекст по каноническому событию и штатному правилу SDK. Русское `ПриСозданииНаСервере` и английское `OnCreateAtServer` создают `&НаСервере`; доступность события в окружениях и запрет optional wrapper не превращаются в контекст вызова. Существующий метод сохраняется.
- Status во время FULL/cleanup того же host возвращает атомарное наблюдение без ожидания lifecycle lock и без SDK/BM-проверки разрешения исполнения. Busy observation всегда `execution_allowed=false`.
- Возобновлённый cleanup при удалённом source и установившемся EQUAL пропускает повторный FULL. Независимое подтверждение отсутствия обработки в сохранённой конфигурации/базе остаётся обязательным. `database_update_skipped` доступен через MCP status.
- Capture формы сохраняет native body из SDK raster вместо ненадёжного PrintWindow. Выбранная страница сохраняется, raster имеет отдельный `capture_id`. Буферы ограничены и освобождаются, обычный вывод renderer восстанавливается. Обычный snapshot страницы формы возвращает следующий вызов проверяемого native capture.

Снимок содержит саму native-форму без chrome редактора. `viewport_equivalence_verified=false` и `visual_correctness_verified=false` сохраняются: растровая картинка и свежая модель не являются доказательством визуальной корректности или эквивалентности clipping/прокрутки редактора.

[Контракт native capture](native-form-preview.md) · [Native host](native-runtime-host.md)
