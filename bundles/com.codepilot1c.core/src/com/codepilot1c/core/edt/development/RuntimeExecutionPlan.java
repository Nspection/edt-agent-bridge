package com.codepilot1c.core.edt.development;

import java.nio.charset.StandardCharsets;
import java.nio.file.*;
import java.util.*;
import com.google.gson.Gson;

/** Builds a Vanessa scenario using VAExtension in the target, never manager-side payload execution. */
public final class RuntimeExecutionPlan {
    private static final Gson JSON = new Gson();
    public static final int MAX_CODE = 128 * 1024, MAX_ARTIFACT = 1024 * 1024;
    private RuntimeExecutionPlan() {}

    public static String literal(String value) {
        // Keep literals short without splitting a UTF-16 surrogate pair.
        List<String> parts = new ArrayList<>();
        String normalized = value.replace("\r\n", "\n").replace('\r', '\n');
        String[] lines = normalized.split("\n", -1);
        for (int n = 0; n < lines.length; n++) {
            if (n > 0) parts.add("Символы.ПС");
            String line = lines[n];
            if (line.isEmpty()) parts.add("\"\"");
            for (int i = 0; i < line.length();) {
                int end = Math.min(i + 180, line.length());
                if (end < line.length() && Character.isHighSurrogate(line.charAt(end - 1))
                    && Character.isLowSurrogate(line.charAt(end))) end--;
                parts.add("\"" + line.substring(i, end).replace("\"", "\"\"") + "\"");
                i = end;
            }
        }
        return String.join(" + ", parts);
    }

    public static String required(Map<String,Object> a, String key) {
        if (!(a.get(key) instanceof String s) || s.isBlank()) throw new IllegalArgumentException(key + " is required");
        return s;
    }

    public static String payload(Map<String,Object> a) throws Exception {
        String mode = required(a, "mode");
        return switch (mode) {
            case "info" -> "BridgeSystem = Новый СистемнаяИнформация; Результат = Новый Структура(\"configuration,configuration_version,user,platform\", Метаданные.Имя, Метаданные.Версия, ИмяПользователя(), BridgeSystem.ВерсияПриложения);";
            case "execute" -> {
                String code = required(a, "code");
                if (code.getBytes(StandardCharsets.UTF_8).length > MAX_CODE) throw new IllegalArgumentException("code exceeds 128 KiB");
                yield "Выполнить(" + literal(code) + ");";
            }
            case "query" -> query(a);
            case "external" -> external(a);
            default -> throw new IllegalArgumentException("Unknown mode: " + mode);
        };
    }

    private static String query(Map<String,Object> a) {
        String query = required(a, "query");
        if (query.getBytes(StandardCharsets.UTF_8).length > MAX_CODE) throw new IllegalArgumentException("query exceeds 128 KiB");
        int limit = ((Number)a.getOrDefault("limit", 100)).intValue();
        if (limit < 1 || limit > 1000) throw new IllegalArgumentException("limit must be 1..1000");
        return """
            BridgeQuery = Новый Запрос;
            BridgeQuery.Текст = %s;
            Для Каждого BridgeParameter Из ПараметрыВызова Цикл
                BridgeQuery.УстановитьПараметр(BridgeParameter.Ключ, BridgeParameter.Значение);
            КонецЦикла;
            BridgeTable = BridgeQuery.Выполнить();
            BridgeRows = Новый Массив;
            BridgeColumns = Новый Массив;
            Для Каждого BridgeColumn Из BridgeTable.Колонки Цикл
                BridgeColumns.Добавить(BridgeColumn.Имя);
            КонецЦикла;
            BridgeSelection = BridgeTable.Выбрать();
            BridgeTruncated = Ложь;
            Пока BridgeSelection.Следующий() Цикл
                Если BridgeRows.Количество() >= %d Тогда
                    BridgeTruncated = Истина;
                    Прервать;
                КонецЕсли;
                BridgeRow = Новый Структура;
                Для Каждого BridgeColumn Из BridgeTable.Колонки Цикл
                    BridgeCell = BridgeSelection[BridgeColumn.Имя];
                    BridgeType = ТипЗнч(BridgeCell);
                    Если BridgeType <> Тип("Строка") И BridgeType <> Тип("Число") И BridgeType <> Тип("Булево")
                        И BridgeType <> Тип("Null") И BridgeType <> Тип("Неопределено") Тогда
                        BridgeCell = Новый Структура("type,presentation", Строка(BridgeType), Строка(BridgeCell));
                    КонецЕсли;
                    BridgeRow.Вставить(BridgeColumn.Имя, BridgeCell);
                КонецЦикла;
                BridgeRows.Добавить(BridgeRow);
            КонецЦикла;
            Результат = Новый Структура("columns,rows,truncated", BridgeColumns, BridgeRows, BridgeTruncated);
            """.formatted(literal(query), limit);
    }

    private static String external(Map<String,Object> a) throws Exception {
        Path artifact = Path.of(required(a,"artifact_path"));
        if (!artifact.isAbsolute() || !Files.isRegularFile(artifact) || Files.size(artifact) < 1 || Files.size(artifact) > MAX_ARTIFACT)
            throw new IllegalArgumentException("Use an absolute nonempty EPF/ERF of at most 1 MiB");
        String suffix = artifact.toString().toLowerCase(Locale.ROOT).endsWith(".epf") ? "epf" :
            artifact.toString().toLowerCase(Locale.ROOT).endsWith(".erf") ? "erf" : "";
        if (suffix.isEmpty()) throw new IllegalArgumentException("Expected .epf or .erf");
        byte[] bytes = Files.readAllBytes(artifact);
        String expected = required(a,"expected_artifact_sha256");
        if (!sha(bytes).equalsIgnoreCase(expected)) throw new IllegalArgumentException("ARTIFACT_CHANGED: rebuild/read the artifact before execution");
        String method = required(a,"method");
        if (!method.matches("[\\p{L}_][\\p{L}\\p{N}_]{0,127}")) throw new IllegalArgumentException("method must be one exported function identifier");
        String manager = suffix.equals("epf") ? "ВнешниеОбработки" : "ВнешниеОтчеты";
        return """
            BridgeProtection = Новый ОписаниеЗащитыОтОпасныхДействий;
            BridgeProtection.ПредупреждатьОбОпасныхДействиях = Ложь;
            BridgeAddress = ПоместитьВоВременноеХранилище(Base64Значение(%s));
            BridgeName = %s.Подключить(BridgeAddress, , Истина, BridgeProtection);
            УдалитьИзВременногоХранилища(BridgeAddress);
            BridgeObject = %s.Создать(BridgeName, Истина, BridgeProtection);
            Результат = BridgeObject.%s(ПараметрыВызова);
            """.formatted(literal(Base64.getEncoder().encodeToString(bytes)), manager, manager, method);
    }

    public static String sha(byte[] data) throws Exception {
        return HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(data));
    }

    static String program(Map<String,Object> a, Map<String,Object> profile, String requestId) throws Exception {
        String context = (String)a.getOrDefault("context", "server");
        if (!Set.of("server", "client").contains(context)) throw new IllegalArgumentException("context must be server or client");
        String mode = required(a, "mode");
        if (!mode.equals("execute") && !context.equals("server")) throw new IllegalArgumentException(mode + " requires server context");
        String program = payload(a);
        String parameters = JSON.toJson(a.getOrDefault("parameters", Map.of()));
        if (!(a.getOrDefault("parameters", Map.of()) instanceof Map<?,?>) || parameters.getBytes(StandardCharsets.UTF_8).length > MAX_CODE)
            throw new IllegalArgumentException("parameters must be a JSON object of at most 128 KiB");
        Map<String,String> expected = new LinkedHashMap<>();
        if (RunnerProfileStore.serverTarget(profile)) {
            expected.put("srvr", required(profile,"server_address"));
            expected.put("ref", required(profile,"server_infobase"));
        } else expected.put("file", Path.of(required(profile,"infobase_path")).toAbsolutePath().normalize().toString());
        StringBuilder guards = new StringBuilder();
        for (var entry : expected.entrySet()) guards.append("Если Не BridgeIdentity.Свойство(").append(literal(entry.getKey()))
            .append(") Или BridgeIdentity[").append(literal(entry.getKey())).append("] <> НРег(")
            .append(literal(entry.getValue().replace('\\','/').replaceAll("/+$", ""))).append(") Тогда ВызватьИсключение \"TARGET_MISMATCH\"; КонецЕсли;\n");
        String wrapper = """
            BridgeEnvelope = Новый Структура("success,value,error,request_id,context", Ложь, Неопределено, "", %s, %s);
            Попытка
                BridgeIdentity = Новый Структура;
                Для Каждого BridgePart Из СтрРазделить(СтрокаСоединенияИнформационнойБазы(), ";", Ложь) Цикл
                    BridgeEquals = СтрНайти(BridgePart, "=");
                    Если BridgeEquals > 0 Тогда
                        BridgeKey = НРег(СокрЛП(Лев(BridgePart, BridgeEquals - 1)));
                        BridgeText = НРег(СтрЗаменить(СтрЗаменить(СокрЛП(Сред(BridgePart, BridgeEquals + 1)), \"\"\"\", ""), "\\", "/"));
                        Пока Прав(BridgeText, 1) = "/" Цикл BridgeText = Лев(BridgeText, СтрДлина(BridgeText) - 1); КонецЦикла;
                        BridgeIdentity.Вставить(BridgeKey, BridgeText);
                    КонецЕсли;
                КонецЦикла;
                %s
                BridgeReader = Новый ЧтениеJSON;
                BridgeReader.УстановитьСтроку(%s);
                ПараметрыВызова = ПрочитатьJSON(BridgeReader);
                BridgeReader.Закрыть();
                Результат = Неопределено;
                %s
                BridgeEnvelope.value = Результат;
                BridgeEnvelope.success = Истина;
            Исключение
                BridgeEnvelope.error = ПодробноеПредставлениеОшибки(ИнформацияОбОшибке());
            КонецПопытки;
            Попытка
                BridgeWriter = Новый ЗаписьJSON;
                BridgeWriter.УстановитьСтроку();
                ЗаписатьJSON(BridgeWriter, BridgeEnvelope);
                ЗначениеДляВозврата = BridgeWriter.Закрыть();
            Исключение
                BridgeEnvelope.success = Ложь;
                BridgeEnvelope.value = Неопределено;
                BridgeEnvelope.error = "RESULT_NOT_JSON_SERIALIZABLE";
                BridgeWriter = Новый ЗаписьJSON;
                BridgeWriter.УстановитьСтроку();
                ЗаписатьJSON(BridgeWriter, BridgeEnvelope);
                ЗначениеДляВозврата = BridgeWriter.Закрыть();
            КонецПопытки;
            """.formatted(literal(requestId), literal(context), guards, literal(parameters), program);
        return wrapper;
    }

    static String expression(String program) {
        String encoded=Base64.getEncoder().encodeToString(program.getBytes(StandardCharsets.UTF_8));
        // Vanessa expands $variables$ and inline expressions. Only Base64 crosses its parser.
        return "ЗначениеДляВозврата = \"\"; Выполнить(ПолучитьСтрокуИзДвоичныхДанных(Base64Значение("
            + literal(encoded) + "), КодировкаТекста.UTF8));";
    }

    public static String scenario(Map<String,Object> a, Map<String,Object> profile, Path result, String requestId) throws Exception {
        String context=(String)a.getOrDefault("context","server");
        String transport=expression(program(a,profile,requestId));
        String expressionStep = context.equals("server") ? "И я запоминаю значение выражения на сервере '$BridgeCode$' в переменную \"BridgeResult\" (Расширение)" :
            "И я запоминаю значение выражения '$BridgeCode$' в переменную \"BridgeResult\" (Расширение)";
        return "#language: ru\nФункционал: EDT Runtime\nСценарий: EDT Runtime request\n"
            + "    Дано Я подключаю клиент тестирования \"TestClient\" из таблицы клиентов тестирования\n"
            + "    И я выполняю код встроенного языка\n        \"\"\"bsl\n"
            + "        Контекст.Вставить(\"BridgeCode\", " + literal(transport) + ");\n        \"\"\"\n"
            + "    " + expressionStep + "\n"
            + "    И я выполняю код встроенного языка\n        \"\"\"bsl\n"
            + "        BridgeFile = Новый ЗаписьТекста(ПолучитьСтрокуИзДвоичныхДанных(Base64Значение("
            + literal(Base64.getEncoder().encodeToString(result.toString().getBytes(StandardCharsets.UTF_8)))
            + "), КодировкаТекста.UTF8), КодировкаТекста.UTF8);\n"
            + "        BridgeFile.Записать(Контекст.BridgeResult);\n        BridgeFile.Закрыть();\n        \"\"\"\n";
    }
}
