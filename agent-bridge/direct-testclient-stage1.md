# Direct TestClient: first stage

The optional `testclient` transport drives a Bridge-owned Windows thin client directly. It does not require a Vanessa manager, VAExtension, a Testpilot MCP server, or a temporary internal/external processing. The existing Vanessa transport remains the default.

## Prerequisites and scope

- A permitted test database already bound to the EDT project, with native configuration equality `EQUAL`. Starting a client never updates the database.
- An explicit isolated `python.exe` with `pywin32` and `cryptography`. Dependencies are not installed automatically. The verification used Python 3.13, pywin32 312 and cryptography 50.0.2 in a disposable virtual environment.
- Windows thin client on the selected 1C platform. The verified platform is **8.5.1.1522**, using `/i85`; other platform versions have not received this runtime acceptance.
- Managed forms and ordinary controls. First-stage actions are `open`, `input`, `command`, `close`, and a batch of up to 16 such actions. Table row selection, arbitrary BSL, coordinates, keyboard automation and attaching to existing user clients are outside this stage.

The protocol client contains three pinned AGPL-3.0 files from ROCTUP/1c-testpilot v1.9.2; see `THIRD_PARTY_NOTICES.md` and the packaged `vendor/SOURCE.json`. Only the protocol is reused. The worker is private stdio, with no extra MCP listener.

## Short workflow

1. Discover the installed command schemas. Start `edt_test_client` with `action=start`, the permitted `project`, and absolute `python_path`. Save its job and poll `edt_operation_status`; keep the returned `client_id`.
2. Read `edt_form_state` with `transport=testclient`, `client_id`, and `view=elements`. It returns a `snapshot_id` and a structured `window` whose `title` is the exact action precondition. `view=values` requires an explicit `fields` array (up to 16 fields); snapshots cover ordinary controls, not table rows.
3. Send `edt_form_action` with the same transport/client, a stable `idempotency_key`, the observed `snapshot_id`, and `expected_window=window.title`. Optional `expected_form` checks the managed form name. Input uses `element` and `value`; commands use `element`; open uses an in-base `e1cib/list`, `e1cib/app` or `e1cib/data` navigation link.
4. Save `operation_id` and `job_id` before waiting. Check terminal job state **and** `result.success`; optionally require `postcondition_verified=true`. `expect.window` and `expect.fields` perform explicit checks after an action. Dispatch success alone does not verify business behavior.
5. `edt_test_client action=result` with `client_id` and `operation_id` reads the durable result without executing anything. A repeated completed action key returns its durable record directly, rather than a new job. A changed payload with the same key is rejected.
6. Stop/clean up with `edt_test_client action=cleanup` and wait for `cleaned=true`. This removes owned client files/results and terminates only the verified owned client. It does not undo database writes or remove the database.

`details` through `edt_form_state snapshot_id` is an immutable cached observation (`live_state=false`). Capture a fresh observation when the UI or current task has changed. Direct snapshots are bounded; pagination and Vanessa text deltas are not supported here. Dirty window captions on 8.5 can include ` *`; use the exact observed direct title.

## Batch

A batch uses the initial live `snapshot_id`, exact initial `expected_window`, and `steps` (1..16). Each step has its own exact `expected_window`, supported `action` and action arguments. Add `expect` to every step that needs a verified postcondition. For example: create from the list, input a name, save and close, reopen the current saved row, verify its name, close it. The whole request is bounded to 128 KiB; replies to 256 KiB and received protocol messages to 4 MiB.

Batches stop at the first failed precondition, failed postcondition or unknown outcome and return available partial results. They are **not transactions**: earlier successful steps remain applied. Do not restart an entire batch to recover a later step.

## Ownership and recovery

The JVM verifies project/base identity and child PID/start time/arguments; the worker checks ownership of the TCP listener before requests. Connections use loopback and do not attach to an arbitrary endpoint. Only SDK authentication settings are used; saved extra launch arguments are not imported.

Operations are durably claimed before dispatch. Loss of a result produces `outcome_unknown`, breaks the channel, and does not trigger an automatic resend. Investigate the original operation and actual data. A restart does not recreate a private pipe or silently relaunch a client. New keys or a new client are not a recovery mechanism for unknown writes.

Cleanup verifies owned paths and hashes, preserving unexpected or edited worker files for review. Closing the plugin also closes its private workers and owned clients. Existing Vanessa APIs and their behavior remain available.
