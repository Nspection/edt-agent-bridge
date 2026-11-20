// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import static org.junit.Assert.*;
import java.nio.charset.StandardCharsets;
import javax.xml.stream.XMLStreamException;
import org.junit.Test;

public class BugReproductionJournalTest {
    private static byte[] xml(String text) {
        return text.getBytes(StandardCharsets.UTF_8);
    }

    @Test
    public void preservesClicksInTheOrderSavedByPlatform851() throws Exception {
        var steps = BugReproductionJournal.read(xml("""
                <uilog xmlns:d1p1="http://v8.1c.ru/8.3/uilog">
                  <ClientApplicationWindow isMain="true">
                    <CommandInterface><CommandInterfaceGroup title="Панель функций текущего раздела">
                      <CommandInterfaceButton title="OWN_ROW_ITEMS"><click/></CommandInterfaceButton>
                    </CommandInterfaceGroup></CommandInterface>
                  </ClientApplicationWindow>
                  <ClientApplicationWindow caption="OWN_ROW_ITEMS">
                    <Form title="OWN_ROW_ITEMS"><FormGroup name="ФормаКоманднаяПанель" title="Командная панель">
                      <FormButton name="ФормаСоздать" title="Создать"><click/></FormButton>
                    </FormGroup></Form>
                  </ClientApplicationWindow>
                </uilog>
                """));
        assertEquals(2, steps.size());
        assertEquals(new BugReproductionJournal.Step(1, "click", "", "", "", "OWN_ROW_ITEMS"), steps.get(0));
        assertEquals(new BugReproductionJournal.Step(2, "click", "OWN_ROW_ITEMS", "OWN_ROW_ITEMS",
                "ФормаСоздать", "Создать"), steps.get(1));
    }

    @Test
    public void ignoresRecordedValuesAndUnknownActions() throws Exception {
        assertTrue(BugReproductionJournal.read(xml("""
                <uilog><Form><FormField name="Secret"><input value="sample">sample</input></FormField></Form></uilog>
                """)).isEmpty());
        assertTrue(BugReproductionJournal.read(xml("<uilog/>")).isEmpty());
    }

    @Test
    public void rejectsExternalEntitiesAndInvalidOrOversizeDocuments() {
        for (String source : new String[] {
            "<!DOCTYPE uilog [<!ENTITY p SYSTEM 'file:///never-read-this'>]><uilog>&p;</uilog>",
            "<other/>", "<uilog><Form>", "<uilog>" + "<node>".repeat(40) + "</node>".repeat(40) + "</uilog>"
        }) {
            assertThrows(XMLStreamException.class, () -> BugReproductionJournal.read(xml(source)));
        }
        assertThrows(XMLStreamException.class, () -> BugReproductionJournal.read(new byte[4 * 1024 * 1024 + 1]));
    }
}
