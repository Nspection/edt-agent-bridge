# Form command-bar regression review — 2026-11-16

Astra medium independently reviewed the SDK, implementation and subsequent fixes. The main agent reviewed the final diff and tested the native model/MCP in an owned isolated EDT. No user EDT, database or x5 file was changed. Scope is this form regression, not a new universal project audit.

The old search and parent traversal followed getItems only. Buttons in autoCommandBar and table command bars were absent; a real old-version fixture reproduced METADATA_NOT_FOUND for name and ID. FormItemTree now uses containment, never cross references. Parent mutation requires actual parent.items membership. ID allocation sees every contained FormItem. Inspector traversal also follows direct contained FormItems of non-container owners, including FormField.contextMenu; reviewed P2 inconsistency was fixed before release.

Final MCP acceptance: inspection by name/ID, auto/table/context button defaultButton mutation, moving a table-bar button into a normal group, removing auto/context buttons and the moved button, depth/count truncation. Table effective values stayed unchanged throughout.

Layout baseline: a valid owned source with two tables (height 5, false flags; height 7, true flags) was imported. A native set of an already-true ordinary button triggered owner export. All table eGet/eIsSet pairs were identical before/after. Explicit false disappeared from XML, while height 5, height 7 and true values survived. This is canonical serialization, not reproduced semantic layout loss. No XML restoration or bulk table setter was introduced. The original user project was not used for mutation/reproduction.

1282 Java /179 Python plus 33 controller, 3 CLI, 13 startup contracts; exact-artifact OSGi/boundary/provenance and copied-profile p2 verifyOnly. Four targeted model regression tests. Own object removed through validated native delete; own EDT stopped; no infobase was created. Native binary/template/EPF unchanged and reused. No new live 1C client/TestClient acceptance is claimed for this metadata-only change.
