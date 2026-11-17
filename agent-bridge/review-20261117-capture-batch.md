# Review: capture_batch native form integration

The batch previously treated every normal snapshot response as successful, even when the Form snapshot deliberately returned a refusal and next_call without PNG. It also discarded this diagnostic.

## Own and independent review

The batch now routes the actual Form page through a fresh native projection; Module and other pages use the ordinary snapshot. Selectors are applied once. Finished capture must match the expected tab and supply an existing decodable PNG of matching positive dimensions. Explicit failure or unverified native generation is rejected. Compact results retain scope/freshness/refusal diagnostics without duplicating the form model. Alignment and cleanup failures stay separate.

EDT may reset the page of a reused editor during openEditor. Loaded editors' indices are recorded beforehand without restoring dormant parts; batch restores the original tab. Failed preparation performs restoration in service cleanup, reporting failure explicitly. New clean editors close; dirty and pre-existing editors are retained without save/discard.

Independent review found the finished-result tab race and the failed-open restoration gap. Both were fixed and regression-tested. Final independent source/artifact review reports no open confirmed P1/P2. The reviewer independently recalculated JAR hashes, read final test/runtime receipts and visually inspected both native form PNGs, grid and Module PNG.

## Validation and limits

The exact artifacts pass 1362 Java tests, 139 direct TestClient Python tests, tooling/provenance/package checks (one symlink-privilege skip), OSGi linkage, 33 managed PowerShell contracts and 13 startup-input contracts. Build/verification/package receipts bind source inputs and artifacts.

Live acceptance used only an owned EDT on a separate invisible Windows desktop and an owned source-only fixture containing two forms: both native PNGs and alignment derivative; Alpha/Second markers visibly present; new-editor closure; pre-existing retention and Module restoration; readable generic Module PNG; invalid-selector and missing-file refusals; unchanged source bytes; no remaining editors or capture buffers. Own EDT was stopped afterwards. No database was created or used; user EDT, x5 and user databases were not accessed.

A negative check of the same final build before the lab prepared usable window geometry returned projection_unavailable, no PNG, captured=false and all_succeeded=false. The lab then prepared only its own window and passed capture. This does not relax visibility/SDK guards. Native images show the form body, not editor chrome. Visual correctness, viewport clipping equivalence, arbitrary user forms and business/database scenarios are separate acceptance boundaries. Native DLL, protocol and host template are unchanged.
