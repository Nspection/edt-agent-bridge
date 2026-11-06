# EDT editor MCP tools

Local CodePilot override for EDT 2026.1 / Java 17, core 1.3.9.20261019-0402.
The existing debugger fixes are preserved. Four tools are contributed through
CodePilot's `com.codepilot1c.core.toolProvider` extension point.

## Agent calls

1. `edt_editor_open` with `project` and `file_path` relative to project `src/`.
   Existing `.mdo` objects and `.form` forms only. The service uses the actual EDT
   model/editor context and explicitly chooses the metadata or form editor.
   It does not use the user's default file association (which may be XML).
2. Read returned `editor_id`, `ready`, `dirty` and `tabs`. If `ready=false`, poll
   `edt_editor_tabs`. Immediately after EDT starts its model may not yet resolve;
   retry open after project loading rather than changing source files.
3. Use `edt_editor_select_tab` with `editor_id` and exactly one of `tab_id`, exact
   `tab_title`, or zero-based `tab_index`. Prefer stable IDs: titles have counts
   and lazy-loading decorations. Closed/stale editor handles are rejected.
4. `edt_editor_snapshot` returns an absolute PNG `image_path`, size, current page,
   timestamps and dirty state. It accepts an optional tab selector, so one call can
   select a tab, yield to UI painting, then capture it. Open the PNG with the agent's
   local image reader; it is not embedded as a base64 text blob in the response.

Object example:

```json
{"project":"TMS_GIT","file_path":"Documents/РегистрацияГрузовыхМест/РегистрацияГрузовыхМест.mdo"}
```

Snapshot after opening:

```json
{"editor_id":"<returned handle>","tab_id":"editors.document.pages.data"}
```

Form example:

```json
{"project":"TMS_GIT","file_path":"Documents/РегистрацияГрузовыхМест/Forms/ФормаДокумента/Form.form"}
```

## Scope and limits

- Calls run on the SWT UI thread; open/select does not save or mutate metadata.
- `requested_file` remains the original requested file. `active_resource` is the
  resource reported by the active editor (may become Module.bsl on a module tab).
- Windows snapshots use PrintWindow on EDT's own window and crop the editor area,
  so overlapping applications are not copied. No forceActive is used for capture.
- EDT can be covered. A minimized Windows window is restored without activation by default; set auto_restore=false to disable this. Other platforms
  use SWT control printing; that fallback has not been live-tested here.
- Native/browser form previews can render asynchronously. `ready` detects the
  editor's loading page; it does not establish that every preview pixel settled.
  Inspect screenshots and retry when necessary. This is not runtime 1C acceptance.
- Modal EDT dialogs are reported, not dismissed. Nested tab controls inside a
  page are not included in this first implementation; this API selects editor pages.
- MCP command lists can remain cached in an existing client session after plugin
  upgrade. Reconnect that client for discovery. Protocol-level calls still reach
  the registered server commands.

Eighteen unit tests cover existing debug regression, source path/tab validation and auto_restore settings.
Live acceptance evidence is recorded separately. No commit or push authorized.

## Live acceptance, 2026-11-05 17:49-17:51 MSK

Installed local override: 20261105editor3.
SHA256: 984863BCFEF0DA4A9A2BEFC31080E6F6DF0D1CAA923043D60E89A136DD690D69.
Four commands registered via the toolProvider extension and were called over the
local authenticated MCP protocol. No mouse/keyboard automation was used.

Document РегистрацияГрузовыхМест opened as DocumentEditor, not XML. Twenty real
editor pages were enumerated. Snapshot with tab_id selected and captured Данные
and Формы in a single call each; viewed PNGs showed the correct trees and form list.
Form ФормаДокумента opened as FormEditor. Форма and Модуль pages selected and
captured by stable IDs. The first form image showed an unrendered preview; a later
snapshot showed the actual form with statuses, registration button, internal pages
and fields. ready=true only confirms editor pages, not complete async preview paint.
All reported dirty=false. The background PrintWindow capture contained EDT even
while another app covered its screen area. Sixteen unit tests passed on final JAR.

Evidence PNGs in workspace .metadata/.plugins/com.codepilot1c.core/editor-snapshots:
- b61d2998-f607-4664-b713-57523129021a.png: document Data
- a11c0a30-079a-4ef3-8254-56d8d94ba478.png: document Forms
- b0ba0c2b-7136-45e1-ac42-ad8734e136ac.png: form Module
- 227cedd7-12b0-4e9f-b825-df229354a5e8.png: rendered form preview

Existing x5 diff remains only the prior 56-line metadata addition. No source edits,
commit, push, runtime migration or new 1C test were performed in this editor task.

## Auto-restore live acceptance, 2026-11-05 18:06 MSK

Installed 20261105editor4, SHA256:
7EE9791E5FB9AD8A5564E20144466647DB214F9D36B1992CC83A50A0347CD882.
The user minimized EDT. A single edt_editor_snapshot request with
editor_id=editor@47b8e2 and tab_id=editors.form.pages.main used default auto_restore.
Response: window_restored=true, ready=true, dirty=false; capture_method=edt_window_print.
The agent viewed 2f201b23-351a-4896-b8b9-dbc2df2c5fec.png and confirmed the form
preview was rendered. No Computer Use or external CMD restoration was involved.
The implementation uses ShowWindow(SW_SHOWNOACTIVATE) inside the CodePilot MCP service.
Eighteen unit tests passed. Auto-restore can be disabled by explicit auto_restore=false.
## General window control

edt_window is independent of editor_id and supports action=state|minimize|restore.
The response contains window_id, before/after minimized/maximized/visible/foreground
and changed. Source restoration is shared with automatic screenshot restoration.
Live build editor5 successfully minimized and restored EDT. Restoring also activated
EDT despite SW_SHOWNOACTIVATE, likely through a restore listener. Build editor6
preserves the previous foreground window if EDT took it, without intervening if the
user selected a different app. Twenty-one tests passed; editor6 deployment and live
focus-preservation acceptance are pending user-confirmed restart.

## Scroll/editor-area investigation (analysis only)

IWorkbenchPage exposes getReference, getPartState and setPartState with
STATE_MAXIMIZED/STATE_RESTORED: editor-area maximize can be controlled independently
of operating-system window maximize. FormWysiwygViewer provides getControl; its
representation has an SWT ScrolledComposite and a native form composite. These
are distinct scrolling layers. A generic scroll command must enumerate/identify
its target region and report actual origin/range instead of scrolling whichever
control happens to have focus. The representation also caches formImageData; full
form export is a candidate requiring verification, not an accepted capability.
No viewport or scroll implementation was added in this change.
## Installed viewport/scroll package: 20261105editor7

SHA256: 7D34C42AC80E2D7EA063FFA279D94AD901691E676BA9365E8FC0819941A11707.
New MCP tools:
- edt_editor_regions(editor_id): enumerate the active page's explicit targets.
- edt_editor_scroll(editor_id,target,axis,operation,amount,capture): scroll a selected
  region; capture=true returns the PNG and operation_result with before/after data.
- edt_editor_view(editor_id,mode,capture): state|maximize|restore the editor area.
These change view state only and preserve the form model. Full-editor maximize
is independent of maximizing/minimizing the operating-system window.

Example: edt_editor_scroll with target=preview, operation=end, capture=true.
preview is the outer ScrolledComposite: pixel offset and authoritative start/end
flags are returned. Generic Tree/Table targets convert pixel deltas to rows;
StyledText and ScrolledComposite use pixel positions. Refresh region ids after a
page switch. Native preview_content wheel dispatch is available, but its actual
range/end is unknown; on this form it did not visibly move. Do not treat native
wheel acceptance as proof of movement; use the verified outer preview scrolling
for inspecting the complete rendered form.

Live acceptance on РегистрацияГрузовыхМест.ФормаДокумента, 2026-11-05 18:22-18:26 MSK:
- Editor maximize changed image width 1004 -> 1874. Restore returned it to 1004.
- preview end moved origin y=0 -> 490 and at_end=true; the viewed PNG showed lower
  fields, Все предметы and the Записать buttons. start returned origin to zero.
- The elements Tree moved from Форма to the last items with selection 114/114;
  start restored the original top item without changing selection/model data.
- edt_window minimize/restore now reported foreground=false before and after.
- A second minimize followed by edt_editor_snapshot returned window_restored=true,
  a valid viewed PNG, dirty=false and foreground=false in subsequent state.
- Twenty-four tests passed on the installed package; diff checks passed.

Evidence PNGs in editor-snapshots:
43d4f280-e27b-42ef-8649-0ddfe05bdff5.png: maximized editor (1874 px).
49585816-e6ea-45ed-926e-6614f448aa7c.png: bottom of form and bottom of elements tree.
3938d749-2037-40a6-868b-8afacccf03cc.png: restored editor (1004 px).
53fa635c-0a81-4983-a56d-81925bdfc5c0.png: auto-restored background screenshot.
No commit or push. Existing x5 metadata diff preserved.


## Review corrections, 2026-11-05

Local package `20261105review1` serializes editor/window operations through the
capture callback. Queued callbacks skip execution after expiration, interruption
or completion. Operations already running on the SWT thread cannot be undone.
A manual tab change during the render delay rejects the snapshot instead of
returning a different tab. Tool execution must originate on a worker thread.

Debug launch reuse now requires launch mode `debug`; an existing normal Run does
not prevent a new debug launch. SSE GET uses the same authentication gate as
POST/DELETE. qa_run emits the Vanessa JSON setting `КлиентыТестирования`.

Validation: 114 runtime tests, 31 patch/regression Java tests, and nine isolated
managed-VA continuation contracts passed. The complete Java 25/Tycho reactor was
not run with this Java 17 patch environment. The full Vanessa/1C debugger scenario
was not repeated as part of these review corrections.

Installed into the existing EDT 2026.1 workspace after graceful restart. Live SSE
GET without a bearer token returns 401. Two parallel snapshots of
РегистрацияГрузовыхМест.ФормаДокумента returned the requested Form and Module tabs,
respectively; both PNGs were viewed. The form preview was initially still loading
and rendered on a subsequent snapshot; capture readiness is not runtime UI proof.
The final editor remains on Form and reports dirty=false.

Evidence in workspace editor-snapshots:
- b213786d-3b18-4ca8-a134-8eba2c28425d.png: first Form tab capture (preview loading).
- 1fb49863-6621-48d8-a3ec-0ff043d6a4c1.png: concurrent Module tab capture.
- 0097c937-6e97-4d93-9cb0-a2eb5459633d.png: Form with rendered preview.

Original p2 core retained for rollback. No commit or push performed.
