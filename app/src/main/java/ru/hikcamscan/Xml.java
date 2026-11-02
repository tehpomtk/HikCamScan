package ru.hikcamscan;

import java.io.*;
import javax.xml.parsers.*;
import org.w3c.dom.*;

final class Xml {
    static Document parse(byte[] bytes) throws Exception {
        if (bytes.length > 1024 * 1024) throw new IOException("XML камеры слишком большой");
        String source = new String(bytes, java.nio.charset.StandardCharsets.UTF_8);
        if (source.startsWith("\uFEFF")) source = source.substring(1);
        if (source.contains("\u0000") || source.toUpperCase(java.util.Locale.ROOT).contains("<!DOCTYPE")
                || source.toUpperCase(java.util.Locale.ROOT).contains("<!ENTITY"))
            throw new IOException("DTD и внешние сущности запрещены");
        DocumentBuilderFactory f = DocumentBuilderFactory.newInstance();
        f.setNamespaceAware(true);
        // Android's platform parser does not implement every JAXP security feature.
        // The input guard and resolver are mandatory; optional flags add protection on JVM.
        try { f.setFeature("http://apache.org/xml/features/disallow-doctype-decl", true); } catch (ParserConfigurationException ignored) { }
        try { f.setFeature("http://xml.org/sax/features/external-general-entities", false); } catch (ParserConfigurationException ignored) { }
        try { f.setFeature("http://xml.org/sax/features/external-parameter-entities", false); } catch (ParserConfigurationException ignored) { }
        f.setExpandEntityReferences(false);
        DocumentBuilder builder = f.newDocumentBuilder();
        builder.setEntityResolver((publicId, systemId) -> { throw new org.xml.sax.SAXException("External entity blocked"); });
        return builder.parse(new org.xml.sax.InputSource(new StringReader(source)));
    }
    static String text(Node root, String tag) {
        NodeList nodes = root instanceof Document ? ((Document)root).getElementsByTagNameNS("*", tag)
                : ((Element)root).getElementsByTagNameNS("*", tag);
        return nodes.getLength() == 0 ? "" : nodes.item(0).getTextContent().trim();
    }
    static String escape(String s) {
        return s.replace("&", "&amp;").replace("<", "&lt;").replace(">", "&gt;")
                .replace("\"", "&quot;").replace("'", "&apos;");
    }
}
