# Direct TestClient table stage — validation, 2026-11-10

This first section records the initial local implementation. The release-review correction and fresh acceptance below supersede its empty-table/addition guarantees.

Branch `codex/direct-testclient-stage2`, based on `8a68c5ea9e1e5e7b78eccc7a5d9ccc6c38df46e7`. Local work only; no commit/push/merge/release or installation into the user EDT in this stage.

## Evidence

Own EDT workspace `EDT-Agent-Bridge-Dev`, Windows thin client8.5.1.1522 with `/i85`, native-created catalog with generated forms and a two-string-column tabular section. No BSL was added. User EDT/TMS/user bases were not operated on; trust settings were not changed.

Live acceptance on candidate **1.4.0.20261110tcstage2dev4** passed:

- Current-row observation preserved selection and retained explicit null for the initially unavailable current row.
- Empty-table count assertion passed. A five-step batch added K1/Alpha, K2/Beta, K3/Gamma, asserted all rows, then changed only K2 to Beta revised.
- Save/close/reopen and exact three-row assertion passed. A second live complete cycle had already passed on dev3.
- Scoped read returned only the requested one column of K2. A one-row offset page reported the full count and truncation correctly.
- Exact unique selection of K3 passed. A deliberately added duplicate K2 caused table_set to return ambiguous_row with no data write; the following batch add of K999 did not run. Exact four-row assertion proved the original K2 value and duplicate were unchanged and K999 absent.
- Missing-row modification returned known row_not_found/data_changed=false. The channel remained usable.
- Wrong-window and stale/expired saved-form snapshot actions were rejected with action_dispatched=false.
- Reusing the completed duplicate-add key returned its durable terminal result without another job; subsequent count remained4.
- Native PrintWindow screenshot of the final reopened form was viewed: four expected synthetic rows, including the unchanged K2 and deliberate duplicate. Screenshot retained with ignored receipts.

The final local package **1.4.0.20261110tcstage2final1** differs from dev4 only by the diagnostic `selection_changed=true` flag for table_add (adding a row changes the current-row state). This metadata correction has a contract assertion; the live cycle above is attributed to dev4 rather than claimed as a separate final1 run.

Final checks: **683 Java tests**, **7 existing worker tests +11 table tests**, standalone product boundary, exact packaged worker/tables/vendor byte comparison and isolated p2 director verifyOnly. Tests cover malformed table payloads/limits, required unique criteria, projection/duplicate counts, hidden/read-only/unknown columns, empty-write refusal, focus redirection to another row, post-handler readback changes, loss after a write without replay, and explicit null durability. Lost-reply and focus-handler injections are contract tests, not claimed as live network-failure injections.

An initial live calibration found a wrong request envelope for GET_CURRENT_ITEM after adding a row. The unknown operation was not replayed: the own window was captured, the protocol envelope was corrected from the pinned upstream implementation, and that own client/base were removed. Subsequent live candidates completed the full cycle.

After private EDT restart, an initial fixture reported native NOT_EQUAL despite no differences in a read-only compare/refresh. Start correctly refused. No equality guard was disabled. The owned empty base was removed and a fresh disposable fixture was provisioned after each candidate load, yielding native EQUAL. This is an observed environment limitation; a general synchronization fix was not included.

## Cleanup and retained artifacts

All four created disposable bases (initial/dev2/dev3/dev4) were removed through native lease/preview/apply cleanup. Each receipt confirmed database_removed, launcher_record_removed, application_settings_removed and cleanup_verified. The own audit project was removed through Eclipse APIs; all three owned clients were cleaned. The own fixture root and temporary Python environment were deleted. Original private EDT bundles.info bytes were restored and that EDT was closed. No own related 1C/EDT process remained in the final inventory.

Retained: source changes, local build/p2 artifacts, sanitized operation receipts and reviewed synthetic screenshots under `.runs/direct-testclient-stage2`. The original SDK test installation/workspace pre-existed and was preserved. Main receipts: `state.json`, `final-cycle.py`, `tests-final-package.log`, `worker-tests-final-package.log`, `table-tests-final-package.log`; package receipt under `.runs/agent-bridge/20261110tcstage2final1/p2-verify.json`.

## Limits

Only flat multiple-selection tables and nonempty ordinary InputField text edits were accepted. Special/reference editors, single-selection/hierarchical tables and other platforms remain unverified. A table read changes owned UI selection and transfers bounded native rows before JSON projection. Batches stop without rollback. See [API and recovery rules](direct-testclient-stage2.md).


## Release review and fresh acceptance — 1.4.0.20261110testclient2

Three P1 findings and one P2 were fixed after primary and independent review; all fixes and the AddRow-proof follow-up were independently rechecked. See [review](review-20261110-direct-testclient-stage2.md). Empty/unavailable bulk now fails rather than proving zero rows. Addition requires a verified nonempty before set and exactly one distinct new descriptor after blank-row finalization. EndEdit cannot rebind the target row; final window/fields share one capture.

Fresh live acceptance ran on the exact release build in the own isolated EDT and a newly created disposable base8.5.1.1522. A native-created explicit object form had a small server initializer that added K1/Alpha to new synthetic objects. From that seeded baseline the guarded batch added K2/Beta and K3/Gamma, asserted all rows and changed K2 to Beta revised. Save/close/reopen and exact three-row assertion passed, followed by scoped projection, offset page, unique K3 selection, deliberate duplicate refusal/batch stop, missing-row refusal, stale/wrong-window guards and durable reuse of completed addition. Final reopened four-row screenshot was viewed. Cancellation, EndEdit redirect and unprovable-empty fault injections are regression tests, not claimed as live handler fault injections.

Final release validation: **683 Java tests**, **7 worker +16 table contracts (23 Python tests)**, standalone boundary, exact packaged resource bytes and private p2 verifyOnly. The own client/base/project were removed through native lifecycle; database/launcher/application-settings removal was confirmed. The own fixture root and Python environment were deleted, private EDT closed and its bundles.info restored byte for byte. User EDT/bases and trust settings were not operated on. New evidence is under ignored `.runs/release-directtc2`.

Runtime scope is intentionally narrower than the initial checkpoint: empty/unprovable bulk is incomplete, and adding to an empty/unproven table is refused. Forms that cannot finalize a blank new row, change other descriptors on insertion or provide indistinguishable rows can also be refused before cell input. A refused operation can leave a newly added blank row; no rollback is promised.
