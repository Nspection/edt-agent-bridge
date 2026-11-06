package com.codepilot1c.core.edt.development;
import java.util.*;
import java.nio.file.*;
import java.nio.charset.StandardCharsets;
import com.google.gson.*;
/** Validates the executable scenario selection before any database/debug launch. */
public final class RunnerScenarioParams {
    private static JsonObject read(Path file) throws Exception {
        if(Files.size(file)>4*1024*1024){throw new IllegalArgumentException("Vanessa parameters exceed 4 MB");}
        String text=Files.readString(file,StandardCharsets.UTF_8);if(text.startsWith("\uFEFF")){text=text.substring(1);}return JsonParser.parseString(text).getAsJsonObject();
    }
    public static void validate(Path file,boolean visual) throws Exception {validate(read(file),visual);}
    private static void validate(JsonObject value,boolean visual) throws Exception {
        var clients=value.get("КлиентыТестирования");var features=value.get("СписокФичДляВыполнения");
        if(clients==null || !clients.isJsonArray() || clients.getAsJsonArray().isEmpty() || features==null || !features.isJsonArray() || features.getAsJsonArray().isEmpty()){throw new IllegalArgumentException("needs_setup: Vanessa parameters require named clients and selected features; pass feature_paths to edt_development_run_start or save a complete params_template");}
        if(features.getAsJsonArray().size()>100){throw new IllegalArgumentException("At most 100 selected features are supported");}
        for(var client:clients.getAsJsonArray()){if(!client.isJsonObject() || !client.getAsJsonObject().has("Имя") || client.getAsJsonObject().get("Имя").getAsString().isBlank()){throw new IllegalArgumentException("Every test client requires a name");}}
        for(var feature:features.getAsJsonArray()){
            Path path=Path.of(feature.getAsString());if(!path.isAbsolute() || !Files.isRegularFile(path) || !path.toString().toLowerCase(Locale.ROOT).endsWith(".feature")){throw new IllegalArgumentException("Selected feature must be an existing absolute .feature path: "+path);}
            if(visual){if(features.getAsJsonArray().size()!=1){throw new IllegalArgumentException("Visual checkpoint requires exactly one selected feature");}if(Files.size(path)>4*1024*1024){throw new IllegalArgumentException("Feature exceeds 4 MB");}var matcher=java.util.regex.Pattern.compile("(?m)^\\s*(Сценарий|Scenario):").matcher(Files.readString(path,StandardCharsets.UTF_8));int count=0;while(matcher.find()){count++;}if(count!=1){throw new IllegalArgumentException("Visual checkpoint requires one ordinary scenario");}}
        }
    }
    public static Path selected(Path storage,Path original,Object requested,boolean visual) throws Exception {
        if(!(requested instanceof List<?> paths) || paths.isEmpty() || paths.size()>100){throw new IllegalArgumentException("feature_paths requires 1..100 absolute .feature paths");}
        JsonObject value=read(original);JsonArray features=new JsonArray();for(Object path:paths){if(!(path instanceof String)){throw new IllegalArgumentException("feature_paths must contain strings");}features.add((String)path);}value.add("СписокФичДляВыполнения",features);validate(value,visual);
        value.addProperty("КаталогФич",Path.of(paths.get(0).toString()).getParent().toString());value.addProperty("КаталогОтносительноКоторогоНадоСтроитьИерархию",Path.of(paths.get(0).toString()).getParent().toString());value.addProperty("ПоказыватьПомощникПриПервомЗапуске",false);value.addProperty("ВыполнитьСценарии",true);
        byte[] bytes=new Gson().toJson(value).getBytes(StandardCharsets.UTF_8);String hash=HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(bytes));Path folder=storage.resolve("selected-features");Files.createDirectories(folder);Path file=folder.resolve(hash+".json");if(Files.exists(file) && !Arrays.equals(bytes,Files.readAllBytes(file))){throw new IllegalStateException("Generated scenario parameters were modified; inspect before retrying");}if(!Files.exists(file)){Path temporary=Files.createTempFile(folder,"params-",".json");try{Files.write(temporary,bytes);Files.move(temporary,file,StandardCopyOption.REPLACE_EXISTING,StandardCopyOption.ATOMIC_MOVE);}finally{Files.deleteIfExists(temporary);}}return file;
    }
}
