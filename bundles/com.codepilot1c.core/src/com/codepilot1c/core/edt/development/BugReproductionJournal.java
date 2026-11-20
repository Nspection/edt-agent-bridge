// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;

import java.io.ByteArrayInputStream;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.List;

import javax.xml.XMLConstants;
import javax.xml.stream.XMLInputFactory;
import javax.xml.stream.XMLStreamConstants;
import javax.xml.stream.XMLStreamException;
import javax.xml.stream.XMLStreamReader;

/** Reads the structural fields of a 1C user action journal. Recorded input values stay in the original XML. */
public final class BugReproductionJournal {
    private static final String NAMESPACE = "http://v8.1c.ru/8.3/uilog";
    private static final int MAX_BYTES = 4 * 1024 * 1024;
    private static final int MAX_ELEMENTS = 24_000;
    private static final int MAX_DEPTH = 32;
    private static final int MAX_STEPS = 1000;

    public record Step(int number, String action, String window, String form, String element,
            String elementTitle) {
    }

    private record Element(String kind, String name, String caption) {
    }

    private BugReproductionJournal() {
    }

    public static List<Step> read(byte[] journal) throws XMLStreamException {
        if (journal.length == 0 || journal.length > MAX_BYTES) {
            throw new XMLStreamException("1C action journal must contain at most 4 MiB");
        }
        var factory = XMLInputFactory.newDefaultFactory();
        factory.setProperty(XMLInputFactory.SUPPORT_DTD, false);
        factory.setProperty(XMLInputFactory.IS_SUPPORTING_EXTERNAL_ENTITIES, false);
        factory.setProperty(XMLConstants.ACCESS_EXTERNAL_DTD, "");
        factory.setXMLResolver((publicId, systemId, base, namespace) -> {
            throw new XMLStreamException("External XML resources are forbidden");
        });
        var reader = factory.createXMLStreamReader(new ByteArrayInputStream(journal));
        try {
            return readEntries(reader);
        } finally {
            reader.close();
        }
    }

    private static List<Step> readEntries(XMLStreamReader reader) throws XMLStreamException {
        var ancestry = new ArrayDeque<Element>();
        var steps = new ArrayList<Step>();
        int visited = 0;
        while (reader.hasNext()) {
            int event = reader.next();
            if (event == XMLStreamConstants.DTD || event == XMLStreamConstants.ENTITY_REFERENCE) {
                throw new XMLStreamException("DTD and custom XML entities are forbidden");
            }
            if (event == XMLStreamConstants.END_ELEMENT) {
                ancestry.pop();
                continue;
            }
            if (event != XMLStreamConstants.START_ELEMENT) {
                continue;
            }
            if (++visited > MAX_ELEMENTS || ancestry.size() >= MAX_DEPTH) {
                throw new XMLStreamException("1C action journal exceeds structural limits");
            }
            String kind = reader.getLocalName();
            boolean nativeNamespace = reader.getNamespaceURI() == null || reader.getNamespaceURI().isEmpty()
                    || NAMESPACE.equals(reader.getNamespaceURI());
            if (ancestry.isEmpty() && (!kind.equals("uilog") || !nativeNamespace)) {
                throw new XMLStreamException("Expected the 1C uilog document root");
            }
            if (!nativeNamespace) {
                ancestry.push(new Element("", "", ""));
                continue;
            }
            var parent = ancestry.peek();
            if (kind.equals("click") && parent != null && isRecordedButton(parent.kind())) {
                if (steps.size() == MAX_STEPS) {
                    throw new XMLStreamException("1C action journal exceeds the step limit");
                }
                steps.add(new Step(steps.size() + 1, kind, caption(ancestry, "ClientApplicationWindow"),
                        caption(ancestry, "Form"), parent.name(), parent.caption()));
            }
            String name = isRecordedButton(kind) ? attribute(reader, "name") : "";
            String title = switch (kind) {
            case "ClientApplicationWindow" -> attribute(reader, "caption");
            case "Form", "CommandInterfaceButton", "FormButton" -> attribute(reader, "title");
            default -> "";
            };
            ancestry.push(new Element(kind, name, title));
        }
        return List.copyOf(steps);
    }

    private static boolean isRecordedButton(String kind) {
        return kind.equals("CommandInterfaceButton") || kind.equals("FormButton");
    }

    private static String caption(Iterable<Element> ancestry, String kind) {
        for (var item : ancestry) {
            if (item.kind().equals(kind)) {
                return item.caption();
            }
        }
        return "";
    }

    private static String attribute(XMLStreamReader reader, String name) {
        String value = reader.getAttributeValue(null, name);
        if (value == null) {
            return "";
        }
        return value.codePoints().filter(c -> !Character.isISOControl(c)).limit(256)
                .collect(StringBuilder::new, StringBuilder::appendCodePoint, StringBuilder::append).toString();
    }
}
