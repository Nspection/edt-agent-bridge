// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDateTime;
import java.time.format.*;
import java.util.*;
import java.util.regex.Pattern;
import com.google.gson.*;

/** Fixed server-side creation recipe; callers provide values, never code or references. */
public final class TestDataCreatePlan {
    private static final Gson JSON=new Gson();
    private static final Pattern NAME=Pattern.compile("[\\p{L}_][\\p{L}\\p{N}_]{0,127}");
    private static final Set<String> PROTECTED=Set.of("ссылка","ref","пометкаудаления","deletionmark","предопределенный","предопределённый","predefined","имяпредопределенныхданных","predefineddataname","этогруппа","isfolder","проведен","проведён","posted","обменданными","dataexchange","дополнительныесвойства","additionalproperties");
    private TestDataCreatePlan() {}
    public static JsonObject normalize(String fqn,Map<String,Object> values) {return normalize(fqn,values,false);}
    static JsonObject normalizeLinked(String fqn,Map<String,Object> values) {return normalize(fqn,values,true);}
    private static JsonObject normalize(String fqn,Map<String,Object> values,boolean linked) {
        if(fqn==null || !fqn.matches("(?:Catalog|Document)\\.[\\p{L}_][\\p{L}\\p{N}_]{0,127}"))throw new IllegalArgumentException("Use Catalog.Name or Document.Name");
        if(values==null || values.size()>32)throw new IllegalArgumentException("fields must contain at most 32 values");
        JsonObject fields=new JsonObject();Set<String> names=new HashSet<>();
        for(var entry:new TreeMap<>(values).entrySet()) {
            String name=entry.getKey();if(name==null || !NAME.matcher(name).matches() || PROTECTED.contains(name.toLowerCase(Locale.ROOT)) || !names.add(name.toLowerCase(Locale.ROOT)))throw new IllegalArgumentException("Unsupported, protected or duplicate field");
            Object value=entry.getValue();
            if(value instanceof String s) {if(s.length()>4096 || s.indexOf(0)>=0)throw new IllegalArgumentException("String field exceeds 4096 characters or contains NUL");fields.addProperty(name,s);}
            else if(value instanceof Boolean b)fields.addProperty(name,b);
            else if(value instanceof Number number) {
                BigDecimal decimal;try{decimal=new BigDecimal(number.toString()).stripTrailingZeros();}catch(NumberFormatException e){throw new IllegalArgumentException("Finite numbers required");}
                if(decimal.precision()>38 || decimal.scale()>38 || decimal.precision()-decimal.scale()>38)throw new IllegalArgumentException("Number exceeds supported precision/range");
                fields.addProperty(name,decimal);
            } else if(linked && value instanceof Map<?,?> ref && "reference".equals(ref.get("type"))) {
                if(!ref.keySet().equals(Set.of("type","metadata_fqn","uuid","expected_version")) || !(ref.get("metadata_fqn") instanceof String meta) || !meta.matches("(?:Catalog|Document)\\.[\\p{L}_][\\p{L}\\p{N}_]{0,127}") || !(ref.get("uuid") instanceof String uuid) || !uuid.matches("[a-f0-9]{8}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{4}-[a-f0-9]{12}") || uuid.equals("00000000-0000-0000-0000-000000000000") || !(ref.get("expected_version") instanceof String version) || version.isBlank() || version.length()>4096)throw new IllegalArgumentException("Invalid owned reference");
                JsonObject owned=new JsonObject();for(String key:List.of("type","metadata_fqn","uuid","expected_version"))owned.addProperty(key,(String)ref.get(key));fields.add(name,owned);
            } else if(value instanceof Map<?,?> date) {
                if(!date.keySet().equals(Set.of("type","value")) || !"date".equals(date.get("type")) || !(date.get("value") instanceof String text) || !text.matches("[0-9]{14}"))throw new IllegalArgumentException("Only typed dates are supported as structured values");
                LocalDateTime parsed;try{parsed=LocalDateTime.parse(text,DateTimeFormatter.ofPattern("uuuuMMddHHmmss").withResolverStyle(ResolverStyle.STRICT));}catch(java.time.DateTimeException e){throw new IllegalArgumentException("Invalid typed date",e);}
                if(parsed.getYear()<1 || parsed.getYear()>3999)throw new IllegalArgumentException("Date year must be 1..3999");
                JsonObject typed=new JsonObject();typed.addProperty("type","date");typed.addProperty("value",text);fields.add(name,typed);
            } else throw new IllegalArgumentException("Use string, number, boolean or typed date; references/tabular sections are not supported");
        }
        JsonObject result=new JsonObject();result.addProperty("metadata_fqn",fqn);result.add("fields",fields);
        if(JSON.toJson(result).getBytes(StandardCharsets.UTF_8).length>32*1024)throw new IllegalArgumentException("Creation fields exceed 32 KiB");return result;
    }
    static Map<String,Object> fieldValues(JsonObject fields) {
        Map<String,Object> result=new TreeMap<>();
        for(var entry:fields.entrySet()) {
            JsonElement value=entry.getValue();Object parsed;
            if(value.isJsonObject()) {var object=value.getAsJsonObject();Map<String,Object> date=new TreeMap<>();for(var field:object.entrySet())date.put(field.getKey(),field.getValue().getAsString());parsed=date;}
            else if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isNumber())parsed=value.getAsBigDecimal();
            else if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isBoolean())parsed=value.getAsBoolean();
            else if(value.isJsonPrimitive() && value.getAsJsonPrimitive().isString())parsed=value.getAsString();
            else throw new IllegalArgumentException("Unsupported saved field value");
            result.put(entry.getKey(),parsed);
        }
        return result;
    }
    public static String code(JsonObject specification) throws Exception {
        String fqn=specification.get("metadata_fqn").getAsString();String name=fqn.substring(fqn.indexOf('.')+1);boolean catalog=fqn.startsWith("Catalog.");
        String source;try(var input=TestDataCreatePlan.class.getResourceAsStream("/resources/agent-host/TestDataCreate.bsl")){if(input==null)throw new IllegalStateException("Creation recipe missing");source=new String(input.readAllBytes(),StandardCharsets.UTF_8);}
        StringBuilder assignments=new StringBuilder();
        for(var entry:specification.getAsJsonObject("fields").entrySet()) {
            String accessor="ПараметрыВызова.fields["+RuntimeExecutionPlan.literal(entry.getKey())+"]";
            if(entry.getValue().isJsonObject()) {
                var typed=entry.getValue().getAsJsonObject();
                if(typed.get("type").getAsString().equals("date"))accessor="Дата("+accessor+".value)";
                else {String metadata=typed.get("metadata_fqn").getAsString();String manager=(metadata.startsWith("Catalog.")?"Справочники.":"Документы.")+metadata.substring(metadata.indexOf('.')+1);String table=(metadata.startsWith("Catalog.")?"Справочник.":"Документ.")+metadata.substring(metadata.indexOf('.')+1);String ref=manager+".ПолучитьСсылку(Новый УникальныйИдентификатор("+accessor+".uuid))";
                    assignments.append("    СсылкаСвязи = ").append(ref).append(";\n    БлокировкаСвязи = Новый БлокировкаДанных;\n    ЭлементСвязи = БлокировкаСвязи.Добавить(\"").append(table).append("\");\n    ЭлементСвязи.Режим = РежимБлокировкиДанных.Разделяемый;\n    ЭлементСвязи.УстановитьЗначение(\"Ссылка\", СсылкаСвязи);\n    БлокировкаСвязи.Заблокировать();\n    ЗапросСвязи = Новый Запрос(\"ВЫБРАТЬ Данные.ВерсияДанных, Данные.ПометкаУдаления ИЗ ").append(table).append(" КАК Данные ГДЕ Данные.Ссылка = &Ссылка\");\n    ЗапросСвязи.УстановитьПараметр(\"Ссылка\", СсылкаСвязи);\n    ВыборкаСвязи = ЗапросСвязи.Выполнить().Выбрать();\n    Если НЕ ВыборкаСвязи.Следующий() Тогда ВызватьИсключение НСтр(\"ru = 'Связанный тестовый объект не найден.'\"); КонецЕсли;\n    Если ВыборкаСвязи.ПометкаУдаления ИЛИ ЗначениеВСтрокуВнутр(ВыборкаСвязи.ВерсияДанных) <> ").append(accessor).append(".expected_version Тогда ВызватьИсключение НСтр(\"ru = 'Связанный тестовый объект изменен.'\"); КонецЕсли;\n");accessor="СсылкаСвязи";
                }
            }
            assignments.append("    ОбъектДанных[").append(RuntimeExecutionPlan.literal(entry.getKey())).append("] = ").append(accessor).append(";\n");
        }
        return source.replace("__MANAGER__",catalog?"Справочники":"Документы").replace("__NAME__",name).replace("__TABLE__",(catalog?"Справочник.":"Документ.")+name).replace("__FACTORY__",catalog?"СоздатьЭлемент":"СоздатьДокумент").replace("__ASSIGNMENTS__",assignments.toString()).replace("__WRITE__",catalog?"Записать()":"Записать(РежимЗаписиДокумента.Запись)");
    }
}
