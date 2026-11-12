// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.diagnostics;

import java.io.InputStream;
import java.net.URI;
import java.net.http.*;
import java.time.Duration;
import java.util.*;
import com.google.gson.*;

/** Fixed, stateless read-only MCP adapter. Only allowlisted identifiers leave the process. */
public final class V8StdClient implements DiagnosticStandards.Resolver {
    private static final URI ENDPOINT=URI.create("https://ai.v8std.ru/mcp");
    private final HttpClient client=HttpClient.newBuilder().connectTimeout(Duration.ofSeconds(4)).followRedirects(HttpClient.Redirect.NEVER).build();
    @Override public Map<String,DiagnosticStandards.Entry> resolve(List<String> codes)throws Exception {
        if(codes.isEmpty()||codes.size()>100||codes.stream().anyMatch(c->!c.equals(DiagnosticStandards.canonical(c))))throw new IllegalArgumentException("Invalid diagnostic identifiers");
        var request=Map.of("jsonrpc","2.0","id",1,"method","tools/call","params",Map.of("name","v8std_explain_diagnostics","arguments",Map.of("codes",codes)));
        var http=HttpRequest.newBuilder(ENDPOINT).timeout(Duration.ofSeconds(8)).header("Accept","application/json, text/event-stream").header("Content-Type","application/json").header("MCP-Protocol-Version","2025-03-26").POST(HttpRequest.BodyPublishers.ofString(new Gson().toJson(request))).build();
        var future=client.sendAsync(http,info->new LimitedBody());
        try {
            var response=future.get(8,java.util.concurrent.TimeUnit.SECONDS);
            if(response.statusCode()!=200)throw new IllegalStateException("v8std HTTP "+response.statusCode());
            return decode(new String(response.body(),java.nio.charset.StandardCharsets.UTF_8),codes);
        } finally {if(!future.isDone())future.cancel(true);}
    }
    private static final class LimitedBody implements HttpResponse.BodySubscriber<byte[]> {
        final java.util.concurrent.CompletableFuture<byte[]> result=new java.util.concurrent.CompletableFuture<>();final java.io.ByteArrayOutputStream bytes=new java.io.ByteArrayOutputStream();java.util.concurrent.Flow.Subscription subscription;
        public java.util.concurrent.CompletionStage<byte[]> getBody(){return result;}
        public void onSubscribe(java.util.concurrent.Flow.Subscription s){subscription=s;s.request(1);}
        public void onNext(List<java.nio.ByteBuffer> chunks){for(var chunk:chunks){if((long)bytes.size()+chunk.remaining()>1024*1024){subscription.cancel();result.completeExceptionally(new IllegalArgumentException("v8std response too large"));return;}byte[] b=new byte[chunk.remaining()];chunk.get(b);bytes.writeBytes(b);}subscription.request(1);}
        public void onError(Throwable e){result.completeExceptionally(e);}public void onComplete(){result.complete(bytes.toByteArray());}
    }

    static Map<String,DiagnosticStandards.Entry> decode(String body,List<String> requested) {
        JsonObject envelope=JsonParser.parseString(body).getAsJsonObject();
        if(envelope.has("error")||!envelope.has("result"))throw new IllegalArgumentException("Invalid v8std RPC response");
        JsonObject result=envelope.getAsJsonObject("result");if(result.has("isError")&&result.get("isError").getAsBoolean())throw new IllegalArgumentException("v8std tool failed");
        JsonObject data=result.has("structuredContent")?result.getAsJsonObject("structuredContent"):null;
        if(data==null)for(JsonElement part:result.getAsJsonArray("content"))if(part.isJsonObject()&&"text".equals(text(part.getAsJsonObject(),"type",10))){data=JsonParser.parseString(part.getAsJsonObject().get("text").getAsString()).getAsJsonObject();break;}
        if(data==null)throw new IllegalArgumentException("Missing v8std data");
        Map<String,DiagnosticStandards.Entry> out=new LinkedHashMap<>();Set<String> allowed=new HashSet<>(requested);Map<String,String> originals=new HashMap<>();for(String c:requested)originals.put(c.toLowerCase(Locale.ROOT),c);
        for(JsonElement item:data.getAsJsonArray("diagnostics")) {
            JsonObject d=item.getAsJsonObject();String id=originals.get(text(d,"id",140).toLowerCase(Locale.ROOT));if(id==null||out.containsKey(id))throw new IllegalArgumentException("Unexpected diagnostic ID");
            List<Map<String,String>> standards=new ArrayList<>();JsonArray links=d.getAsJsonArray("standards");if(links.size()>32)throw new IllegalArgumentException("Too many standards");
            for(JsonElement link:links){JsonObject row=link.getAsJsonObject();String std=text(row,"id",20),url=url(row,"url"),title=text(row,"title",300),summary=text(row,"description",400);if(!std.matches("std[0-9]{1,8}"))throw new IllegalArgumentException("Invalid standard ID");standards.add(Map.of("id",std,"url",url,"title",title,"description",summary,"description_scope","article_intro"));}
            out.put(id,new DiagnosticStandards.Entry(standards.isEmpty()?"no_standard":"resolved",text(d,"title",300),url(d,"url"),standards));
        }
        for(JsonElement item:data.getAsJsonArray("unknown_codes")){String id=originals.get(text(item.getAsJsonObject(),"code",140).toLowerCase(Locale.ROOT));if(id==null||out.putIfAbsent(id,new DiagnosticStandards.Entry("unknown_code","","",List.of()))!=null)throw new IllegalArgumentException("Unexpected unknown diagnostic");}
        if(!out.keySet().equals(allowed))throw new IllegalArgumentException("Incomplete v8std reply");return out;
    }
    private static String text(JsonObject o,String key,int limit){if(!o.has(key)||o.get(key).isJsonNull())return "";String s=o.get(key).getAsString();if(s.length()>limit)s=s.substring(0,limit);return s.replaceAll("[\\p{Cntrl}&&[^\\n\\t]]","");}
    private static String url(JsonObject o,String key){String s=text(o,key,600);URI u=URI.create(s);if(!"https".equals(u.getScheme())||!"v8std.ru".equals(u.getHost())||u.getRawUserInfo()!=null||u.getPort()!=-1||u.getRawQuery()!=null)throw new IllegalArgumentException("Untrusted standard URL");return s;}
}
