// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.search;
import java.nio.file.*;import java.util.*;
import com.google.gson.Gson;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;

/** Cold indexes use existing background jobs; following status never resubmits a search. */
public final class Bm25SearchJobs {
    private static final Bm25SearchJobs INSTANCE=new Bm25SearchJobs(BackgroundJobRegistry.getInstance(),ProjectBm25Index::searchProject);
    @FunctionalInterface interface Work {Map<String,Object> search(Path root,String query,String scope,int limit,boolean rebuild)throws Exception;}
    private final BackgroundJobRegistry jobs;private final Work work;private final Map<List<Object>,String> active=new HashMap<>();private static final Gson JSON=new Gson();
    Bm25SearchJobs(BackgroundJobRegistry jobs,Work work){this.jobs=jobs;this.work=work;}
    public static Map<String,Object> search(Path root,String query,String scope,int limit,boolean rebuild)throws Exception{return INSTANCE.run(root,query,scope,limit,rebuild,20000);}
    Map<String,Object> run(Path root,String query,String scope,int limit,boolean rebuild,int wait)throws Exception {
        if(query==null||query.isBlank()||query.length()>1000||!Set.of("all","modules","queries").contains(scope)||limit<1||limit>20)throw new IllegalArgumentException("Invalid BM25 query/scope/top_k");
        var terms=new LinkedHashSet<>(Bm25Documents.tokens(query,64));if(terms.isEmpty()||terms.size()>32)throw new IllegalArgumentException("query must contain 1..32 lexical terms");
        Path real=root.toRealPath();List<Object> key=List.of(real,query,scope,limit,rebuild);String id;
        synchronized(active){active.entrySet().removeIf(e->{var status=jobs.getStatus(e.getValue());return status.isEmpty()||Set.of("DONE","FAILED","EXPIRED").contains(status.get().getState().name());});id=active.get(key);if(id==null){if(active.size()>=16)throw new IllegalStateException("BM25 search queue full");id=jobs.startJob("project_bm25_search",()->{Map<String,Object> result=new LinkedHashMap<>(work.search(real,query,scope,limit,rebuild));result.put("state","completed");result.put("terminal",true);return JSON.toJson(result);});active.put(key,id);}}
        var status=jobs.awaitStatus(id,wait).orElseThrow(()->new IllegalStateException("Unknown BM25 job"));
        if(status.getState().name().equals("DONE")){@SuppressWarnings("unchecked") Map<String,Object> result=JSON.fromJson(status.getResult(),LinkedHashMap.class);result.put("job_id",id);return result;}
        if(status.getState().name().equals("FAILED"))throw new IllegalStateException("BM25 search failed: "+status.getError());
        return Map.of("search_type","bm25","state","indexing","terminal",false,"job_id",id,"next_call",Map.of("tool","edt.task","arguments",Map.of("op","edt_operation_status","args",Map.of("job_id",id,"timeout_ms",20000))));
    }
}
