# Direct TestClient waits: validation

Local candidate **1.4.0.20261110waitsfinal1**, branch `codex/direct-testclient-waits`, based on main `b098c25b1876ce1ab2432082e493b11c0bff6f4d`. This stage makes no commit, push, public release or user installation.

## Automated checks

- **705 Java tests** passed on the final candidate, including typed/bounded wait validation, unsupported step options, conflicting window conditions and summary evidence without large values. Standalone product/account-route boundary checks passed.
- **23 worker +16 table Python contracts** passed: delayed exact windows/fields, complete negative read replies, readiness with no title, known timeout, boolean uncertainty, transport loss before/after dispatch, postcondition waiting without repeated mutation, stopped batches and integer-valued JSON numbers. Existing table assertions remain covered.
- Update site packaged; private p2 director verifyOnly passed without installing into the user EDT. Exact packaged Python resources/lock match sources; no wheels, venv, pyc or Python caches are embedded.

## Own live acceptance

Windows AMD64, CPython3.13 and platform8.5.1.1522; own `EDT-Agent-Bridge-Dev` workspace, native-created minimal configuration/catalog/default object form and newly provisioned disposable file base. No application BSL, extensions, external EPF or trust changes were needed. No user EDT/base was queried or changed.

A direct client started without python_path and returned connected=true/ui_ready=true after semantic window readiness. One batch performed9 verified steps: open list, wait for list/form, create item, wait for writable name, fill name, save-and-close, reopen, verify persisted name/field properties, close. The full durable result confirmed `Наименование=Wait acceptance` after reopening. Repeating the identical idempotency key returned the existing summary without dispatch. The displayed list was captured and visually checked: exactly the synthetic saved item was visible.

The summary/full JSON measurements for this nine-step result were2461/5630 UTF-8 bytes using the same serializer, about56% fewer bytes. This is a reply-size example, not a tokenizer measurement, general performance claim or guarantee for other payloads.

A second batch waited for an impossible title for300ms, then included a Create command. It returned success=false/outcome_unknown=false/action_dispatched=false, failed_step_index=0, finished_steps=1,total_steps=2. The Create command did not run; a fresh observation of the original list succeeded on the same channel.

The acceptance helper's first connection attempt occurred before private EDT MCP was listening; it failed before any environment/base/action operation. After checking private endpoint readiness, the as-yet-unsubmitted acceptance ran normally. No unknown mutation was replayed. Real delayed-form timing/transport-loss branches are covered by deterministic worker contracts; the live happy-path waits satisfied on the first observation. There is no claim of runtime coverage for every form/control type or other platforms/Python versions.

## Cleanup and evidence

Owned client cleanup confirmed cleaned=true; owned Python environment cleanup removed its venv; native base cleanup confirmed database, launcher record and application settings removed; native creation-audit cleanup removed the fixture project. The own fixture root, workspace-specific Python cache witness and temporary native PNG were removed. Private EDT was closed and its original bundles.info restored byte for byte. The pre-existing private SDK/workspace is preserved.

Receipts, safe screenshots, scripts and logs remain in ignored `.runs/testclient-waits`, build/update site in `.runs/agent-bridge/20261110waitsfinal1`. The full operation result was saved before client cleanup. User EDT, user bases and global Python packages were untouched.

## Release review and repeated acceptance

Public candidate **1.4.0.20261110zzzwaits1** includes all reviewed corrections and managed-Python discovery guidance. Main/independent review confirmed and closed three findings: exact form signature around condition reads, known unsent read deadline handling, and single-summary counts. The new missing-environment next_call was also independently reviewed.

The final build passed **708 Java tests**, **30 worker +16 table Python tests**, standalone boundary and private p2 verifyOnly. Added contracts cover before/during-read same-title form replacement, guaranteed-unsent timeout before/after a confirmed mutation, receive timeout, exhausted overall budget, ambiguous element conditions, single-summary success/failure and exact managed preparation guidance without installation.

A fresh own disposable fixture repeated the nine-step persistence cycle on the exact release JARs. All9 steps verified; full result confirmed the reopened saved name. The repeated key returned the existing result. Summary/full JSON measured2460/5629 UTF-8 bytes using the same serializer, again about56% smaller. A standalone verified wait returned finished/passed/verified/total counts1. A missing environment returned the exact prepare_environment next_call with manual_pip_required=false; preparation and start then succeeded without python_path. The negative300ms wait stopped before the next Create command and preserved the channel. The saved list PNG was viewed and confirmed the synthetic item.

Release receipts are under ignored `.runs/release-waits`, artifacts under `.runs/agent-bridge/20261110zzzwaits1`. Cleanup receipts verify removal of this second fixture's own client, environment, base and project; only verified own temporary root/cache/snapshot are removed. Private EDT is closed and original bundles restored byte for byte before publication. The pre-existing SDK/workspace and all user EDT/bases remain untouched.
