# Review: separate process popup snapshots

The original process snapshot returned only the main PrintWindow surface and filtered small windows. It omitted real menus that live in independent HWNDs. The fix retains the selected-window PNG and returns separate same-process/same-UI-thread WS_POPUP images, including small and ownerless windows; no desktop composition or UI input is performed.

## Own and independent review

Independent review found and closed: recursive window description, timeout/publication race, partial PNG cleanup, mixed GDI alpha erasing black pixels, false failure caused by transparent shadows, and uniform gray incorrectly satisfying informative-popup expectations. The capture lease retains its process gate until the actual native worker exits, even after caller timeout. Pending PNGs are published only when accepted; abandoned artifacts are removed. Opaque/global-alpha GDI and per-pixel layered alpha use distinct decoding modes; changed layered attributes or matte content reject capture. Inconclusive surfaces retain diagnostic PNGs with explicit unproven alpha, not fabricated visual success.

Final source and artifact review reports no open confirmed P1/P2. The independent reviewer recalculated both JAR hashes, checked bundled resources against source, verified absence of experimental UIA helpers, read the exact final receipts and personally inspected root and menu PNGs.

## Validation

Exact final artifacts passed 1379 Java tests, 140 direct TestClient Python tests, tooling/provenance/package tests (one symlink-privilege skip), OSGi linkage, 33 managed PowerShell contracts, 3 empty-workspace CLI checks and 13 startup input contracts. Local p2 resolution used verifyOnly with a copied external profile; no user installation changed.

Live acceptance used an owned SDK/workspace on an isolated Windows desktop, a new minimal source fixture and a newly provisioned disposable 8.3.27.2325 base. Root-only capture passed. A real V8CommandPanelPopup 244x33 plus five shadow surfaces produced 6/6 separate PNGs, complete inventory and exactly one informative popup. The independent reviewer read CHILD MUST NOT RUN in the menu; the form title trap proved the child command was not executed. Shadows-only retained complete PNG coverage but failed expect_popup with informative count zero. Unsupported Group/Popup expand returned action_dispatched=false/outcome_unknown=false and the channel remained mutable. All eight owned test-client records were cleaned, the disposable database/launcher record/UUID settings were removed through preview/hash cleanup, and the own SDK stopped.

## Limits

Safe semantic Popup reveal remains unsupported. Direct TestClient EXPAND_GROUP was rejected and both advertised UIA Expand/Invoke methods returned NotImplemented on the owned 8.3.27 provider. Rejected prototypes are preserved externally and excluded from product artifacts. A separate lab-only input helper preopened the minimal own popup to test observation; this is not a shipped action backend or proof of semantic reveal. No coordinate/message fallback ships.

Snapshots observe only windows present during capture. PNGs are separate, not an atomic composite. capture_complete is inventory/render coverage, not content/shape/menu identity proof. expect_popup requires at least one informative same-thread WS_POPUP, which does not identify a specific menu. White/black, uniform, low-information, inconclusive and alpha flags must be inspected. No arbitrary business form, user environment or 8.5 platform acceptance is claimed. Native DLL, EPF and protocol are unchanged. User EDT, x5 and user databases were not used or modified.
