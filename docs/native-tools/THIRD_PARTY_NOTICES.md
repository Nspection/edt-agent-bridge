# Attribution for the native EDT contribution

This contribution is ported selectively from Nspection/edt-agent-bridge, source commit b442420db2dd0f3eb918c7c636ff9ae4a9277ea2. CodePilot1C's existing history, license and contributor terms remain unchanged. The standalone Bridge product extraction, provider removals and account/UI replacements are not part of this contribution.

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

## Scope of this contribution

The additive native tools and their validation boundaries are described in [README.md](README.md) and [VALIDATION.md](VALIDATION.md). The original CodePilot Maven/Tycho build and all existing provider/account/chat mechanisms remain in use. No proprietary EDT or 1C binaries are added.

## Native API interface headers

`native/agent-host/include/{ComponentBase.h,types.h,com.h,IMemoryManager.h}` are unchanged interface headers obtained from [lintest/AddinTemplate](https://github.com/lintest/AddinTemplate/tree/ca2e27976f8c00f892d7cd8c8660d72a76d0211a/include), revision `ca2e27976f8c00f892d7cd8c8660d72a76d0211a`. The template repository is MIT licensed, Copyright (c) 2020 Kandrashin Denis; the complete notice is preserved in `native/agent-host/include/LICENSE` and native release ZIPs. Header warnings are retained. No implementation code from Infactum/addin-template is bundled. The native transport implementation is an addition to EDT Agent Bridge under its AGPL-3.0 license. Proprietary 1C/EDT runtime binaries are not redistributed.
