# Review: atomic TestClient preflight before once-consent

The prior startup claimed scoped consent before checking four pending client records. Stopped records still occupied capacity, and the thrown string omitted all blocking IDs. The fix reserves a unique capacity slot atomically before SDK/Python/consent work and supplies a typed prelaunch refusal, rendered as structured MCP job result. No process is assumed dead from a stopped receipt, no existing client is attached, and no records are automatically cleaned.

## Own and independent review

A separate reservation object owns its unique directory and CREATE_NEW nonce marker. Capacity gating uses only bounded stored-record reads, never client lifecycle locks, process queries or SDK operations. Consent availability is checked without consuming once, Python is selected, then a fresh lifecycle check and atomic once claim precede the durable start receipt. Commit removes only the owned marker. Failures before commit remove only the exact owned marker and empty directory; foreign files and changed witnesses are retained. Unknown business results and postlaunch barriers retain their existing conservative behavior.

The first real Windows run found BasicFileAttributes.fileKey=null and three failed cleanup regressions. This was corrected using a bounded exact nonce witness, canonical paths, NOFOLLOW checks, optional nonnull fileKey matching and nonrecursive deletion. The failed run is not used as acceptance. Independent source review and exact artifact/provenance review report no open confirmed P1/P2; the reviewer independently recalculated JAR hashes and checked final receipts.

## Validation and boundaries

Final exact artifacts passed 1389 Java tests, 140 direct TestClient Python tests, OSGi linkage, tooling/provenance/package tests (one symlink-privilege skip), 33 managed PowerShell contracts, three empty-workspace CLI checks and 13 startup contracts. Regressions cover four stopped blockers, parallel last-slot claims, retained once, missing-consent decisions, preclaim errors, foreign files, modified markers, unproven records, cleaned history and aggregate prepare diagnostics.

Live acceptance used only a newly owned empty SDK workspace on a separate Windows desktop with four synthetic stopped receipts and no database/project. A real edt_test_client start returned a DONE job containing exact blocking IDs, status/cleanup calls, no-dispatch/no-consent flags and unchanged original record bytes. After MCP cleanup of one synthetic receipt, another start reached unbound-project validation and failed conservatively; the Windows reservation marker and directory were removed. All four synthetic receipts were cleaned through MCP, and the own SDK stopped. No real 1C client or database was created, launched, updated or inspected. This is prelaunch acceptance, not a new successful business-client run.

Native DLL/EPF/protocol are unchanged. User EDT, user processes, x5 and user databases were not accessed. The separate colleague report of a form-command preflight transport timeout has no root-cause proof here and is not claimed fixed by this release.
