# EDT Agent Bridge 1.4.0.20261117templates1

Existing XML spreadsheet templates were looked up as `Template.mxl`, so `inspect_template` returned a successful “file not found” grid for actual `Template.mxlx`. `render_template` could create a binary sibling instead of replacing the real layout.

The three legacy creation/inspection/render routes now share format-aware artifact resolution and native codecs. Existing MXLX stays MXLX; new child spreadsheet artifacts use native MXLX. CommonTemplate.Name is supported as well as owner-child templates. Two siblings are an explicit ambiguity: no guessing, auto-conversion or source write. Missing/corrupt/unsafe XML is an error. Direct text edits and copy-transform writes to `.mxlx` are blocked alongside `.mxl`.

Creation uses EDT SheetFactory defaults. Section rendering reuses the typed spreadsheet writer for parameter fill and bold formats, preserving ordinary/detail bindings and named areas. The fabricated MOXCEL fallback was removed. Reads are limited to2MiB and inspection/render to1000rows×100columns; use bounded `edt_spreadsheet` reads for larger layouts. Writes require stable source/sibling hashes, unlinked local paths, no unsaved project editors and a successful native round trip before the workspace write. An initialization failure after BM creation explicitly reports metadata_created=true and artifact_initialized=false, with the existing FQN and no instruction to repeat add.

Validation:1397Java and152direct-TestClient Python regressions, mandatory linkage/provenance/package/PowerShell checks, independent exact-artifact review and source-only acceptance in a new isolated own EDT project. Native MXLX creation, full replacement, CommonTemplate, normal/detail parameters, named areas, repeat initialization and stale-sibling refusal passed. Ambiguous/corrupt/DTD/missing cases and post-BM partial creation were checked through the own MCP.

Known SDK limit: the test SDK moxel18.0.0.v202607090722 has a native binary MXL loader NPE (stream initialization order). Binary success is not claimed on this SDK. The generic native MXL route remains for SDKs where it works; on loader failure render refuses before writing and preserves the existing bytes. No SDK internals are patched, no automatic XML conversion occurs. This release fixes MXLX; it does not claim to fix the separately reported form-command transport timeout.

Native DLL/EPF/protocol unchanged. No client or database was started for this change. Only the own SDK/workspace was used and it was stopped; user EDT, x5 and databases untouched.
