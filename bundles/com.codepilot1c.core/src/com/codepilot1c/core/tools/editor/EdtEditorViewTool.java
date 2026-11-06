package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name="edt_editor_view",category="workspace",tags={"workspace","edt"})
public final class EdtEditorViewTool extends AbstractEdtEditorTool {
    public EdtEditorViewTool(){super("viewport","Inspect, maximize or restore the editor area inside the EDT perspective, independent of Windows window maximize. capture=true returns a PNG after resizing. Does not change/save the model.","{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\"editor_id\": {\"type\": \"string\"}, \"mode\": {\"type\": \"string\", \"enum\": [\"state\", \"maximize\", \"restore\"], \"default\": \"state\"}, \"capture\": {\"type\": \"boolean\", \"default\": false}, \"auto_restore\": {\"type\": \"boolean\", \"default\": true}}, \"required\": [\"editor_id\"]}");}
}
