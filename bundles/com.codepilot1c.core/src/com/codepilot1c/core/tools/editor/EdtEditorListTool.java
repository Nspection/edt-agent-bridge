package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name="edt_editor_list",category="workspace",tags={"read-only", "workspace", "edt"})
public final class EdtEditorListTool extends AbstractEdtEditorTool {
    public EdtEditorListTool(){super("list","List top-level editor tabs in the active EDT workbench page without activating or restoring them. Returns editor_id for loaded editors; internal object/form pages are not separate editors. Record initial IDs before work; enumeration does not establish ownership.","{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{}}");}
}
