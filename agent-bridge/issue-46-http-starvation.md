# Issue46: HTTP worker starvation and bounded IO

## Evidence boundary

Issue46 reports version `1.4.0.20261113zreview1`, 53 parallel managed-form reads, later intermittent transport failures even during sequential validation, and successful later status without a confirmed restart. There are no original server logs, thread dump or HTTP trace. We cannot identify which original socket stalled, whether it was a slow body/writer or a different cause. The experiments below establish concrete server failure paths with the same externally visible loss/recovery; they do not reconstruct unavailable historical events.

The previous hypothesis of a session monitor held across routing/writing is incorrect for this exact version. Historical source `fbc97f9` already releases the short session lease before route/IO.

## Confirmed mechanism

The historical HTTP executor has core4/max16/queue64. With 53 concurrent submissions, only four run while49 queue: max16 starts growing only after the queue fills. Reading headers/body and writing responses use blocking socket IO without an active-phase deadline. Four stalled sockets can indefinitely occupy all core threads; later reads, validation and status then wait behind them. Closing those peers releases the threads and restores the same server/session. At16 running +64 queued, AbortPolicy can also reject before an HttpExchange/MCP error is available; the original53 alone does not prove that saturation threshold was reached.

Tool success is recorded before network delivery. Thus completed operations with zero tool failures can coexist with missing client responses. `ToolListChanges` in the client stack and the old advertised listChanged capability are not evidence identifying the failed socket or operation.

## Actual loopback socket experiments

The exact historical transport was compiled separately from `fbc97f9`, using a controlled router that returns a completed8MiB result to a peer whose receive side stops reading. This removes EDT/base behavior from the transport fault. No user EDT or base participated.

| Experiment | Historical zreview1 | reports2 | reports3 |
|---|---|---|---|
| Four nonreading response peers, then ping | 512ms client deadline | 200,2–4ms | 200,9ms |
| Following53 small reads while writers occupied | 53 transport deadlines | 53 explicit busy replies | 53 explicit busy replies |
| Read after11s, peers still open | Not tested | Still busy | Completed;4 response IO deadlines |
| Four partial request bodies, then ping/53reads | Ping deadline;53 transport deadlines | Same failure | Live EDT:4 reads completed,49 explicit busy,0 transport failures |
| After closing stalled peers | Same server/session recovers | Recovers | Does not require client close |

Final own-EDT acceptance used actual `inspect_form_layout` with properties/titles/max_items1500. The four partial bodies expired server-side after10.004s; maximum read latency was100ms in the recorded acceptance. Separate16 partial bodies and16 partial headers expired after 10–10.6s in the final run, without client-initiated close; the same MCP session answered afterwards. Counters showed36 request IO timeouts and no response write errors. This demonstrates recovery; it does not promise zero latency while every framing worker is occupied.

## Fix and semantics

`McpHttpIoDeadline` owns one active IO phase. Its timer interrupts that phase's worker. With the supported JDK HTTP provider, blocking SocketChannel interruption closes the associated socket. Timer expiry and finish share a short monitor; a cancelled timer cannot interrupt a later request on the reused pool thread. Cancelled timers are removed, ThreadLocal state is cleared, and listeners capture their original scheduler across stop/restart. There is no reflection, JVM-global timeout property, EDT restart or arbitrary thread cancellation.

The framing/control executor has16 workers and a16-entry queue, bounded by the same previous maximum worker count. Header/body processing has a10s active-phase budget. That guard finishes before heavy business dispatch. Response delivery gets its own10s budget only after routing returns. These are active-phase limits, not an end-to-end business deadline; queueing and JVM scheduling can add time. Long business operations retain their existing semantics and are not interrupted or replayed by the IO watchdog.

Malformed/incomplete HTTP cannot receive a trustworthy JSON-RPC result: its socket closes without dispatch. A response timeout after an operation completed is delivery uncertainty, never a reason to repeat a mutation. Existing durable operation IDs, unknown-outcome barriers and explicit -32001/server_busy before dispatch remain unchanged. Health transport metrics distinguish `request_io_timeout`, `response_io_timeout`, routing and successful response writing. Response writing still does not prove client consumption.

## Validation and review

Six new mandatory regression tests use actual sockets for partial headers/body, paused readers, slot recovery without re-execution, cancelled timer/pool reuse and a business route lasting1200ms across a600ms IO budget without interruption. Mandatory final suite:1341 Java tests,139 direct TestClient Python tests,59 tooling tests (one Windows symlink privilege skip), OSGi/product-boundary and managed/startup PowerShell checks. Independent source review found no open confirmed blockers.

Build/runtime/review receipts and historical/current comparison logs live outside the checkout in the owned task run. Native DLL, EPF and their source/template pins are unchanged from reports2. No user EDT/database was launched, modified or stopped.

A repeated final-version suite initially failed two pre-existing asynchronous job tests (`UiRunServiceTest.preClientBlockPreservesKnownOutcomeWithoutResolvingMissingClient`, `BslMethodBatchJobsTest.pendingContinuesOriginalRead`) while the owned EDT was starting. Their assertions were retained; the final mandatory run was repeated with the owned EDT stopped. The first complete same-source candidate run had already passed1341 tests. Logs of every run are retained; neither a selected pass nor a failed run alone replaces the final provenance receipt.

A further unchanged-source repeat passed those two tests but exposed the existing editor test's80ms budget versus a50ms scheduled completion: only30ms of scheduler margin. Its regression was stabilized without production editor changes: stage one completes, the original2s request budget expires between stages, then the pending second stage must time out in less than1s and be cancelled. An implementation incorrectly granting a new2s budget still fails. No assertion was removed and failed run logs remain retained.
