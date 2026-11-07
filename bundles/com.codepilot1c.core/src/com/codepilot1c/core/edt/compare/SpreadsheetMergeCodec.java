// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;

import java.io.*;
import java.nio.charset.StandardCharsets;
import java.util.*;
import javax.xml.XMLConstants;
import javax.xml.parsers.DocumentBuilderFactory;
import org.eclipse.emf.common.util.URI;
import com._1c.g5.v8.dt.core.platform.IDtProject;
import com._1c.g5.v8.dt.moxel.*;

/** Native mxlx round trip. Never changes source resources. */
public final class SpreadsheetMergeCodec {
    public static final int MAX_BYTES=2*1024*1024;
    private SpreadsheetMergeCodec() {}
    public static MoxelResourceMxlx load(String xml,IDtProject project,URI uri) {
        byte[] bytes=checkedBytes(xml);
        try {
            var resource=new MoxelResourceMxlx(uri);resource.setDtProject(project);
            resource.load(new ByteArrayInputStream(bytes),Map.of());
            if(resource.getContents().size()!=1 || !(resource.getContents().get(0) instanceof SpreadsheetDocument) || !resource.getErrors().isEmpty())
                throw new IllegalArgumentException("Expected one valid native SpreadsheetDocument");
            return resource;
        } catch(IOException e){throw new IllegalArgumentException("Cannot load native spreadsheet",e);}
    }
    public static byte[] checkedBytes(String xml) {
        if(xml==null || xml.length()>MAX_BYTES)throw new IllegalArgumentException("Missing/oversized spreadsheet XML");
        byte[] bytes=xml.getBytes(StandardCharsets.UTF_8);
        if(bytes.length>MAX_BYTES)throw new IllegalArgumentException("Spreadsheet exceeds 2 MiB");
        try {
            var f=DocumentBuilderFactory.newInstance();f.setNamespaceAware(true);
            f.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
            f.setFeature("http://xml.org/sax/features/external-general-entities",false);
            f.setFeature("http://xml.org/sax/features/external-parameter-entities",false);
            f.setAttribute(XMLConstants.ACCESS_EXTERNAL_DTD,"");f.setAttribute(XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
            var builder=f.newDocumentBuilder();
            builder.setErrorHandler(new org.xml.sax.helpers.DefaultHandler(){
                @Override public void error(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
                @Override public void fatalError(org.xml.sax.SAXParseException e)throws org.xml.sax.SAXException{throw e;}
            });
            var root=builder.parse(new ByteArrayInputStream(bytes)).getDocumentElement();
            if(!"document".equals(root.getLocalName()) || !"http://v8.1c.ru/8.2/data/spreadsheet".equals(root.getNamespaceURI()))throw new IllegalArgumentException("Expected SpreadsheetDocument XML root");
            return bytes;
        }catch(Exception e){throw new IllegalArgumentException("Invalid or unsafe spreadsheet XML",e);}
    }
    public static String saveDocument(SpreadsheetDocument document,IDtProject project) {
        // Non-owning resource view: do not copy special native color/style values or move the BM object.
        var r=new MoxelResourceMxlx(URI.createURI("memory:/result.mxlx")) {
            private final org.eclipse.emf.common.util.EList<org.eclipse.emf.ecore.EObject> roots=new org.eclipse.emf.common.util.BasicEList<>(java.util.List.of(document));
            @Override public org.eclipse.emf.common.util.EList<org.eclipse.emf.ecore.EObject> getContents(){return roots;}
        };
        r.setDtProject(project);return save(r);
    }
    public static String save(MoxelResourceMxlx resource) {
        try {
            var out=new ByteArrayOutputStream();resource.save(out,Map.of("ENCODING","UTF-8"));
            String xml=out.toString(StandardCharsets.UTF_8);checkedBytes(xml);return xml;
        }catch(IOException e){throw new IllegalStateException("Cannot serialize native spreadsheet",e);}
    }
}
