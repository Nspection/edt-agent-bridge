package com.codepilot1c.core.tools.development;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.*;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
@ToolMeta(name="edt_operation_status",category="workspace",tags={"read-only","workspace","edt"})
public final class EdtOperationStatusTool extends AbstractTool {
    @Override public String getDescription(){return "Read any CodePilot background operation by job_id, including project creation and infobase update. A completed project-create job does not imply that project indexing or validation finished.";}
    @Override public String getParameterSchema(){return """
        {"type":"object","additionalProperties":false,"properties":{"job_id":{"type":"string"}},"required":["job_id"]}
        """;}
    @Override protected CompletableFuture<ToolResult> doExecute(ToolParameters p){
        var optional=BackgroundJobRegistry.getInstance().getStatus((String)p.getRaw().get("job_id"));
        if(optional.isEmpty()){return CompletableFuture.completedFuture(ToolResult.failure("Unknown or expired operation"));}
        var s=optional.get();Map<String,Object> out=new LinkedHashMap<>();out.put("job_id",s.getJobId());out.put("kind",s.getKind());out.put("state",s.getState().name());
        out.put("submitted_at",String.valueOf(s.getSubmittedAt()));out.put("started_at",String.valueOf(s.getStartedAt()));out.put("finished_at",String.valueOf(s.getFinishedAt()));
        out.put("progress",BackgroundJobRegistry.getInstance().getProgress(s.getJobId()));
        if(s.getResult()!=null){try{out.put("result",new Gson().fromJson(s.getResult(),Object.class));}catch(Exception e){out.put("result",s.getResult());}}
        if(s.getError()!=null){out.put("error",s.getError());}
        return CompletableFuture.completedFuture(ToolResult.success(new Gson().toJson(out)));
    }
}
