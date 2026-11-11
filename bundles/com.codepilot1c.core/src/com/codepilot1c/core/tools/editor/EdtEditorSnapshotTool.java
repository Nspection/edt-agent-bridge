package com.codepilot1c.core.tools.editor;
import com.codepilot1c.core.tools.ToolMeta;
@ToolMeta(name="edt_editor_snapshot",category="workspace",tags={"read-only","workspace","edt"})
public final class EdtEditorSnapshotTool extends AbstractEdtEditorTool {
    public EdtEditorSnapshotTool(){super("snapshot",
        "Capture the EDT editor window directly, without bringing it over the user's current app on Windows. Automatically restores a minimized window by default; set auto_restore=false to leave it minimized and get an error. Optionally select a tab by exactly one tab_id, tab_title or tab_index. Returns PNG image_path, window_restored, ready/dirty state and active tab. grid=true adds a separate alignment.image_path PNG with rulers and grid_step (default20px); image_path remains the untouched original. Coordinates refer to original pixels, origin offset32px in annotated PNG. Annotation failure retains original image_path with alignment_error; do not repeat UI actions. Read the PNG with the local image reader. Preview can render asynchronously; inspect the image and retry if loading.",
        """
        {"type":"object","additionalProperties":false,
         "properties":{"editor_id":{"type":"string"},"grid":{"type":"boolean","default":false},"grid_step":{"type":"integer","minimum":8,"maximum":500,"default":20},"auto_restore":{"type":"boolean","default":true},"tab_id":{"type":"string"},"tab_title":{"type":"string"},"tab_index":{"type":"integer","minimum":0}},
         "required":["editor_id"]}
        """);}
}
