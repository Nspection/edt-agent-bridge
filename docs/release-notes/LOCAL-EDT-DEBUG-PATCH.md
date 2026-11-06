# Local EDT debug bridge patch

Prepared on 2026-11-05 from repository commit `846b37eafa6fa0b41ca5b9c3729148114c10e384`.
Target: EDT 2026.1 and the installed CodePilot core `1.3.9.20261019-0402`, Java 17.

## Problem and implementation

The installed core always throws `EDT_SERVICE_UNAVAILABLE` from `setBreakpoint`
and `evaluateExpression`. Its normal application launcher uses ProcessBuilder,
which does not create an Eclipse debug launch.

The patch uses EDT's injected `IBslBreakpointFactory`, registers each breakpoint
with Eclipse's manager, and reuses the same marker for repeated source locations.
Conditions and enabled state are supported. Paths are normalized to src-relative
paths, with bounds and traversal checks. Failed new markers are removed.

Expression evaluation uses the selected suspended frame's evaluation engine and
an EDT evaluation request with a callback and a 15-second timeout.

`edt_diagnostics(command=launch_app, mode=debug, project_name=..., launch_config_name=...)`
launches an existing saved EDT configuration in debug mode without a build.
If the name is omitted, exactly one RuntimeClient configuration must match the
project. An existing live launch is reused. The response includes debug server
URLs. RemoteRuntime requires a valid explicit HTTP(S) server URL before launch.
A launch without an active target URL returns an error rather than false success. Run mode retains the existing process-based launcher.

## Build and verification

Run `tools/build-edt-debug-patch.ps1`, then `tools/test-edt-debug-patch.ps1`.
The lightweight build compiles only the changed classes against the exact active
installed bundles. It does not require upgrading the IDE to the repository's
newer full-reactor target. The original core must be unsigned and match the pinned
version. Only the five debug/launcher class entries change in the resulting JAR;
the original manifest, all other classes and nested libraries remain identical.

Twelve unit tests cover conditional registration, marker reuse, disabled state,
Windows/src path normalization, missing files, out-of-range lines, traversal,
cleanup after failure, debug preview, actual launch mode/build flags, reuse and
project isolation, invalid RemoteRuntime URLs, and the actual EDT evaluation
request builder requiring a UUID. These tests do not establish live EDT/1C operation.

## Install and rollback

Save work and close EDT and EDT Start. The installer refuses to run while they
are active. Run `tools/install-edt-debug-patch.ps1`, then restart EDT with `-clean`.

This is a local development override, not a published p2 upgrade. The installer
copies the patched core into `EDT_HOME/local-debug-patch/` and redirects only its
existing bundles.info entry. The original shared-pool JAR and p2 profile remain
untouched. A backup and checksummed installation state are saved. The bundle
version intentionally remains the installed version, so identify the override by
the installation state and file checksum. An EDT/p2 update may replace the entry;
remove the override before upgrading and rebuild against the new target.

To revert, close EDT and EDT Start, run `tools/install-edt-debug-patch.ps1 -Rollback`,
and restart with `-clean`. Rollback refuses if another operation changed the core
entry after this installation.

## Live acceptance still required

1. Set a breakpoint at line 6 of `Catalogs/НаправленияТендера/Forms/ФормаСписка/Module.bsl`.
2. Start the saved `TMS_GIT Тонкий клиент` configuration through
   the new debug mode. Confirm EDT supplies the debugger URL and an active target.
3. Add `/Debug -http -attach /DebuggerURL <actual-url>` to the Vanessa TestClient profile.
   Do not guess the server port or attach the Vanessa manager as the test target.
4. Run the existing read-only scenario that opens the catalog list asynchronously.
   Keep the debugger control channel available while the scenario is waiting.
5. Wait for suspension, inspect variables, evaluate a simple expression, step over,
   resume, and confirm the same scenario finishes with zero failures.
6. Remove the temporary breakpoint. Preserve the report and observed limitations,
   especially Vanessa/platform timeouts and any stale frame identifiers.

The patch does not add exception-breakpoint MCP tools or claim their acceptance.

## First live run, 2026-11-05 16:30-16:32 MSK

EDT RuntimeClient supplied http://localhost:1552/.
Vanessa launched its named TestClient with -http -attach and reached breakpoint
345055 in the catalog list creation handler. Reading locals returned Отказ=False
and СтандартнаяОбработка=True. Step-over and resume commands were accepted.
Expression evaluation failed because EvaluationRequest requires expressionUuid;
debug2 supplies UUID and an empty interface list, verified against installed EDT
classes. This fix still requires deployment and live evaluation acceptance.
Vanessa's final assertion failed because the application-update-results window
was active. The scenario now closes existing client windows before opening the list.
The full cycle is not yet accepted. Preserve the update-handler failure separately;
closing its result window does not establish migration success.

For a second local patch installation use -Upgrade. It validates the previous
override and preserves the original shared-pool rollback entry and prior state.

## Successful live acceptance, 2026-11-05 16:46-16:47 MSK

Installed debug2 SHA256: 20EAE2958B2EB91BE21536E9F3403D8AF11E044D9744A1F54857C8F19E495144.
RuntimeClient launch returned an active target URL without an update dialog on
this synchronized project. Vanessa ran in a separate native manager process,
with TestClient attached to the EDT server. The condition СтандартнаяОбработка
was set through MCP on breakpoint 345055 and the breakpoint was hit.
Expression 1 + 1 returned numeric 2; Отказ returned False. Locals were read as
Отказ=False and СтандартнаяОбработка=True. Step-over caused another suspension;
the newly reported thread ID was used for resume. The scenario conditionally
closed the late application-update-results window and activated the expected list.
Final JUnit: tests=1, failures=0, errors=0, skipped=0, time=94.079 seconds.
Run directory: C:\Users\developer\AppData\Local\1C\1cedtstart\projects\TMS\tests\qa\results\va-debug2-final-20261105-164546
Temporary breakpoint 345055 was removed after success.

Metadata expressions evaluated in the live base confirmed ТипРезультата exists
in all four processed-result table sections of РегистрацияГрузовыхМест.
This is schema verification, not a successful rerun of the migration handler.
The old failed-update report is deliberately not treated as resolved.

Limits: exception breakpoints have no new MCP tool; initial launches of an
unsynchronized project may still invoke EDT update dialogs. This successful run
does not establish autonomous update handling for every dirty-project state.
No commit or push performed.
