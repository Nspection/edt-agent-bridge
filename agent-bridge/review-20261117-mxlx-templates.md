# Review: native spreadsheet artifact resolution

Root cause: legacy inspect/render/ensure hardcoded Template.mxl while actual EDT sources use Template.mxlx. Inspection encoded missing/corrupt reads as successful grids. Rendering could create a shadow MXL; raw text edit did not classify MXLX as structured.

## Architecture and review

SpreadsheetArtifact is the shared boundary for exact sibling discovery, native binary/XML codec selection, bounded/secure reads, source snapshots and workspace writes. Both siblings reject. Existing format is preserved, creation uses MXLX, and errors remain errors. There is no fake MOXCEL fallback or codec fallback. Detached generated sheets come from native SheetFactory; parameter/bold formatting uses the existing SpreadsheetTemplateWriter rather than a second implementation. CommonTemplate and owner-child paths are validated; traversal and linked paths are rejected. XML preflight rejects DTD/external entities before native load. CAS checks both sibling hashes; serialization and native load validation happen before source writes. This is cooperative workspace/source protection, not a claim of immunity to adversarial parent-directory replacement by another process. Dirty-project-editor checks run on the own UI thread before mutation; no editor is saved or closed.

Independent review identified disk/workspace existence drift and post-BM partial creation. Initialization now trusts its filesystem snapshot rather than stale IFile existence. Failures after BM commit report the already-created FQN/metadata_created=true/artifact_initialized=false, are non-retryable and prohibit repeating add. Metadata is not deleted to fake rollback. Runtime tests confirmed this partial-result diagnostic.

Self-review covered the actual source diff, codec boundary, request paths, guard bypasses, error propagation and acceptance receipts. Independent review covered these and the exact final artifacts/receipts. No open confirmed blocker remains for the documented MXLX scope.

## Validation

Final artifacts passed1397Java tests and152direct-TestClient Python regressions, OSGi bytecode linkage, source/provenance/package and mandatory33managed-PowerShell/3CLI/13startup contracts. The tooling suite retains its normal single host-privilege skip. Focused regressions cover codec selection/ambiguity/missing, UTF16 XML declaration handling, corrupt XML/DTD, byte bounds, traversal, supported symlink rejection, CommonTemplate contracts and uppercase MXLX raw-edit/copy guards.

Source-only own SDK acceptance used a new owner-marked isolated workspace/project, private MCP port and hidden Windows desktop. Native-created empty MXLX, section full replacement, CommonTemplate, normal Номер/detail Товар parameters and named areas passed. Existing MXLX never gained an MXL sibling. A reflective own harness checked stable bytes after repeated initialization, stale-sibling CAS refusal and source-preserving binary-codec refusal. Real MCP negative checks covered ambiguous read/render, corrupt/DTD/missing reads, SDK binary refusal and post-BM partial creation. No database/1C client was launched. The own SDK was stopped; source fixtures remain outside checkout as evidence.

## SDK limit

MoxelResourceMxl in the installed moxel18.0.0.v202607090722 SDK calls deserializeMxl before setting its StreamInPersistenceStorage reader. An independent direct SDK invocation reproduced the same ListInStream NPE as the product route; javap verified the order. No SDK-internal reflection/fallback patch is shipped. Successful binary MXL support is not claimed on this SDK. Hash checks proved that the validation failure happens before source write and retains the original binary bytes. Format-aware binary selection remains available for working SDK versions; there is no auto-conversion. The separately reported form-command timeout is not claimed fixed. Native DLL/EPF/protocol unchanged, user EDT/x5/databases untouched.
