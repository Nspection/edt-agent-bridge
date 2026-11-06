package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.edt.metadata.EdtMetadataService;
@ToolMeta(name="edt_spreadsheet",category="metadata",mutating=true,tags={"workspace","edt"})
public final class EdtSpreadsheetTool extends AbstractTool {
    @Override public String getDescription(){return "Read sparse spreadsheet-template cells/areas or preview/apply a native EDT model patch. Omit patch for read. 1-based patch arrays: cells[{row,col,text|parameter,bold,fontSize,hAlign,vAlign,wrap,replace}], merges/areas[{fromRow,fromCol,toRow,toCol,name}], columnWidths[{col,width}], rowHeights[{row,height}]. Validate exact supported values through preview; errors make no change. apply=true requires fresh expected_preview_sha256. New spreadsheet content uses SheetFactory interactive project defaults; never rewrites raw mxlx or retypes other template kinds.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"project":{"type":"string"},"template_fqn":{"type":"string"},"patch":{"type":"object"},"apply":{"type":"boolean","default":false},"expected_preview_sha256":{"type":"string"},"from_row":{"type":"integer","minimum":1,"default":1},"to_row":{"type":"integer","minimum":1,"default":50},"from_col":{"type":"integer","minimum":1,"default":1},"to_col":{"type":"integer","minimum":1,"default":20},"limit":{"type":"integer","minimum":1,"maximum":500,"default":100}},"required":["project","template_fqn"]}
        """;}
    @SuppressWarnings("unchecked") @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){return CompletableFuture.supplyAsync(()->{try{var a=p.getRaw();Map<String,Object> patch=a.get("patch") instanceof Map<?,?> values?(Map<String,Object>)values:null;return ToolResult.success(new Gson().toJson(new EdtMetadataService().spreadsheetContent((String)a.get("project"),(String)a.get("template_fqn"),patch,Boolean.TRUE.equals(a.get("apply")),(String)a.get("expected_preview_sha256"),n(a,"from_row",1),n(a,"to_row",50),n(a,"from_col",1),n(a,"to_col",20),n(a,"limit",100))));}catch(Exception e){return ToolResult.failure("EDT_SPREADSHEET: "+e.getMessage());}});}
    private static int n(Map<String,Object> a,String key,int fallback){return ((Number)a.getOrDefault(key,fallback)).intValue();}
}
