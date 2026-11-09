// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import org.junit.*;import static org.junit.Assert.*;import org.junit.rules.TemporaryFolder;import java.nio.file.*;import java.nio.charset.StandardCharsets;
public class NativeHostStoredVerificationTest {
 @Rule public TemporaryFolder temporary=new TemporaryFolder();
 private static final String LEGEND="***- Object changed\r\n-->- Object available only in the file\r\n<--- Object available only in the database configuration\r\n^- Object order changed\r\n";
 private Path report(String text)throws Exception{Path p=temporary.newFile().toPath();Files.writeString(p,"\uFEFF"+text,StandardCharsets.UTF_16LE);return p;}
 private Path xml(String child)throws Exception{Path p=temporary.newFile().toPath();Files.writeString(p,"<MetaDataObject xmlns=\"http://v8.1c.ru/8.3/MDClasses\"><Configuration uuid=\"fixture\"><ChildObjects>"+child+"</ChildObjects></Configuration></MetaDataObject>");return p;}
 @Test public void typedInventoryAndIdenticalSnapshotAreRequired()throws Exception{NativeHostVerification.requireStoredAbsent(xml("<DataProcessor>Other</DataProcessor>"),report(LEGEND),"OwnedHost");try{NativeHostVerification.requireStoredAbsent(xml("<DataProcessor>OwnedHost</DataProcessor>"),report(LEGEND),"OwnedHost");fail();}catch(IllegalStateException expected){}}
 @Test public void differencesAndUnknownReportsCannotCertifyAbsence()throws Exception{for(String body:new String[]{"No differences",LEGEND+"***- DataProcessor.OwnedHost",LEGEND.replace("Object changed","Unknown changed")}){try{NativeHostVerification.requireStoredAbsent(xml(""),report(body),"OwnedHost");fail();}catch(IllegalStateException expected){}}}
 @Test public void absentInventoryOrUnknownXmlCannotCertifyAbsence()throws Exception{Path p=temporary.newFile().toPath();Files.writeString(p,"<Configuration/>");try{NativeHostVerification.requireStoredAbsent(p,report(LEGEND),"OwnedHost");fail();}catch(IllegalStateException expected){}}
 @Test public void externalXmlEntityIsRejected()throws Exception{Path p=temporary.newFile().toPath();Files.writeString(p,"<!DOCTYPE x [<!ENTITY e SYSTEM \"file:///unreadable-private-data\">]><MetaDataObject>&e;</MetaDataObject>");try{NativeHostVerification.requireStoredAbsent(p,report(LEGEND),"OwnedHost");fail();}catch(Exception expected){}}
}
