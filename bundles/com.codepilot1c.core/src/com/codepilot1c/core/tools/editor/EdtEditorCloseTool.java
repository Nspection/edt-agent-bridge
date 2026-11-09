package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name="edt_editor_close",category="workspace",tags={"workspace", "edt"})
public final class EdtEditorCloseTool extends AbstractEdtEditorTool {
    public EdtEditorCloseTool(){super("close","Close one saved top-level editor by editor_id without saving or discarding changes or activating it. Dirty editors return status=dirty_editor and remain open. Returns closed/close_refused and the remaining editor list. Close only explicit task-owned IDs; no close-all or forced discard.","{\"type\":\"object\",\"additionalProperties\":false,\"properties\":{\"editor_id\":{\"type\":\"string\",\"minLength\":1}},\"required\":[\"editor_id\"]}");}
}
