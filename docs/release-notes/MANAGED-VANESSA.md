# Managed Vanessa debug runs

For current MCP automation, start with [EDT-AI-AUTOMATION.md](EDT-AI-AUTOMATION.md), `edt_runner_profile` and `edt_development_run_start`. The steps below describe the lower-level PowerShell runner and earlier acceptance runs; they are not the recommended first-run procedure for a new agent. Obtain verified paths from the saved project profile instead of reconstructing them from historical PIDs or examples.

`tools/run-vanessa-debug.ps1` is a local runner, used alongside the installed
CodePilot debug MCP tools. It is not a new MCP endpoint or a change to Vanessa EPF.
Manager and named clients use `/i85`. `ЗавершитьРаботуСистемы=true` and
`ЗакрытьTestClientПослеЗапускаСценариев=true` request normal shutdown after a run.

The runner serializes operations with a file lock. Each run has unique manager
VAParams and client /Out paths. Cleanup matches those exact paths, test-process
roles and creation times, then rechecks identity before stopping processes.
It never enumerates all 1C processes for termination. Earlier untracked clients
must be attributed independently; only the current managed run is replaced.
Stopping an unfinished owned test may forcibly terminate that test's processes.

Agent sequence:

1. `-Action Stop`; inspect CodePilot `debug_status`, resume any suspended thread
   left by the preceding owned run, and recheck. A server thread can remain paused
   after its TestClient is closed; replacing Windows processes alone is insufficient.
2. Get the actual debugger URL from `edt_diagnostics launch_app mode=debug`.
3. `-Action Start -DebuggerUrl <url> -ParamsTemplate <va-params.json>`.
4. Poll `-Action Status` while using the independent CodePilot debug channel.
5. Read final JUnit and verify `OwnedPids=[]`. Use Stop for abandoned/failed runs.

The script's timeout is enforced on Status polling, not by a background watchdog.
Each Status returns a current snapshot, including process IDs and report totals.
`events.jsonl` records observed phase/step changes; it is not an exact event stream
for every intermediate step between polls. Native `steps.log` contains step logs
without buffering; `status.txt` contains Vanessa's own outcome code.

## Visual checkpoint

Use `-VisualCheckpoint` for one selected feature with one ordinary scenario.
The runner copies the feature into its private run directory and appends standard
Vanessa steps: save TestClient screenshot, write a ready flag, wait for Continue.
The source feature remains unchanged. Relative resources/includes in arbitrary
features may require adaptation when copying; the demonstrated smoke has none.

Status returns `Progress.Phase=awaiting_visual_review`, a screenshot path and
`ContinueAction=Continue`. The wait is 180 seconds. The agent inspects the image
through its image tool, then invokes `-Action Continue -RunId <RunId> -CheckpointId <Id> -ScreenshotSha256 <ScreenshotSha256>` using the values returned by Status. Old run identifiers, changed images, stopped runs, expired gates and duplicate continuations are rejected. It must not acknowledge
an image it has not inspected. An unsuccessful review calls Stop or leaves the
gate to fail by timeout. Continue means the agent authorizes execution to proceed;
it does not itself analyze the screenshot or establish UI correctness.

The current prototype has one final visual checkpoint. Additional checkpoints
can be added using the same standard-step protocol. Debugger suspension remains
an explicit independent CodePilot status; it is not inferred from a quiet VA log.

No commit, push or publication performed.

## Live acceptance 2026-11-05 MSK

- Manager PID 27256 and TestClient PID 19544 both had /i85. A replacement Start
  identified and stopped exactly these two owned processes. Killing a paused
  TestClient left the independent EDT server suspended; resume was required before
  the next test connection. The subsequent connection timed out until that state
  was cleared, which is why the agent sequence explicitly checks the debugger.
- Normal managed run va-managed-20261105-165322-9af529b2 passed one test with zero
  failures and both owned processes exited by Vanessa's normal shutdown settings.
- Visual run va-managed-20261105-170322-1c35df1e returned the current step and
  awaiting_visual_review on line 18. The agent opened screenshots/checkpoint.png:
  the 8.5 catalog list Направления тендера was visible, without the update-result
  modal, with the empty-list message. Continue was issued only after inspection.
- Final status passed/completed, JUnit one test/zero failures, OwnedPids empty.
- Earlier EDT test client 9572 was separately verified by its exact creation time,
  parent PID and debug/base arguments and closed. Final Enterprise process scan
  found none; Designer agents were preserved.
- No Java/p2 installation changes were needed for this runner; it lives in tools.
