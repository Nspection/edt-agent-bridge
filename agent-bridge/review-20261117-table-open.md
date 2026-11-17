# Review: typed existing-row activation

The MCP previously supported reading/selecting table rows, but had no typed activation for an existing record when no separate Edit button was exposed. table_open uses the pinned CHOOSE_ROW command after exact-row uniqueness and fresh source/current-row guards, then requires a changed form identity and exact target_form. The contract explicitly describes choice/close/handler effects; it does not invent a DynamicList/choice discriminator or read-only record reference. expect.fields is recommended to assert record data in the destination.

## Own and independent review

Both native-search and modern bulk-fallback paths recheck the full current descriptor before activation. Source expected_form is mandatory for each batch step; target_form is distinct and bounded. Unverified target or lost action/commit reply flows into the existing conservative unknown barrier. The same durable key returns the receipt without another UI command. No coordinate input, generic keyboard, default child button, guessed navigation URL or fallback is used.

Live testing found a pre-existing search bug: an absent second match returned fully decoded GOTO_ROW OperationError status12/value_not_found. The original failed operation was preserved, observed read-only at the final row and never replayed; that client was cleaned. The fix normalizes only that exact typed status from criterion search, followed by the normal source guard. Other statuses, incomplete timeouts and CHOOSE_ROW errors remain errors. Initial miss, duplicate miss and foreign action errors have regressions; two additional external checks cover reverse-search miss and source drift after a completed miss.

Independent final review found no open confirmed P1/P2, recalculated exact JAR hashes/source/resource matches, read all final receipts and inspected the owned card PNG showing OWN001 / OWN_ROW_1.

## Validation and limits

Exact final artifacts passed 1391 Java tests, 152 direct TestClient Python tests, mandatory OSGi linkage, tooling/provenance/package checks (one symlink-privilege skip), 33 managed PowerShell contracts, three empty-workspace CLI checks and13 startup contracts. Two additional negative-search guard checks passed against actual production Python.

Live acceptance used a newly owned isolated SDK/workspace and native-created minimal catalog with four own seeded records and List/Object forms, in a new disposable8.5.1.1522 base. The final client opened existing OWN002 and verified exact target identity/fields; repeating the original key returned the same operation without a new job. Missing-row search returned known row_not_found without row activation and the channel stayed mutable. An intentionally wrong target after activating OWN003 retained unknown/barrier with usable transport; subsequent calls were observation/stop/cleanup only. The original unknown operation was not repeated.

A separate actual edt_ui_run passed three steps: open list, assert all four exact rows, activate OWN001 and verify destination fields. Its immediate all-popup capture honestly reported incomplete transient inventory; that is not claimed as full capture success. The card PNG was readable, and a later selected-window-only capture succeeded with stable complete scope. Three owned clients, their task/preparation session, private Python and the disposable database/launcher/UUID settings were cleaned through guarded APIs. The own SDK stopped. A cold own-SDK equality refresh had reported NOT_EQUAL/NO_CHANGES; only that exact owned base was fully reloaded to native EQUAL, without weakening launch guards.

No live8.3, arbitrary custom handlers, choice-form behavior or hierarchical tables are claimed verified. The operation is CHOOSE_ROW activation with possible side effects, not an open-only guarantee or read-only navigation-link getter. Source, caption and form target do not by themselves prove record UUID. Native DLL/EPF/protocol are unchanged. User EDT/processes/x5/databases were not accessed. The separate form-command transport timeout report is not claimed fixed by this release.
