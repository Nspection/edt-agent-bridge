package com.codepilot1c.core.tools.editor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.editor.EdtEditorService;
@ToolMeta(name="edt_editor_capture_batch",category="workspace",tags={"workspace","edt"})
public final class EdtEditorCaptureBatchTool extends AbstractTool {
    @Override public String getDescription(){return "Sequentially open, snapshot and close up to 8 EDT object/form editors. Each item may select one actual editor tab. Returns all per-item PNG paths and errors in one response. Only editors newly opened by this batch are closed, and only when clean. Pre-existing editors remain open and their selected tab is restored. Dirty editors are retained. Does not save or discard changes; PNGs must be viewed separately for visual acceptance.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"items":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","additionalProperties":false,"properties":{"project":{"type":"string","minLength":1},"file_path":{"type":"string","minLength":1},"tab_id":{"type":"string"},"tab_title":{"type":"string"},"tab_index":{"type":"integer","minimum":0}},"required":["project","file_path"]}}},"required":["items"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters params){return CompletableFuture.supplyAsync(()->{
        try {
            var items=validateItems(params.getRaw().get("items"));var results=new ArrayList<Map<String,Object>>();var service=EdtEditorService.getInstance();
            for(var item:items) {
                var row=new LinkedHashMap<String,Object>();row.put("project",item.get("project"));row.put("file_path",item.get("file_path"));
                String id=null;boolean owned=false;Object previousTab=null;
                try {
                    var opened=service.execute("open",Map.of("project",item.get("project"),"file_path",item.get("file_path")));
                    id=(String)opened.get("editor_id");owned=Boolean.TRUE.equals(opened.get("opened_new"));row.put("editor_id",id);row.put("opened_new",owned);
                    var ready=opened;
                    for(int attempt=0;!Boolean.TRUE.equals(ready.get("ready")) && attempt<40;attempt++) {
                        Thread.sleep(100);ready=service.execute("tabs",Map.of("editor_id",id));
                    }
                    if(!Boolean.TRUE.equals(ready.get("ready")))throw new IllegalStateException("Editor pages are still loading; inspect before retrying");
                    previousTab=ready.get("active_tab_index");
                    var capture=new LinkedHashMap<String,Object>();capture.put("editor_id",id);
                    for(String key:List.of("tab_id","tab_title","tab_index"))if(item.containsKey(key))capture.put(key,item.get(key));
                    if(capture.size()>1) {
                        for(int attempt=0;;attempt++)try {service.execute("select_tab",capture);break;}
                        catch(IllegalArgumentException e) {
                            if(attempt>=40 || e.getMessage()==null || !e.getMessage().startsWith("Tab selector must match exactly one tab; found 0"))throw e;
                            Thread.sleep(100);
                        }
                    }
                    var snapshot=service.execute("snapshot",capture);var compact=new LinkedHashMap<String,Object>();
                    for(String key:List.of("image_path","mime_type","width","height","captured_at","capture_method","ready","active_tab_index"))if(snapshot.containsKey(key))compact.put(key,snapshot.get(key));
                    row.put("snapshot",compact);row.put("captured",true);
                } catch(Exception e){if(e instanceof EdtEditorService.OpenFailure failure)row.putAll(failure.details());row.put("captured",false);row.put("error",e.getMessage()==null?e.getClass().getSimpleName():e.getMessage());}
                finally {
                    if(id!=null)try {
                        if(owned){var closed=service.execute("close",Map.of("editor_id",id));row.put("cleanup",Map.of("status",closed.get("status"),"closed",closed.get("closed")));}
                        else {if(previousTab instanceof Number n && n.intValue()>=0)service.execute("select_tab",Map.of("editor_id",id,"tab_index",n));row.put("cleanup",Map.of("status","preexisting_retained","closed",false));}
                    }catch(Exception e){row.put("cleanup",Map.of("status","cleanup_failed","closed",false,"error",String.valueOf(e.getMessage())));}
                }
                results.add(row);
            }
            return ToolResult.success(new Gson().toJson(finishResults(results,()->service.execute("list",Map.of()))));
        }catch(Exception e){return ToolResult.failure("EDT_EDITOR_BATCH: "+e.getMessage());}
    });}
    interface Inventory {Map<String,Object> read()throws Exception;}
    static Map<String,Object> finishResults(List<Map<String,Object>> results,Inventory inventory) {
        boolean ok=results.stream().allMatch(x->Boolean.TRUE.equals(x.get("captured")) && x.get("cleanup") instanceof Map<?,?> m && Set.of("closed","preexisting_retained").contains(m.get("status")));
        var output=new LinkedHashMap<String,Object>();output.put("results",results);output.put("total",results.size());
        try{output.put("remaining_editors",inventory.read());}catch(Exception error){ok=false;output.put("remaining_editors_error",String.valueOf(error.getMessage()));}
        output.put("all_succeeded",ok);return output;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> validateItems(Object raw) {
        if(!(raw instanceof List<?> values)||values.isEmpty()||values.size()>8)throw new IllegalArgumentException("items must contain 1..8 editors");
        List<Map<String,Object>> out=new ArrayList<>();
        for(Object value:values) {
            if(!(value instanceof Map<?,?> m)||!Set.of("project","file_path","tab_id","tab_title","tab_index").containsAll(m.keySet()))throw new IllegalArgumentException("Invalid batch item");
            for(String key:List.of("project","file_path"))if(!(m.get(key) instanceof String s)||s.isBlank())throw new IllegalArgumentException("Missing "+key);
            if(List.of("tab_id","tab_title","tab_index").stream().filter(m::containsKey).count()>1)throw new IllegalArgumentException("Select at most one tab per item");
            out.add(new LinkedHashMap<>((Map<String,Object>)m));
        }
        return out;
    }
}
