package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name = "edt_editor_open", category = "workspace", tags = {"read-only", "workspace", "edt"})
public final class EdtEditorOpenTool extends AbstractEdtEditorTool {
    public EdtEditorOpenTool(){super("open","Open an existing .mdo object, .form, or .bsl module in EDT. Optional 1-based line applies only to .bsl. Returns current editor and navigation status. Never saves or changes sources.","{\"type\": \"object\", \"additionalProperties\": false, \"properties\": {\"line\": {\"type\": \"integer\", \"minimum\": 1}, \"project\": {\"type\": \"string\"}, \"file_path\": {\"type\": \"string\", \"description\": \"Path relative to project src/, e.g. Documents/РегистрацияГрузовыхМест/РегистрацияГрузовыхМест.mdo or Documents/РегистрацияГрузовыхМест/Forms/ФормаДокумента/Form.form\"}}, \"required\": [\"project\", \"file_path\"]}");}
}
