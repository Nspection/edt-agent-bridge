package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name = "edt_editor_tabs", category = "workspace", tags = {"read-only", "workspace", "edt"})
public final class EdtEditorTabsTool extends AbstractEdtEditorTool {
    public EdtEditorTabsTool(){super("tabs","List actual pages of an opened EDT editor, including ids, titles and active index.","{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\"editor_id\": {\"type\": \"string\"}}, \"required\": [\"editor_id\"]}");}
}
