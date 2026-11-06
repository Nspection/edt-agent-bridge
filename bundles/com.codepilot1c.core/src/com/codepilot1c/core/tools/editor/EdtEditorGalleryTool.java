package com.codepilot1c.core.tools.editor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.editor.EdtEditorService;
@ToolMeta(name="edt_editor_gallery",category="workspace",tags={"workspace","edt"})
public final class EdtEditorGalleryTool extends AbstractTool {
    @Override public String getDescription(){return "Capture a bounded gallery of nested managed-form pages through native model selection and editor snapshots, without desktop input. Open the Form tab first. Returns per-page PNG paths and failures; restores the previous element selection when available. offset/limit paginate pages (maximum 20 per call). Does not save or mutate the form. Image availability is not layout acceptance.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"editor_id":{"type":"string"},"offset":{"type":"integer","minimum":0,"default":0},"limit":{"type":"integer","minimum":1,"maximum":20,"default":10}},"required":["editor_id"]}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{
        var service=EdtEditorService.getInstance();var a=p.getRaw();var captures=new ArrayList<Map<String,Object>>();Object originalId=null;boolean restored=false;String restoreError=null;
        try{
            String editor=(String)a.get("editor_id");int offset=((Number)a.getOrDefault("offset",0)).intValue(),limit=((Number)a.getOrDefault("limit",10)).intValue();if(offset<0 || limit<1 || limit>20){throw new IllegalArgumentException("Invalid gallery range");}
            var state=service.execute("form_preview",Map.of("editor_id",editor,"mode","list","limit",1));var selected=(List<Map<String,Object>>)state.get("selected");if(!selected.isEmpty()){originalId=selected.get(0).get("id");}
            var pages=(List<Map<String,Object>>)state.get("pages");
            try{for(var page:pages.stream().skip(offset).limit(limit).toList()){
                var result=new LinkedHashMap<String,Object>();result.put("page",page);
                try{result.put("snapshot",service.execute("form_preview",Map.of("editor_id",editor,"mode","select","element_id",page.get("id"),"capture",true,"limit",1)));result.put("captured",true);}catch(Exception e){result.put("captured",false);result.put("error",e.getMessage());}captures.add(result);
            }}finally{if(originalId!=null){try{service.execute("form_preview",Map.of("editor_id",editor,"mode","select","element_id",originalId,"limit",1));restored=true;}catch(Exception e){restoreError=e.getMessage();}}}
            var output=new LinkedHashMap<String,Object>();output.put("captures",captures);output.put("total_pages",pages.size());output.put("has_more",offset+limit<pages.size());output.put("selection_restored",restored);output.put("restore_error",restoreError);return ToolResult.success(new Gson().toJson(output));
        }catch(Exception e){return ToolResult.failure("EDT_EDITOR_GALLERY: "+e.getMessage());}
    });}
}
