# Direct TestClient: bounded table actions

The optional direct transport now adds `table_read`, `table_select`, `table_add`, and `table_set` to `edt_form_action`, including inside the existing batch of up to16 steps. Existing scalar actions and the default Vanessa route remain available. Use the [first-stage setup and recovery rules](direct-testclient-stage1.md).

## Observe and address a table

Start the Bridge-owned client on a permitted, native-EQUAL test base and capture `edt_form_state transport=testclient, client_id, view=elements`. A table is addressed by its exact element name, not its caption. `edt_form_state view=table` additionally requires `table` and `columns` (1..8 unique column element names). It returns the chosen column names/titles/types and the current row, without moving the cursor or changing selection. `current_row=null` does not prove that the whole table is empty.

All table actions require the current form `snapshot_id`, exact `expected_window`, stable `idempotency_key`, and `table`. A stale/foreign snapshot or replaced table handle refuses before dispatch. Capture a new snapshot after closing/reopening a form. In a batch, each table step has its own exact window precondition.

## Actions

| Action | Arguments | Verified result |
|---|---|---|
| `table_read` | `columns` required; optional `row_match`, `offset` (0..1000), `max_rows` (1..50, default10) | Returns only requested columns/row slice; `row_count` is the number matching the filter, `truncated` says more matching rows follow the returned slice |
| `table_select` | `row_match` required | Exactly one matching selectable row; the native search is followed by exact current-row readback |
| `table_add` | `cells` required | Requires a verified nonempty baseline; adds and finalizes a blank row, verifies exactly one distinct new current descriptor, then fills and verifies cells |
| `table_set` | `row_match` and `cells` required | Refuses zero or multiple matches; verifies the row around cell focusing/edit start and verifies all requested texts after editing |

`row_match` and `cells` map **column element names** to exact displayed strings, up to8 entries. Search is exact; native wildcard matches never bypass exact readback. `cells` accepts nonempty text up to4096 characters per value. Empty writes are rejected before claim/dispatch because the native null cell result cannot reliably prove a clear. Object/reference choosing, grouped columns, boolean editors and other special editor types are outside this stage.

For read assertions, add `expect_count` (0..1000) and/or `expect_rows`. `expect_count` checks the full matching count, not the slice length. An empty native selection is not proof of an empty table: it returns failed `table_rows_unproven`, `complete=false`, `row_count=null`, never a passed zero-count assertion. `expect_rows` checks exactly the returned slice as an unordered multiset, preserving duplicate rows; every expected row must have exactly the requested column keys. Existing `expect.window` and ordinary `expect.fields` still work. Reads without assertions are observations and report `postcondition_verified=false`; select/add/set have mandatory native readback.

Example inside an existing direct batch:

```json
{
  "action": "table_add",
  "expected_window": "Table items (создание) *",
  "table": "Lines",
  "cells": {"LinesItemKey": "K1", "LinesValueText": "Alpha"}
}
```

Then `table_read` with `columns=["LinesItemKey","LinesValueText"]`, `expect_count=1` and `expect_rows=[{"LinesItemKey":"K1","LinesValueText":"Alpha"}]` verifies the row. After saving and reopening the object, repeat a fresh read/assertion to verify persistence.

## Selection, limits and failures

Whole-table reading uses native selection. Therefore it is an **explicit journaled action**, not a hidden side effect of ordinary form observation. It clears prior selection, selects readable rows, collects their data and clears selection again. It can change cursor/selection and trigger form handlers. Add/select/set also change current-row state. Only the Bridge-owned client is addressed.

The supported scope is ordinary flat, multiple-selection form tables on Windows, with a provable nonempty baseline and the current filters/selectable rows. Adding to an unproven/empty table is refused before AddRow. Addition also requires that the new blank row can be finalized; a form that requires cell input before this can be refused. An added blank row can remain after a later refusal; cleanup does not remove it. A changed/inserting sort order or indistinguishable row descriptors can be refused rather than guessed. Single-selection tables without reliable bulk selection are refused; hierarchical/grouped-table coverage is not claimed. A unique match within this scope is not a database-wide uniqueness assertion. Bulk transfer still reads the native selected rows internally; JSON projection saves the agent's response size, not the whole native transfer.

Limits:1000 scanned rows,50 returned rows,8 requested columns,4096 characters per text,64 KiB projected row slice; existing128 KiB request,256 KiB reply and4 MiB native receive limits also apply. Unavailable cells remain explicit null, not invented empty strings, and cannot satisfy an empty-text assertion.

Zero/duplicate matches return a known failed result with `data_changed=false` (no requested cell input was sent; selection-triggered handlers are outside that assertion); later batch steps do not run. Guard/transport failure after possible effects remains unknown and closes the channel; no automatic replay. Per-cell or final readback failure can leave partial changes. A batch is not a transaction, and stop/cleanup does not roll back data. Read the durable original result before cleanup; do not substitute a new key/client for an unknown write.
