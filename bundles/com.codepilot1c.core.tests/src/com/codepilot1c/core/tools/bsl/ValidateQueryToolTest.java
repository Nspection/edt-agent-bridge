package com.codepilot1c.core.tools.bsl;

import static org.junit.Assert.assertEquals;

import java.util.List;

import org.junit.Test;

public class ValidateQueryToolTest {

    @Test
    public void extractsStaticQueriesWithModuleLineAndUnescapesQuotes() {
        String source = """
                Процедура Проверить()
                    // Текст = "ВЫБРАТЬ";
                    Заголовок = "Выбрать файл";
                    Текст = "ВЫБРАТЬ ""пример"" КАК Поле
                        |ИЗ Справочник.Товары";
                    Другой = "SELECT Item
                      |FROM Catalog.Products";
                КонецПроцедуры
                """.replace("\n", "\r\n");

        assertEquals(List.of(
                new ValidateQueryTool.QueryLiteral(23, "ВЫБРАТЬ \"пример\" КАК Поле\nИЗ Справочник.Товары"),
                new ValidateQueryTool.QueryLiteral(25, "SELECT Item\nFROM Catalog.Products")),
                ValidateQueryTool.queries(source, 20));
    }

    @Test
    public void skipsConcatenatedAndNonQueryLiterals() {
        String source = """
                Текст = "ВЫБРАТЬ Ссылка
                    |ИЗ " + ИмяТаблицы;
                Другой = Префикс + "SELECT Item
                    |FROM Catalog.Products";
                Составной = "ВЫБРАТЬ Ссылка
                    |ИЗ " // имя таблицы задаётся отдельно
                    + ИмяТаблицы;
                Составной = Префикс + // часть запроса
                    "SELECT Item
                    |FROM Catalog.Products";
                Скобки = (("ВЫБРАТЬ Ссылка
                    |ИЗ ")) + ИмяТаблицы;
                Скобки = Префикс + (("SELECT Item
                    |FROM Catalog.Products"));
                Справка = "ВыбратьСя
                    |пример";
                """;

        assertEquals(List.of(), ValidateQueryTool.queries(source, 1));
    }
    @Test
    public void keepsLiteralPassedToFunctionWhenPlusAppliesToItsResult() {
        String source = """
                Длина = СтрДлина(("ВЫБРАТЬ Ссылка
                    |ИЗ Справочник.Товары")) + 1;
                """;
        assertEquals(List.of(new ValidateQueryTool.QueryLiteral(1,
                "ВЫБРАТЬ Ссылка\nИЗ Справочник.Товары")), ValidateQueryTool.queries(source, 1));
    }
}
