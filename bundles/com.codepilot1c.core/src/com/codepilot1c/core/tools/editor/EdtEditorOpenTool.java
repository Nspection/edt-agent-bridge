package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name = "edt_editor_open", category = "workspace", tags = {"read-only", "workspace", "edt"})
public final class EdtEditorOpenTool extends AbstractEdtEditorTool {
    public EdtEditorOpenTool(){super("open","Open existing .mdo object or .form editor through Eclipse IDE. Returns editor_id, dirty state and tabs. Never saves or changes metadata.","{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\"project\": {\"type\": \"string\"}, \"file_path\": {\"type\": \"string\", \"description\": \"Path relative to project src/, e.g. Documents/РегистрацияГрузовыхМест/РегистрацияГрузовыхМест.mdo or Documents/РегистрацияГрузовыхМест/Forms/ФормаДокумента/Form.form\"}}, \"required\": [\"project\", \"file_path\"]}");}
}
