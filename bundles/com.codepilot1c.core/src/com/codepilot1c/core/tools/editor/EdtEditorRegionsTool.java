package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name="edt_editor_regions",category="workspace",tags={"workspace","edt"})
public final class EdtEditorRegionsTool extends AbstractEdtEditorTool {
    public EdtEditorRegionsTool(){super("regions","List explicit visible scroll regions of the active editor page. Includes semantic preview (outer viewport) and preview_content (native form content) targets when available, SWT region ids, scrollbar positions/ranges and tree/table state. Region ids are session/page specific; refresh after switching tabs.","{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\"editor_id\": {\"type\": \"string\"}}, \"required\": [\"editor_id\"]}");}
}
