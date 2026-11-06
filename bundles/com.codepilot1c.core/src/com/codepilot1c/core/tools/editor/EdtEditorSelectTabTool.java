package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name = "edt_editor_select_tab", category = "workspace", tags = {"read-only", "workspace", "edt"})
public final class EdtEditorSelectTabTool extends AbstractEdtEditorTool {
    public EdtEditorSelectTabTool(){super("select_tab","Select an actual EDT editor page by exactly one tab_id, tab_title or zero-based tab_index. Returns selected tab and dirty state. Does not change model or save.","{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\"editor_id\": {\"type\": \"string\"}, \"tab_id\": {\"type\": \"string\"}, \"tab_title\": {\"type\": \"string\"}, \"tab_index\": {\"type\": \"integer\", \"minimum\": 0}}, \"required\": [\"editor_id\"]}");}
}
