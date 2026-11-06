# Sources and third-party notices

This repository is a development fork of [ondysss/codepilot1c-edt](https://github.com/ondysss/codepilot1c-edt), based on commit `846b37eafa6fa0b41ca5b9c3729148114c10e384`. Original Git history, copyright notices, and the GNU Affero General Public License version 3 in [LICENSE](LICENSE) are retained. This fork is independently maintained; it is not an official upstream release.

## Adapted source: DitriXNew/EDT-MCP

- Project: https://github.com/DitriXNew/EDT-MCP
- Source revision: https://github.com/DitriXNew/EDT-MCP/tree/d2cf6c8b5ab1c43868351b066ae6c67fd027e680
- Author notice: Copyright (C) 2025 DitriX (https://github.com/DitriXNew).
- License: AGPL-3.0-or-later. Original notices are retained in adapted files; the license text is included in LICENSE.
- Adapted files in `bundles/com.codepilot1c.core/src/com/codepilot1c/core/edt/metadata/`: `PredefinedWriter.java`, `SpreadsheetTemplateWriter.java`, `MdNameNormalizer.java`.
- Adapted test: `bundles/com.codepilot1c.core.tests/src/com/codepilot1c/core/edt/metadata/SpreadsheetTemplateWriterTest.java`.
- Modifications include the CodePilot package namespace, a local error adapter, integration with native CodePilot type creation and BM transactions, input validation, and one-based MCP spreadsheet coordinates.
- The EDT-MCP implementation was also consulted for native extension preparation and debug launch attributes. The complete EDT-MCP server and transport are not bundled here.

The surrounding `NativeContentModels` and `VendorToolResult` adapters are additions to this fork. Existing dependency notices elsewhere in the upstream repository remain applicable.

## External integrations (not redistributed)

- [Vanessa Automation](https://github.com/Pr-Mex/vanessa-automation) is an optional external scenario runner. This fork invokes a user-installed EPF and does not distribute Vanessa binaries, VanessaExt, or its feature libraries. Follow Vanessa's own license and installation instructions.
- 1C:Enterprise, 1C:EDT, and their SDK/runtime libraries are provided by their respective vendors under their own terms. The local override build uses an existing licensed EDT installation; no proprietary platform binaries or business configurations are included.

## Changes in this fork

The fork adds native EDT editor navigation and snapshots, conditional and exception breakpoint control, richer debugger inspection, managed Vanessa runs with reusable profiles and visual checkpoints, native metadata creation/refactoring/content helpers, and targeted lifecycle/security fixes. Implementation and verification limits are described in [the automation guide](docs/release-notes/EDT-AI-AUTOMATION.md).

Development and live checks used a local core-bundle override on EDT 2026.1. A complete Tycho/p2 release build has not been validated for these additions. Upstream update-site links install upstream releases, not this fork's changes.
