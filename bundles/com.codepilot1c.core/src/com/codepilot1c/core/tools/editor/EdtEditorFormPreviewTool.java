package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name="edt_editor_form_preview",category="workspace",tags={"workspace","edt"})
public final class EdtEditorFormPreviewTool extends AbstractEdtEditorTool {
    public EdtEditorFormPreviewTool(){super("form_preview","List form elements/pages and native projection readiness, or select by exact name/ID. Native selection reveals containing pages without desktop input or model mutation. Open the Form tab first. grid=true/grid_step adds separate rulers and grid while preserving the original; requires capture=true. capture=true returns a delayed screenshot plus selection result; inspect it, image_available alone is not a correctness verdict.","""
        {"type":"object","additionalProperties":false,"properties":{"editor_id":{"type":"string"},"mode":{"type":"string","enum":["list","select"],"default":"list"},"element_name":{"type":"string"},"element_id":{"type":"integer"},"offset":{"type":"integer","minimum":0},"limit":{"type":"integer","minimum":1,"maximum":500},"capture":{"type":"boolean"},"grid":{"type":"boolean","default":false},"grid_step":{"type":"integer","minimum":8,"maximum":500,"default":20},"auto_restore":{"type":"boolean","default":true}},"required":["editor_id"]}
        """);}
}
