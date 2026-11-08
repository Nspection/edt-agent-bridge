// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.ui;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.*;
import java.util.*;
import javax.xml.parsers.DocumentBuilderFactory;
import com.codepilot1c.core.tools.*;
/** Registered extension tools share the exact registry consumed by existing ChatView. */
public class NativeToolsChatRegistryTest {
 @Test public void nativeExtensionToolsAreVisibleThroughTheExistingRegistry() throws Exception {
  Path root=Path.of("").toAbsolutePath();while(root!=null&&!Files.exists(root.resolve("bundles/com.codepilot1c.core/plugin.xml")))root=root.getParent();assertNotNull(root);
  var doc=DocumentBuilderFactory.newInstance().newDocumentBuilder().parse(root.resolve("bundles/com.codepilot1c.core/plugin.xml").toFile());var nodes=doc.getElementsByTagName("tool");var registry=ToolRegistry.createDetached();int count=0;
  try(var lease=ToolRegistry.installScopedForTesting(registry)) {
   for(int i=0;i<nodes.getLength();i++){String name=((org.w3c.dom.Element)nodes.item(i)).getAttribute("class");if(!name.startsWith("com.codepilot1c.core.tools.development.")&&!name.startsWith("com.codepilot1c.core.tools.editor.")&&!name.endsWith("EdtCreationDefaultsTool")&&!name.endsWith("EdtDeletePreviewTool"))continue;ITool tool=(ITool)Class.forName(name).getConstructor().newInstance();registry.register(tool);assertTrue(tool.getName(),registry.isToolModelFacing(tool.getName()));count++;}
   assertTrue(count>=50);for(String name:List.of("edt_check","edt_form_state","edt_form_action","edt_runtime_host","edt_merge_plan","edt_test_data"))assertNotNull(name,registry.resolveTool(name).tool());
   ITool capabilities=registry.resolveTool("edt_capabilities").tool();var result=capabilities.execute(Map.of("name_contains","edt_form_")).get();assertTrue(result.getContent(),result.isSuccess());assertTrue(result.getContent().contains("edt_form_state"));assertTrue(result.getContent().contains("edt_form_action"));
  }
 }
}
