# Sources and third-party notices

This is an independent repository for EDT Agent Bridge, derived from [ondysss/codepilot1c-edt](https://github.com/ondysss/codepilot1c-edt), based on commit `846b37eafa6fa0b41ca5b9c3729148114c10e384`. Original Git history, copyright notices, and the GNU Affero General Public License version 3 in [LICENSE](LICENSE) are retained. This project is independently maintained; it is not an official upstream release.

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

The original development checks used a local core-bundle override on EDT 2026.1. EDT Agent Bridge now has a standalone source build and an Eclipse-published p2 update site; the supported build and its validation boundaries are documented in agent-bridge/README.md. Historical upstream Maven/CLI files are not part of this product build.

## Native API interface headers

`native/agent-host/include/{ComponentBase.h,types.h,com.h,IMemoryManager.h}` are unchanged interface headers obtained from [lintest/AddinTemplate](https://github.com/lintest/AddinTemplate/tree/ca2e27976f8c00f892d7cd8c8660d72a76d0211a/include), revision `ca2e27976f8c00f892d7cd8c8660d72a76d0211a`. The template repository is MIT licensed, Copyright (c) 2020 Kandrashin Denis; the complete notice is preserved in `native/agent-host/include/LICENSE` and native release ZIPs. Header warnings are retained. No implementation code from Infactum/addin-template is bundled. The native transport implementation is an addition to EDT Agent Bridge under its AGPL-3.0 license. Proprietary 1C/EDT runtime binaries are not redistributed.

## Direct TestClient protocol adapter

`bundles/com.codepilot1c.core/resources/test-client/vendor/{tc1c.py,guids.py,_collection.py}` are vendored from ROCTUP/1c-testpilot, commit `46eaa7454292e3e8a8159f631190221b5ffdce72` (1.9.2), under AGPL-3.0-only. The original license and source SHA256 inventory are included beside them. Source: https://github.com/ROCTUP/1c-testpilot/tree/46eaa7454292e3e8a8159f631190221b5ffdce72. The typed control adapter `controls.py` and checkbox-cell support also follow the wire contracts documented by `_runtime.py` and `_field_batches.py` at that pinned source revision; these adapters add exact-identity guards, desired-state checks and bounded read-back. The narrow stdio worker and EDT adapter are Bridge code; no Testpilot MCP server, GUI keyboard automation, service processing, or credentials are bundled. Python dependencies are externally supplied in an isolated runtime.

## External reference service: v8std

The optional diagnostic enrichment queries https://ai.v8std.ru/mcp using validated analyzer identifiers only. Article titles, introductory descriptions and links are cached as reference data. The v8std server implementation and corpus are not bundled with EDT Agent Bridge. Provider project: https://github.com/zeegin/v8std; service documentation: https://v8std.ru/mcp/.
