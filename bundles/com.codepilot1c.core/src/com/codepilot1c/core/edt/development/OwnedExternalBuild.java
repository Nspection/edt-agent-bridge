// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.nio.file.*;import java.util.*;
import org.eclipse.core.resources.*;import org.eclipse.core.runtime.Platform;import org.eclipse.core.runtime.NullProgressMonitor;
import com._1c.g5.v8.dt.core.platform.IExternalObjectProject;
import com._1c.g5.v8.dt.platform.services.core.dump.IExternalObjectDumpSupport;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
/** Stop automatic EPF generation only for a newly owned temporary external project. */
final class OwnedExternalBuild {
 static final String PREFERENCES=".settings/com._1c.g5.v8.dt.platform.services.core.prefs";
 private static final String DISABLED="com._1c.g5.v8.dt.platform.services.core.PREF_AUTO_EXT_OBJECT_DUMP_GENERATION=false";
 static boolean isDisabledDumpPreferences(Path file)throws Exception {
  if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||Files.size(file)>512)return false;
  var lines=Files.readAllLines(file);
  return lines.size()==2&&lines.contains(DISABLED)&&lines.contains("eclipse.preferences.version=1");
 }
 static Map<String,Object> generatedDumpProof(Path root,Path expected,Path generated)throws Exception {
  Path file=generated.toAbsolutePath().normalize(),ownedRoot=root.toAbsolutePath().normalize();
  if(!file.equals(expected)||!file.startsWith(ownedRoot)||Files.isSymbolicLink(file))
   throw new IllegalStateException("Native dump output ownership changed");
  // EDT may remove its automatic output when disabling that producer. The explicit
  // dump after form creation builds the artifact actually used by the host.
  if(Files.notExists(file))return Map.of("automatic_dump_present_after_disable",false);
  if(!Files.isRegularFile(file,LinkOption.NOFOLLOW_LINKS)||!file.toRealPath().startsWith(root.toRealPath()))
   throw new IllegalStateException("Native dump output ownership changed");
  return Map.of("generated_path",ownedRoot.relativize(file).toString().replace('\\','/'),
    "generated_sha256",NativePackageService.sha(file));
 }
 interface DumpProofSink { void record(Map<String,Object> proof)throws Exception; }
 static Map<String,Object> pause(String project,String object,Path root,DumpProofSink sink)throws Exception{
  var gateway=new EdtMetadataGateway();var p=gateway.resolveProject(project);
  if(p.getLocation()==null||!p.getLocation().toFile().toPath().toRealPath().equals(root.toRealPath()))throw new IllegalStateException("External project location changed");
  var model=gateway.getV8ProjectManager().getProject(p);
  if(!(model instanceof IExternalObjectProject external))throw new IllegalStateException("Owned external model unavailable");
  var objects=external.getExternalObjects().stream().filter(md->object.equals(md.getName())).toList();
  if(objects.size()!=1)throw new IllegalStateException("Owned external object not unique");
  var context=Platform.getBundle("com._1c.g5.v8.dt.platform.services.core").getBundleContext();var reference=context.getServiceReference(IExternalObjectDumpSupport.class);
  if(reference==null)throw new IllegalStateException("Native per-project dump control unavailable");
  var support=context.getService(reference);if(support==null){context.ungetService(reference);throw new IllegalStateException("Native dump control stopped");}
  try{
   var proof=new LinkedHashMap<String,Object>();proof.put("automatic_dump_was_enabled",support.isEnabled(p));
   var dt=gateway.getDtProjectManager().getDtProject(p);var modelManager=gateway.getBmModelManager();
   if(dt==null||!modelManager.forceExport(dt,"ExternalDataProcessor."+object))throw new IllegalStateException("Owned descriptor export unavailable");
   var derived=gateway.getDerivedDataManagerProvider().get(dt);
   if(derived==null||!derived.waitComputation(60000L,true,"EXP_O","EXP_B")||!derived.waitImportantDataComputations(60000L))throw new IllegalStateException("Owned descriptor export incomplete");
   modelManager.waitModelSynchronization(p);
   Path generated=null,expected=null;
   if(support.isEnabled(p)){
    Path store=support.getDumpStorePath(p).toAbsolutePath().normalize();if(!store.startsWith(root.toAbsolutePath().normalize()))throw new IllegalStateException("External dump output outside owned project");
    // This public API joins only this project/object producer family, not workspace builds.
    expected=store.resolve(object+".epf");
    generated=support.getDump(p,objects.get(0),true,new NullProgressMonitor());
    if(generated!=null)sink.record(generatedDumpProof(root,expected,generated));
   }
   support.setEnabled(p,false);new ProjectScope(p).getNode("com._1c.g5.v8.dt.platform.services.core").flush();if(support.isEnabled(p))throw new IllegalStateException("Owned automatic dump remains enabled");
   proof.put("automatic_dump_disabled",true);proof.put("preferences_sha256",NativePackageService.sha(root.resolve(PREFERENCES)));
   if(generated!=null)proof.putAll(generatedDumpProof(root,expected,generated));
   String source="src/ExternalDataProcessors/"+object+"/"+object+".mdo";Path descriptor=root.resolve(source);
   var xml=javax.xml.parsers.DocumentBuilderFactory.newInstance();xml.setNamespaceAware(true);xml.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);xml.setFeature("http://xml.org/sax/features/external-general-entities",false);xml.setFeature("http://xml.org/sax/features/external-parameter-entities",false);xml.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_DTD,"");xml.setAttribute(javax.xml.XMLConstants.ACCESS_EXTERNAL_SCHEMA,"");
   byte[] descriptorBytes;try(var input=Files.newInputStream(descriptor,LinkOption.NOFOLLOW_LINKS)){descriptorBytes=input.readNBytes(2097153);}if(descriptorBytes.length>2097152)throw new IllegalStateException("Owned descriptor too large");
   var document=xml.newDocumentBuilder().parse(new java.io.ByteArrayInputStream(descriptorBytes));var element=document.getDocumentElement();
   if(!"ExternalDataProcessor".equals(element.getLocalName())||!"http://g5.1c.ru/v8/dt/metadata/mdclass".equals(element.getNamespaceURI())||!Objects.toString(objects.get(0).getUuid()).equals(element.getAttribute("uuid"))||!object.equals(element.getElementsByTagName("name").item(0).getTextContent()))throw new IllegalStateException("Initial external descriptor identity changed");
   proof.put("source_path",source);proof.put("source_sha256",HexFormat.of().formatHex(java.security.MessageDigest.getInstance("SHA-256").digest(descriptorBytes)));
   return proof;
  }finally{context.ungetService(reference);}
 }
}
