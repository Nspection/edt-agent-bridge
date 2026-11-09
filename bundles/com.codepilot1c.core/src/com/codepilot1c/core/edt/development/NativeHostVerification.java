// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.Map;
/** Verification cannot be transferred across bases or inferred from a queued/unknown request. */
final class NativeHostVerification {
    private NativeHostVerification(){}
    static void requireSameBase(String uuid,String identity,Map<String,Object> target){
        if(uuid==null||uuid.isBlank()||identity==null||identity.isBlank()||!uuid.equals(target.get("base_uuid"))||!identity.equals(target.get("base_identity_sha256")))throw new IllegalArgumentException("Cleanup verifier belongs to another base or lacks identity proof");
    }
    static void requireAbsent(Map<String,Object> result){
        if(!Boolean.TRUE.equals(result.get("terminal"))||!"completed".equals(result.get("state"))||!(result.get("execution") instanceof Map<?,?> execution)||!Boolean.TRUE.equals(execution.get("success"))||!Boolean.TRUE.equals(execution.get("value")))throw new IllegalStateException("Temporary metadata is still present or verification outcome is unknown");
    }
    static void requireStoredAbsent(java.nio.file.Path configuration,java.nio.file.Path report,String name)throws Exception {
        if(!java.nio.file.Files.isRegularFile(report)||java.nio.file.Files.size(report)>1024*1024)throw new IllegalStateException("Comparison report missing or too large");
        byte[] bytes;try(var input=java.nio.file.Files.newInputStream(report)){bytes=input.readNBytes(1024*1024+1);}if(bytes.length<2||bytes.length>1024*1024||bytes[0]!=(byte)0xff||bytes[1]!=(byte)0xfe)throw new IllegalStateException("Unknown native comparison report encoding");
        String text=new String(bytes,2,bytes.length-2,java.nio.charset.StandardCharsets.UTF_16LE);
        var expected=java.util.Set.of("***- Object changed","-->- Object available only in the file","<--- Object available only in the database configuration","^- Object order changed");
        var lines=text.lines().map(String::trim).filter(line->!line.isEmpty()).toList();
        if(lines.size()!=4||!new java.util.HashSet<>(lines).equals(expected))throw new IllegalStateException("Main and database configurations differ or report format is unknown");
        if(!java.nio.file.Files.isRegularFile(configuration)||java.nio.file.Files.size(configuration)>16*1024*1024)throw new IllegalStateException("Configuration export missing or too large");
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();factory.setNamespaceAware(true);factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);factory.setFeature("http://xml.org/sax/features/external-general-entities",false);factory.setFeature("http://xml.org/sax/features/external-parameter-entities",false);factory.setXIncludeAware(false);factory.setExpandEntityReferences(false);
        var doc=factory.newDocumentBuilder().parse(configuration.toFile());var root=doc.getDocumentElement();
        if(!"MetaDataObject".equals(root.getLocalName())||!"http://v8.1c.ru/8.3/MDClasses".equals(root.getNamespaceURI()))throw new IllegalStateException("Unknown metadata export");
        var configurations=root.getElementsByTagNameNS(root.getNamespaceURI(),"Configuration");if(configurations.getLength()!=1)throw new IllegalStateException("Configuration identity missing");
        var config=(org.w3c.dom.Element)configurations.item(0);if(config.getAttribute("uuid").isBlank())throw new IllegalStateException("Configuration UUID missing");
        var children=config.getElementsByTagNameNS(root.getNamespaceURI(),"ChildObjects");if(children.getLength()!=1)throw new IllegalStateException("Configuration object inventory missing");
        var objects=((org.w3c.dom.Element)children.item(0)).getElementsByTagNameNS(root.getNamespaceURI(),"DataProcessor");for(int i=0;i<objects.getLength();i++)if(name.equals(objects.item(i).getTextContent().trim()))throw new IllegalStateException("Temporary processor still present in stored configuration");
    }
}
