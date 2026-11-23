// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.mcp.host;
import org.junit.Test;
import static org.junit.Assert.*;
import java.util.*;
public class McpCompactSurfaceTest {
 static Map<String,Object> tool(String name,boolean read){return Map.of("name",name,"description","Description "+name,"inputSchema",Map.of("type","object","properties",Map.of("value",Map.of("type","string"))),"annotations",Map.of("readOnlyHint",read,"destructiveHint",!read));}
 static List<Map<String,Object>> catalog(){return List.of(tool("edt_bridge_status",true),tool("search_in_code",true),tool("get_module_structure",true),tool("create_metadata",false),tool("edt_compare",true),tool("edt_check",false),tool("edt_test_client",false),tool("edt_debug_inspect",true),tool("edt_operation_status",true));}
 @Test public void nineDomainsPlusHelpAreSmallAndSeparate(){var definitions=McpCompactSurface.definitions(catalog());assertEquals(10,definitions.size());assertEquals(10,definitions.stream().map(d->d.get("name")).distinct().count());for(var d:definitions){assertTrue(McpCompactSurface.isFacade((String)d.get("name")));assertFalse(d.toString().contains("create_metadata"));}}
 @Test public void exactOriginalArgumentsPassThrough(){var args=Map.<String,Object>of("value","x","validation_token","token");var call=McpCompactSurface.decode("edt.edit",Map.of("op","create_metadata","args",args),catalog());assertEquals("create_metadata",call.operation());assertEquals(args,call.arguments());}
 @Test public void noOperationRunsWhenOnlyDiscovering(){assertEquals("@help",McpCompactSurface.decode("edt.edit",Map.of(),catalog()).operation());assertEquals("@route",McpCompactSurface.decode("edt.run",Map.of("op","route","args",Map.of()),catalog()).operation());}
 @Test public void wrongDomainAndUnexposedOperationsRefuse(){for(var request:List.of(Map.<String,Object>of("op","create_metadata"),Map.<String,Object>of("op","not_exposed"))){try{McpCompactSurface.decode("edt.inspect",request,catalog());fail();}catch(IllegalArgumentException expected){}}}
 @Test public void malformedWrappersRefuse(){for(var request:List.of(Map.<String,Object>of("op","create_metadata","args","bad"),Map.<String,Object>of("op",true),Map.<String,Object>of("unexpected",1),Map.<String,Object>of("args",Map.of("x",1)))){try{McpCompactSurface.decode("edt.edit",request,catalog());fail();}catch(IllegalArgumentException expected){}}}
 @Test public void fullContractAndPagedCatalogPreserveEveryOperation(){var all=new ArrayList<Object>();for(int offset=0;offset<9;offset+=2){var r=McpCompactSurface.help(Map.of("action","operations","offset",offset,"limit",2),catalog());all.addAll((List<?>)r.get("operations"));}assertEquals(10,all.size());var c=McpCompactSurface.help(Map.of("action","contract","op","create_metadata"),catalog());assertEquals("edt.edit",c.get("entry"));assertEquals(catalog().get(3),c.get("contract"));}
 @Test public void helpDoesNotRevealUnavailableCommands(){var restricted=List.of(tool("edt_bridge_status",true));assertEquals(2,McpCompactSurface.definitions(restricted).size());try{McpCompactSurface.help(Map.of("action","contract","op","create_metadata"),restricted);fail();}catch(IllegalArgumentException expected){}}
 @Test public void testClientGuideSeparatesManualControlAndRecordingAndRespectsExposure(){
    var args=Map.<String,Object>of("action","guide","topic","testclient");
    var tools=List.of(tool("edt_launch_app",false),tool("edt_test_client",false),tool("edt_bug_reproduction",false));
    assertEquals(3,((List<?>)McpCompactSurface.help(args,tools).get("routes")).size());
    var scoped=McpCompactSurface.help(args,List.of(tools.get(1)));
    assertEquals(1,((List<?>)scoped.get("routes")).size());
    assertFalse(scoped.toString().contains("edt_bug_reproduction"));
    assertFalse(scoped.toString().contains("edt_launch_app"));
 }
 @Test public void editorGuideOffersAnExplicitMcpFallback(){
    var args=Map.<String,Object>of("action","guide","topic","editor");
    var guide=McpCompactSurface.help(args,List.of(tool("edt_editor_open",true)));
    assertEquals("edt_editor_open",guide.get("op"));
    assertEquals("edt.inspect",guide.get("entry"));
    assertEquals(216,((Map<?,?>)guide.get("example_args")).get("line"));
    assertThrows(IllegalArgumentException.class,()->McpCompactSurface.help(args,List.of()));
 }
 @Test public void agentTurnContractShowsExistingPathsAndExplicitAdditions() {
    var guide = McpCompactSurface.help(Map.of("action", "contract", "op", "edt_agent_turn_review"),
            List.of(tool("edt_agent_turn_review", false)));
    var examples = (List<?>) guide.get("examples");
    assertEquals(Map.of("action", "begin"), examples.get(0));
    assertEquals(List.of("src/Catalogs/Items/Forms/Item/Form.form"), ((Map<?,?>) examples.get(1)).get("files"));
    assertEquals(List.of("src/CommonModules/NewModule/Module.bsl"), ((Map<?,?>) examples.get(2)).get("additions"));
 }
 @Test public void discoversHistoryByPanelNamesAndAliases() {
    var operations = List.of(tool("edt_agent_history", false), tool("edt_agent_turn_review", false));
    for (String alias : List.of("message", "panel", "Сообщения ИИ", "Изменения ИИ", "changes", "review")) {
        var found = McpCompactSurface.help(Map.of("action", "operations", "filter", alias), operations);
        assertTrue(alias, ((Number) found.get("total")).intValue() > 0);
    }
    assertTrue(McpCompactSurface.help(Map.of(), operations).containsKey("agent_history"));
    assertFalse(McpCompactSurface.help(Map.of(), catalog()).containsKey("agent_history"));
 }
 @Test public void historyGuideShowsLinkedAndStandaloneTurnsAndRespectsExposure() {
    var args = Map.<String,Object>of("action","guide","topic","agent_history");
    var history = tool("edt_agent_history", false);
    var review = tool("edt_agent_turn_review", false);
    var guide = McpCompactSurface.help(args, List.of(history, review));
    assertEquals(5, ((List<?>) guide.get("edit_turn")).size());
    assertTrue(guide.toString().contains("element_id=16"));
    assertTrue(guide.toString().contains("turn-<your UUID>"));
    assertFalse(McpCompactSurface.help(args, List.of(history)).toString().contains("edt_agent_turn_review"));
    assertFalse(McpCompactSurface.help(args, List.of(review)).containsKey("standalone"));
    assertThrows(IllegalArgumentException.class, () -> McpCompactSurface.help(args, List.of()));
 }
 @Test public void emptyPolicyDoesNotAdvertiseVirtualCapabilities(){assertTrue(McpCompactSurface.definitions(List.of()).isEmpty());}
 @Test public void mixedGroupNeverClaimsReadOnly(){var mixed=List.of(tool("edt_compare",true),tool("edt_merge_apply",false));var definition=McpCompactSurface.definitions(mixed).get(0);assertEquals(false,((Map<?,?>)definition.get("annotations")).get("readOnlyHint"));}
 @Test public void unsupportedPaginationNeverAllocatesUnboundedReplies(){for(Object limit:List.of(0,51,1.5,"20")){try{McpCompactSurface.help(Map.of("action","operations","limit",limit),catalog());fail();}catch(IllegalArgumentException expected){}}}
 @Test public void routePrefersNativeForCodeAndData(){for(String intent:List.of("code","query","data"))assertEquals("native",McpRuntimeRouting.choose(Map.of("intent",intent),false).get("engine"));}
 @Test public void directUiHasPriorityWhenPythonAvailable(){assertEquals("testclient",McpRuntimeRouting.choose(Map.of("intent","ui","required_actions",List.of("open","input","wait")),true).get("engine"));}
 @Test public void unsupportedControlsOrMissingPythonUseVanessaPlan(){assertEquals("vanessa",McpRuntimeRouting.choose(Map.of("intent","ui","required_actions",List.of("spreadsheet_set_cell")),true).get("engine"));assertEquals("vanessa",McpRuntimeRouting.choose(Map.of("intent","ui"),false).get("engine"));assertEquals("vanessa",McpRuntimeRouting.choose(Map.of("intent","scenario"),true).get("engine"));}
 @Test public void typedControlsRouteToDirectClient(){for(String action:List.of("choose_value","check","uncheck","tab","activate","expand","collapse","set_date","set_position","increase","decrease","open_choices"))assertEquals("testclient",McpRuntimeRouting.choose(Map.of("intent","ui","required_actions",List.of(action)),true).get("engine"));}
 @Test public void nativeCannotStandInForUiAcceptance(){assertEquals("unsupported",McpRuntimeRouting.choose(Map.of("intent","ui","engine","native"),true).get("state"));}
 @Test public void unknownOrRunningOperationBlocksAnyExecutorSwitch(){for(String state:List.of("outcome_unknown","running")){var r=McpRuntimeRouting.choose(Map.of("intent","ui","previous_state",state),true);assertEquals("blocked",r.get("state"));assertEquals(false,r.get("executor_switch_allowed"));assertFalse(r.containsKey("next_call"));}}
 @Test public void routeNeverLaunchesOrClaimsRuntimeReadiness(){var r=McpRuntimeRouting.choose(Map.of("intent","ui"),true);assertEquals("planned",r.get("state"));assertEquals(false,r.get("side_effects"));assertEquals(false,r.get("automatic_launch"));}

 @Test public void facadeDenyDoesNotExpandOrReplaceCanonicalAuthorization(){var cfg=McpHostConfig.defaults();cfg.setExposedToolsFilter("create_metadata,-edt.edit");var policy=new DefaultMcpToolExposurePolicy(cfg,n->false);assertTrue(policy.isExposed("create_metadata"));assertFalse(policy.isFacadeAllowed("edt.edit"));assertTrue(policy.isFacadeAllowed("edt.inspect"));assertFalse(policy.isExposed("delete_metadata"));}

 @Test public void plannerContractIsDiscoverableWithoutAdvertisingEverySchema(){var c=McpCompactSurface.help(Map.of("action","contract","group","run","op","route"),catalog());assertEquals("edt.run",c.get("entry"));assertTrue(c.toString().contains("required_actions"));}
 @Test public void explicitVanessaAndUnsupportedUiDoNotProbePython(){assertFalse(McpRuntimeRouting.usesPythonProbe(Map.of("intent","ui","engine","vanessa")));assertFalse(McpRuntimeRouting.usesPythonProbe(Map.of("intent","ui","required_actions",List.of("tree"))));assertTrue(McpRuntimeRouting.usesPythonProbe(Map.of("intent","ui")));}
 @Test public void prepareAliasPreservesCanonicalDispatcherAndExposure(){var tools=List.of(tool("edt_runtime_prepare",false));var args=Map.<String,Object>of("action","prepare","project","P");var r=McpCompactSurface.decode("edt.run",Map.of("op","prepare","args",args),tools);assertEquals("edt_runtime_prepare",r.operation());assertEquals(args,r.arguments());assertEquals("edt_runtime_prepare",McpCompactSurface.help(Map.of("action","contract","op","prepare"),tools).get("op"));try{McpCompactSurface.decode("edt.run",Map.of("op","prepare"),List.of());fail();}catch(IllegalArgumentException expected){}}
 @Test public void uiAliasUsesCanonicalContractWithoutExpandingCatalog(){var tools=List.of(tool("edt_ui_run",false));var args=Map.<String,Object>of("action","start","project","P");assertEquals("edt_ui_run",McpCompactSurface.decode("edt.run",Map.of("op","ui","args",args),tools).operation());assertEquals("edt.run",McpCompactSurface.help(Map.of("action","contract","op","ui"),tools).get("entry"));try{McpCompactSurface.decode("edt.run",Map.of("op","ui"),List.of());fail();}catch(IllegalArgumentException expected){}}
 @Test public void directObservationContractSeparatesVanessaDepthOptions(){var s=com.google.gson.JsonParser.parseString(new com.codepilot1c.core.tools.development.EdtFormStateTool().getParameterSchema()).getAsJsonObject();var rule=s.getAsJsonArray("allOf").get(0).getAsJsonObject();assertEquals("testclient",rule.getAsJsonObject("if").getAsJsonObject("properties").getAsJsonObject("transport").get("const").getAsString());assertTrue(rule.getAsJsonObject("then").getAsJsonObject("not").toString().contains("max_depth"));assertTrue(s.getAsJsonObject("properties").has("max_depth"));}
 @Test public void compactUiContractExposesOwnedReuseWithoutNewFacade(){var tool=new com.codepilot1c.core.tools.development.EdtUiRunTool();var schema=com.google.gson.JsonParser.parseString(tool.getParameterSchema()).getAsJsonObject();assertTrue(schema.getAsJsonObject("properties").has("reuse_from_ui_run_id"));assertTrue(McpRuntimeRouting.policy().containsKey("ui_batch"));assertEquals(10,McpCompactSurface.definitions(catalog()).size());}
}
