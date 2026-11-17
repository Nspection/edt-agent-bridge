package com.codepilot1c.core.tools.editor;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import java.nio.file.Files;
import java.nio.file.Path;
import javax.imageio.ImageIO;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.editor.EdtEditorService;
import com.codepilot1c.core.edt.editor.SnapshotAlignment;
@ToolMeta(name="edt_editor_capture_batch",category="workspace",tags={"workspace","edt"})
public final class EdtEditorCaptureBatchTool extends AbstractTool {
    @Override public String getDescription(){return "Sequentially open, snapshot and close up to 8 EDT object/form editors. Each item may select one actual editor tab. Returns all per-item PNG paths and errors in one response. Only editors newly opened by this batch are closed, and only when clean. Pre-existing editors remain open and their selected tab is restored. Dirty editors are retained. Does not save or discard changes; grid=true/grid_step per item adds an alignment.image_path PNG with rulers while preserving each original. Annotation failure returns captured=true with original PNG and alignment_error, all_succeeded=false; do not replay UI actions. The active Form page is captured through a fresh native projection; other pages use the editor snapshot. captured=true requires a readable PNG and accepted capture evidence. Failures retain state, reason and projection diagnostics. PNGs must be viewed separately for visual acceptance.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"items":{"type":"array","minItems":1,"maxItems":8,"items":{"type":"object","additionalProperties":false,"properties":{"project":{"type":"string","minLength":1},"file_path":{"type":"string","minLength":1},"grid":{"type":"boolean","default":false},"grid_step":{"type":"integer","minimum":8,"maximum":500,"default":20},"tab_id":{"type":"string"},"tab_title":{"type":"string"},"tab_index":{"type":"integer","minimum":0}},"required":["project","file_path"]}}},"required":["items"]}
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
                    previousTab=opened.getOrDefault("previous_active_tab_index",opened.get("active_tab_index"));
                    var ready=opened;
                    for(int attempt=0;!Boolean.TRUE.equals(ready.get("ready")) && attempt<40;attempt++) {
                        Thread.sleep(100);ready=service.execute("tabs",Map.of("editor_id",id));
                    }
                    if(!Boolean.TRUE.equals(ready.get("ready")))throw new IllegalStateException("Editor pages are still loading; inspect before retrying");
                    var capture=new LinkedHashMap<String,Object>();capture.put("editor_id",id);
                    for(String key:List.of("tab_id","tab_title","tab_index"))if(item.containsKey(key))capture.put(key,item.get(key));
                    if(capture.size()>1) {
                        for(int attempt=0;;attempt++)try {ready=service.execute("select_tab",capture);break;}
                        catch(IllegalArgumentException e) {
                            if(attempt>=40 || e.getMessage()==null || !e.getMessage().startsWith("Tab selector must match exactly one tab; found 0"))throw e;
                            Thread.sleep(100);
                        }
                    }
                    for(String key:List.of("grid","grid_step"))if(item.containsKey(key))capture.put(key,item.get(key));
                    recordSnapshot(row,capture(service::execute,capture,ready.get("active_tab_index")));
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
    interface EditorAccess {Map<String,Object> execute(String action,Map<String,Object> args)throws Exception;}
    /** Selectors have already been applied. Route by the actual active editor page, not the file suffix. */
    static Map<String,Object> capture(EditorAccess service,Map<String,Object> args,Object expectedTab)throws Exception {
        var capture=new LinkedHashMap<>(args);
        for(String key:List.of("tab_id","tab_title","tab_index"))capture.remove(key);
        var tabs=service.execute("tabs",Map.of("editor_id",args.get("editor_id")));
        if(!Objects.equals(expectedTab,tabs.get("active_tab_index")))throw new IllegalStateException("Editor tab changed before batch capture");
        boolean form=tabs.get("tabs") instanceof List<?> pages && pages.stream().anyMatch(page->page instanceof Map<?,?> tab
                && Boolean.TRUE.equals(tab.get("active")) && "editors.form.pages.main".equals(tab.get("id")));
        if(form){capture.put("mode","refresh");capture.put("capture",true);}
        var snapshot=service.execute(form?"form_preview":"snapshot",capture);
        if(!Objects.equals(expectedTab,snapshot.get("active_tab_index"))) {
            snapshot=new LinkedHashMap<>(snapshot);snapshot.put("success",false);
            var check=new LinkedHashMap<String,Object>();check.put("verified",false);check.put("expected_active_tab_index",expectedTab);
            check.put("actual_active_tab_index",snapshot.get("active_tab_index"));check.put("reason","editor_tab_changed_during_batch_capture");
            snapshot.put("capture_tab_verification",check);
        }
        return snapshot;
    }
    static void recordSnapshot(Map<String,Object> row,Map<String,Object> snapshot) {
        var compact=new LinkedHashMap<String,Object>();
        for(String key:List.of("image_path","original_image_path","original_sha256","alignment","alignment_error","alignment_applied",
                "mime_type","width","height","captured_at","capture_method","capture_scope","capture_id","ready","editor_ready",
                "active_tab_index","success","state","reason","error","projection_freshness","readiness_scope","viewport_equivalence_verified",
                "visual_correctness_verified","capture_tab_verification","next_call","freshness_call","note"))if(snapshot.containsKey(key))compact.put(key,snapshot.get(key));
        row.put("snapshot",compact);boolean captured=hasCapture(compact);row.put("captured",captured);
        if(!captured)row.put("error","Capture did not produce an accepted PNG; inspect snapshot diagnostics");
    }
    /** A normal return or ready editor is insufficient: the accepted PNG must really exist and decode. */
    static boolean acceptedMetadata(Map<?,?> snapshot) {
        if(Boolean.FALSE.equals(snapshot.get("success")) || !(snapshot.get("image_path") instanceof String name) || name.isBlank()
                || !"image/png".equals(snapshot.get("mime_type")) || !(snapshot.get("width") instanceof Number width)
                || !(snapshot.get("height") instanceof Number height) || width.intValue()<=0 || height.intValue()<=0)return false;
        if("native_form_body_from_same_renderer_cache".equals(snapshot.get("capture_scope"))
                && (!Boolean.TRUE.equals(snapshot.get("ready")) || !(snapshot.get("projection_freshness") instanceof Map<?,?> freshness)
                    || !Boolean.TRUE.equals(freshness.get("verified"))))return false;
        return true;
    }
    static boolean hasCapture(Map<?,?> snapshot) {
        if(!acceptedMetadata(snapshot))return false;
        try {
            Path path=Path.of((String)snapshot.get("image_path"));if(!Files.isRegularFile(path))return false;
            try(var input=ImageIO.createImageInputStream(path.toFile())) {
                if(input==null)return false;var readers=ImageIO.getImageReaders(input);if(!readers.hasNext())return false;
                var reader=readers.next();try {
                    if(!"png".equalsIgnoreCase(reader.getFormatName()))return false;
                    reader.setInput(input);if(reader.getWidth(0)!=((Number)snapshot.get("width")).intValue() || reader.getHeight(0)!=((Number)snapshot.get("height")).intValue())return false;
                    return reader.read(0)!=null;
                }finally {reader.dispose();}
            }
        }catch(Exception failure){return false;}
    }
    interface Inventory {Map<String,Object> read()throws Exception;}
    static Map<String,Object> finishResults(List<Map<String,Object>> results,Inventory inventory) {
        boolean ok=results.stream().allMatch(x->Boolean.TRUE.equals(x.get("captured")) && x.get("snapshot") instanceof Map<?,?> snapshot && acceptedMetadata(snapshot) && !snapshot.containsKey("alignment_error") && x.get("cleanup") instanceof Map<?,?> m && Set.of("closed","preexisting_retained").contains(m.get("status")));
        var output=new LinkedHashMap<String,Object>();output.put("results",results);output.put("total",results.size());
        try{output.put("remaining_editors",inventory.read());}catch(Exception error){ok=false;output.put("remaining_editors_error",String.valueOf(error.getMessage()));}
        output.put("all_succeeded",ok);return output;
    }
    @SuppressWarnings("unchecked") static List<Map<String,Object>> validateItems(Object raw) {
        if(!(raw instanceof List<?> values)||values.isEmpty()||values.size()>8)throw new IllegalArgumentException("items must contain 1..8 editors");
        List<Map<String,Object>> out=new ArrayList<>();
        for(Object value:values) {
            if(!(value instanceof Map<?,?> m)||!Set.of("project","file_path","tab_id","tab_title","tab_index","grid","grid_step").containsAll(m.keySet()))throw new IllegalArgumentException("Invalid batch item");
            for(String key:List.of("project","file_path"))if(!(m.get(key) instanceof String s)||s.isBlank())throw new IllegalArgumentException("Missing "+key);
            if(List.of("tab_id","tab_title","tab_index").stream().filter(m::containsKey).count()>1)throw new IllegalArgumentException("Select at most one tab per item");
            SnapshotAlignment.options((Map<String,Object>)m);
            out.add(new LinkedHashMap<>((Map<String,Object>)m));
        }
        return out;
    }
}
