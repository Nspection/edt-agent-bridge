# Review: form/native lifecycle follow-up

Scope: managed-form event stubs, Native host observation/recovery and native form raster capture.

## Own review

- Event aliases resolve to the canonical SDK Event before generation. The generator now matches the installed SDK default: canonical names ending in AtServer use AtServer, other events use AtClient. Environmental availability and the optional server wrapper restriction do not describe invocation context. Existing method text is preserved.
- Host mutations remain serialized with reentrant lifecycle locks, including ordered two-host cleanup. Busy public status reads the atomic ownership receipt without SDK/BM readiness checks; it cannot authorize execution. Fresh execution admission remains under the lifecycle lock.
- Cleanup checks source absence again after source/BM synchronization. EQUAL permits skipping a redundant FULL, while stored configuration comparison independently verifies absence before completion. The skip is exposed through status.
- Form capture uses SDK ImageData or the current native renderer cache with unique bounded buffers. It never changes global SDK flags/services or substitutes editor renderer fields. Output is the native form body, not editor/window chrome. A separate raster receipt preserves the original projection freshness boundary.
- SDK callback exceptions may be swallowed. A sticky terminal exchange result rejects paint/decode/restore/other callback failures, including failures after an image was decoded. Successful read-only SDK passes are bounded to four; failure prohibits another native call. Temporary layout sessions are disposed per BM attempt.
- The SDK may allocate its hidden child popup cache on cold render. Growth is bounded to four, parent/visibility are verified, buffers cover the full bound, existing SDK children are not deleted. No new top-level window or activation is used by this allocation.

## Independent review

The independent reviewer inspected the final implementation and the owned cold Alpha/Beta PNGs. Confirmed findings about clipping/composition and swallowed callback failures were resolved. Composition was removed entirely in favour of the native body. No open confirmed P1/P2 remains. The reviewer did not operate user EDT or databases.

## Validation

The full standalone suite passes: 1353 Java tests, 139 direct TestClient Python tests, tooling/provenance/packaging tests (one platform symlink-privilege skip), OSGi visibility, 33 managed PowerShell contracts and 13 startup-input contracts. Source/artifact verification receipts bind the final package inputs.

Live acceptance uses only an owned isolated SDK/workspace and newly provisioned disposable 8.3.27.2325 file bases: RU/EN server event generation, byte preservation of an existing method, cold Alpha capture, Beta page selection and repeated capture, grid, generic snapshot redirect, buffer release, real FULL/cleanup status polling, independent database absence, and recovery after an EQUAL checkpoint without a second FULL. Disposable databases, launcher records and UUID access settings are removed through preview/hash cleanup; owned EDT is stopped after acceptance.

Limits: native body capture does not certify editor viewport clipping/scroll equivalence or visual correctness. The SDK completion contract is version pinned. These checks do not certify unrelated business scenarios or other platform versions. Windows file observers that do not share DELETE access can interfere with atomic journal replacement; acceptance polls through MCP and retains atomic fail-closed writes.
