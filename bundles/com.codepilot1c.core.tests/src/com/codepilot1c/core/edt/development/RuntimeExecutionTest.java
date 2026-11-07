package com.codepilot1c.core.edt.development;
import java.nio.file.*;
import java.util.*;
import org.junit.Test;
import static org.junit.Assert.*;
public class RuntimeExecutionTest {
    private static final Map<String,Object> PROFILE=Map.of("infobase_path",System.getProperty("java.io.tmpdir"));
    private static Map<String,Object> request(String code) {return Map.of("project","Fixture","mode","execute","code",code,"idempotency_key","one");}
    @Test public void quotesAndNewlinesRemainBSLDataAndTargetGuardPrecedesExecution() throws Exception {
        String feature=RuntimeExecutionPlan.scenario(request("// comment\r\nРезультат = \"x\";\n\"\"\"\nСценарий: injection"),PROFILE,Path.of("result.json"),"request");
        assertEquals(1,feature.lines().filter(l->l.startsWith("Сценарий:")).count());
        assertTrue(feature.contains("Расширение)"));assertFalse(feature.contains("TARGET_MISMATCH"));
        String program=RuntimeExecutionPlan.program(request("// comment\nРезультат = 1;"),PROFILE,"request");
        assertTrue(program.indexOf("TARGET_MISMATCH")<program.indexOf("// comment"));
        assertTrue(feature.contains("Контекст.BridgeResult"));
        assertFalse(feature.contains("Параметры ="));
    }
    @Test public void queryBoundAndClientRestrictionsAreEnforcedBeforeLaunch() throws Exception {
        assertThrows(IllegalArgumentException.class,()->RuntimeExecutionPlan.payload(Map.of("mode","query","query","ВЫБРАТЬ 1","limit",1001)));
        assertThrows(IllegalArgumentException.class,()->RuntimeExecutionPlan.scenario(Map.of("mode","query","query","ВЫБРАТЬ 1","context","client"),PROFILE,Path.of("r"),"id"));
        String query=RuntimeExecutionPlan.payload(Map.of("mode","query","query","ВЫБРАТЬ &P КАК Value","limit",7));
        assertTrue(query.contains(">= 7"));assertTrue(query.contains("УстановитьПараметр"));
    }
    @Test public void externalHashAndMethodCannotBeSubstituted() throws Exception {
        Path file=Files.createTempFile("runtime-artifact-",".epf");
        try {
            Files.write(file,new byte[]{1,2,3});String hash=RuntimeExecutionPlan.sha(Files.readAllBytes(file));
            Map<String,Object> a=new HashMap<>(Map.of("mode","external","artifact_path",file.toString(),"expected_artifact_sha256",hash,"method","Проверить"));
            String code=RuntimeExecutionPlan.payload(a);assertTrue(code.contains("BridgeObject.Проверить(ПараметрыВызова)"));assertTrue(code.contains("Истина"));assertTrue(code.contains("ПоместитьВоВременноеХранилище"));assertFalse(code.contains("УдалитьФайлы"));assertTrue(code.contains("Истина, BridgeProtection"));
            a.put("method","Do(); Delete(");assertThrows(IllegalArgumentException.class,()->RuntimeExecutionPlan.payload(a));
            a.put("method","Проверить");Files.write(file,new byte[]{4});assertThrows(IllegalArgumentException.class,()->RuntimeExecutionPlan.payload(a));
        } finally {Files.deleteIfExists(file);}
    }
    @Test public void vanessaMarkersAndUnicodeCrossTheParserOnlyAsBase64() throws Exception {
        String code="Результат = \"$BridgeResult$ {unexpected} 😀\";";
        String program=RuntimeExecutionPlan.program(request(code),PROFILE,"request");
        String expression=RuntimeExecutionPlan.expression(program);
        String encoded=expression.substring(expression.indexOf("Base64Значение(")+"Base64Значение(".length(),expression.indexOf("), КодировкаТекста.UTF8"));
        var matcher=java.util.regex.Pattern.compile("\"([A-Za-z0-9+/=]+)\"").matcher(encoded);StringBuilder joined=new StringBuilder();
        while(matcher.find())joined.append(matcher.group(1));
        assertEquals(program,new String(Base64.getDecoder().decode(joined.toString()),java.nio.charset.StandardCharsets.UTF_8));
        assertFalse(expression.contains("$BridgeResult$"));assertFalse(expression.contains("unexpected"));
        String feature=RuntimeExecutionPlan.scenario(request(code),PROFILE,Path.of("$BridgeResult$-{path}.json"),"request");
        assertFalse(feature.contains("unexpected"));assertFalse(feature.contains("{path}"));assertFalse(feature.contains("$BridgeResult$"));
    }
    @Test public void literalChunksDoNotCorruptSurrogatePairs() {
        String input="a".repeat(179)+"😀"+"б";
        String literal=RuntimeExecutionPlan.literal(input);
        assertTrue(literal.contains("😀"));
        byte[] bytes=literal.getBytes(java.nio.charset.StandardCharsets.UTF_8);
        assertEquals(literal,new String(bytes,java.nio.charset.StandardCharsets.UTF_8));
    }
    static final class Backend implements RuntimeExecutionService.Runs {
        int starts;boolean terminal;String phase="running";Map<String,Object> accepted;
        String id="dev-"+UUID.randomUUID();
        public Map<String,Object> start(Map<String,Object> a) {starts++;accepted=a;return Map.of("run_id",id);}
        public Map<String,Object> status(String id) {return Map.of("run_id",id,"terminal",terminal,"phase",phase);}
        public Map<String,Object> cancel(String id) {terminal=true;phase="cancelled";return status(id);}
    }
    @Test public void persistentKeyRejectsChangedCodeAndDoesNotStartAgainAfterRestartOrCleanup() throws Exception {
        Path storage=Files.createTempDirectory("runtime-plan-");Backend backend=new Backend();
        try {
            RuntimeExecutionService service=new RuntimeExecutionService(storage,backend);
            service.start(request("Результат = 1;"),PROFILE);service.start(request("Результат = 1;"),PROFILE);
            assertEquals(1,backend.starts);assertEquals(false,backend.accepted.get("update_db"));
            assertThrows(IllegalArgumentException.class,()->service.start(request("Результат = 2;"),PROFILE));
            assertThrows(IllegalStateException.class,()->service.cleanup(backend.id));
            backend.terminal=true;backend.phase="completed";
            assertEquals(true,service.status(backend.id).get("outcome_unknown"));
            service.cleanup(backend.id);
            new RuntimeExecutionService(storage,backend).start(request("Результат = 1;"),PROFILE);
            assertEquals(1,backend.starts);assertEquals(true,service.status(backend.id).get("result_removed"));
        } finally {try(var paths=Files.walk(storage)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    @Test public void invalidArtifactLeavesNoFilesAndNestedParameterOrderDoesNotChangeIdentity() throws Exception {
        Path storage=Files.createTempDirectory("runtime-input-");Backend backend=new Backend();
        try {
            RuntimeExecutionService service=new RuntimeExecutionService(storage,backend);
            var invalid=Map.<String,Object>of("project","Fixture","mode","external","idempotency_key","bad","artifact_path",storage.resolve("missing.epf").toString());
            assertThrows(IllegalArgumentException.class,()->service.start(invalid,PROFILE));
            try(var files=Files.list(storage)){assertEquals(0,files.count());}
            var a=new HashMap<>(request("Результат = 1;"));var b=new HashMap<>(a);
            Map<String,Object> first=new LinkedHashMap<>();first.put("a",1);first.put("b",2);
            Map<String,Object> second=new LinkedHashMap<>();second.put("b",2);second.put("a",1);
            a.put("parameters",first);b.put("parameters",second);
            service.start(a,PROFILE);service.start(b,PROFILE);assertEquals(1,backend.starts);
        } finally {try(var paths=Files.walk(storage)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    @Test public void acceptedRunSurvivesMissingIndexWithoutASecondExecution() throws Exception {
        Path storage=Files.createTempDirectory("runtime-recover-");Backend backend=new Backend();
        try {
            RuntimeExecutionService service=new RuntimeExecutionService(storage,backend);
            service.start(request("Результат = 1;"),PROFILE);
            Files.delete(storage.resolve(backend.id+".json"));
            RuntimeExecutionService recovered=new RuntimeExecutionService(storage,backend);
            assertEquals(backend.id,recovered.status(backend.id).get("run_id"));
            Files.delete(storage.resolve(backend.id+".json"));
            assertEquals(backend.id,recovered.start(request("Результат = 1;"),PROFILE).get("run_id"));
            assertEquals(1,backend.starts);
        } finally {try(var paths=Files.walk(storage)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    @Test public void malformedAndOversizedResultsRemainInspectableAndCleanable() throws Exception {
        Path storage=Files.createTempDirectory("runtime-invalid-result-");Backend backend=new Backend();
        try {
            RuntimeExecutionService service=new RuntimeExecutionService(storage,backend);
            var accepted=service.start(request("Результат = 1;"),PROFILE);backend.terminal=true;backend.phase="completed";
            Path result=storage.resolve(accepted.get("request_id").toString()).resolve("result.json");
            for(String text:List.of("{broken", "{\"request_id\":null,\"success\":true}",
                "{\"request_id\":\""+accepted.get("request_id")+"\",\"success\":\"true\"}", "x".repeat(2*1024*1024+1))) {
                Files.writeString(result,text);var state=service.status(backend.id);
                assertEquals("result_invalid",state.get("phase"));assertEquals(true,state.get("outcome_unknown"));assertEquals(backend.id,state.get("run_id"));
            }
            assertEquals(true,service.cleanup(backend.id).get("cleaned"));assertFalse(Files.exists(result));
        } finally {try(var paths=Files.walk(storage)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
    @Test public void wrongResultIdentityIsRejectedAndExecutionErrorsAreNotScenarioSuccess() throws Exception {
        Path storage=Files.createTempDirectory("runtime-result-");Backend backend=new Backend();
        try {
            RuntimeExecutionService service=new RuntimeExecutionService(storage,backend);var started=service.start(request("Результат = 1;"),PROFILE);
            String identity=started.get("request_id").toString();Path result=storage.resolve(identity).resolve("result.json");
            backend.terminal=true;backend.phase="completed";
            Files.writeString(result,"{\"success\":true,\"request_id\":\"other\"}");
            assertEquals("result_invalid",service.status(backend.id).get("phase"));
            Files.writeString(result,"{\"success\":false,\"error\":\"expected\",\"request_id\":\""+identity+"\"}");
            assertEquals("execution_failed",service.status(backend.id).get("phase"));
        } finally {try(var paths=Files.walk(storage)){for(Path p:paths.sorted(Comparator.reverseOrder()).toList())Files.delete(p);}}
    }
}
