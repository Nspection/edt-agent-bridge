package com.codepilot1c.core.edt.debug;

import static org.junit.Assert.*;
import java.util.ArrayList;
import java.util.List;
import org.eclipse.core.resources.IProject;
import org.junit.Test;
import com.codepilot1c.core.edt.ast.EdtAstException;

public class EdtDebugServiceLaunchTest {
    @Test public void previewDoesNotLaunch() {
        Fixture f = new Fixture();
        f.manager.configs.add(new Config("Test", "Attach"));
        var result = f.service.launchDebugSession("Test", "Attach", true);
        assertEquals("dry_run", result.get("status"));
        assertEquals(0, f.manager.configs.get(0).launches);
    }
    @Test public void launchesSavedClientConfigurationInDebugModeWithoutBuild() {
        Fixture f = new Fixture(); Config config = new Config("Test", "Attach");
        f.manager.configs.add(config);
        var result = f.service.launchDebugSession("Test", null, false);
        assertEquals("debug", config.mode);
        assertFalse(config.build);
        assertEquals(List.of("http://localhost:1550"), result.get("debug_server_urls"));
    }
    @Test public void reusesActiveSessionInsteadOfStartingSecondDebugger() {
        Fixture f = new Fixture(); Config config = new Config("Test", "Attach");
        f.manager.configs.add(config); f.manager.launches.add(new Launch(config));
        f.service.launchDebugSession("Test", "Attach", false);
        assertEquals(0, config.launches);
    }
    @Test public void normalRunDoesNotPreventStartingDebugger() {
        Fixture f = new Fixture(); Config config = new Config("Test", "Attach");
        f.manager.configs.add(config); Launch run = new Launch(config); run.debug = false;
        f.manager.launches.add(run);
        var result = f.service.launchDebugSession("Test", "Attach", false);
        assertEquals(1, config.launches);
        assertEquals("debug", config.mode);
        assertEquals("debug_started", result.get("status"));
    }
    @Test public void rejectsAmbiguityAndNeverSelectsOtherProject() {
        Fixture f = new Fixture();
        f.manager.configs.add(new Config("Other", "Attach"));
        assertThrows(EdtAstException.class, () -> f.service.launchDebugSession("Test", "Attach", true));
        f.manager.configs.add(new Config("Test", "First"));
        f.manager.configs.add(new Config("Test", "Second"));
        assertThrows(EdtAstException.class, () -> f.service.launchDebugSession("Test", null, true));
        assertEquals("Second", f.service.launchDebugSession("Test", "Second", true).get("configuration"));
    }
    @Test public void rejectsMissingAndMalformedRemoteUrlBeforeLaunchEvenInPreview() {
        Fixture f = new Fixture(); Config config = new Config("Test", "Attach");
        config.type = "com._1c.g5.v8.dt.debug.core.RemoteRuntime";
        f.manager.configs.add(config);
        for (String url : List.of("", "localhost:1550", "ftp://localhost:1550", "http://")) {
            config.url = url;
            assertThrows(EdtAstException.class, () -> f.service.launchDebugSession("Test", "Attach", true));
            assertThrows(EdtAstException.class, () -> f.service.launchDebugSession("Test", "Attach", false));
        }
        assertEquals(0, config.launches);
        config.url = "http://localhost:1550";
        assertEquals("debug_started", f.service.launchDebugSession("Test", "Attach", false).get("status"));
    }
    @Test public void evaluationRequestHasRequiredUuidAndInterfaces() {
        var frame = (com._1c.g5.v8.dt.debug.core.model.IBslStackFrame) java.lang.reflect.Proxy.newProxyInstance(
                getClass().getClassLoader(), new Class<?>[]{com._1c.g5.v8.dt.debug.core.model.IBslStackFrame.class},
                (proxy, method, args) -> null);
        var request = (com._1c.g5.v8.dt.debug.core.model.evaluation.IEvaluationRequest)
                EdtDebugService.buildEvaluationRequest(
                    com._1c.g5.v8.dt.debug.core.model.evaluation.EvaluationRequest.class,
                    new com._1c.g5.v8.dt.debug.core.model.values.BslValuePath("1 + 1"), frame, null);
        assertNotNull(request.getExpressionUuid());
        assertNotNull(request.getInterfaces());
        assertSame(frame, request.getStackFrame());
        assertEquals(8192, request.getMaxTextSize());
    }
    @Test public void debugConfigurationUsesRealApplicationIdAndCreationDoesNotLaunch() {
        final var attributes=new java.util.LinkedHashMap<String,Object>();
        class Copy {
            public void setAttribute(String key,String value){attributes.put(key,value);}
            public void setAttribute(String key,boolean value){attributes.put(key,value);}
            public void setAttribute(String key,int value){attributes.put(key,value);}
            public void setMappedResources(org.eclipse.core.resources.IResource[] values){}
            public Object doSave(){return this;}
        }
        class ConfigType {public Copy newInstance(Object folder,String name){assertEquals("CodePilot Test (debug)",name);return new Copy();}}
        class SetupManager {public Object[] getLaunchConfigurations(){return new Object[0];}public ConfigType getLaunchConfigurationType(String id){assertEquals("com._1c.g5.v8.dt.launching.core.RuntimeClient",id);return new ConfigType();}}
        class SetupPlugin {public SetupManager getLaunchManager(){return new SetupManager();}}
        EdtDebugService service=new EdtDebugService(null){@Override protected IProject requireProject(String name){return null;}@Override protected Object getDebugPlugin(){return new SetupPlugin();}@Override protected String defaultApplicationId(IProject project){return "actual-app-id";}};
        assertFalse((Boolean)service.prepareDebugConfiguration("Test",null,true).get("created"));assertTrue(attributes.isEmpty());
        assertTrue((Boolean)service.prepareDebugConfiguration("Test",null,false).get("created"));
        assertEquals("actual-app-id",attributes.get("com._1c.g5.v8.dt.debug.core.ATTR_APPLICATION_ID"));
        assertEquals("AUTO",attributes.get("com._1c.g5.v8.dt.debug.core.ATTR_USE_LOCAL_DEBUG_SERVER"));
        assertEquals(Boolean.TRUE,attributes.get("com._1c.g5.v8.dt.launching.core.ATTR_LAUNCH_USER_USE_INFOBASE_ACCESS"));
    }
    @Test public void preparationNeverOverwritesAnotherProjectConfiguration() {
        Fixture f=new Fixture();f.manager.configs.add(new Config("Other","CodePilot Test (debug)"));
        assertThrows(EdtAstException.class,()->f.service.prepareDebugConfiguration("Test",null,false));
    }
    @Test public void onlyNewOwnedLaunchIsClosedAndProjectOwnershipIsChecked() {
        Fixture f=new Fixture();Config config=new Config("Test","Attach");f.manager.configs.add(config);
        var result=f.service.launchDebugSession("Test",null,false);String id=(String)result.get("owned_debug_session_id");assertNotNull(id);
        assertThrows(EdtAstException.class,()->f.service.closeOwnedDebugSession("Other",id));
        f.service.closeOwnedDebugSession("Test",id);f.service.closeOwnedDebugSession("Test",id);
        f.manager.launches.add(new Launch(config));assertFalse(f.service.launchDebugSession("Test",null,false).containsKey("owned_debug_session_id"));
    }
    @Test public void variableReadWaitsForNativePendingValue() {
        class Value {int reads;public boolean isPending(){return ++reads<3;}}
        Value value=new Value();
        class Variable {public Value getValue(){return value;}}
        class Owner {public Object[] getVariables(){return new Object[]{new Variable()};}}
        var result=EdtDebugService.readVariableSnapshot(new Owner(),null,false);
        assertFalse(result.pending());assertEquals(1,result.variables().length);assertTrue(value.reads>=3);
    }
    @Test public void nativePlaceholderIsNotReportedAsReadyEvenWhenItsValueSaysNotPending(){
        class Value {public boolean isPending(){return false;}}
        class BslPlaceholderVariable {public Value getValue(){return new Value();}}
        class RealVariable {public Value getValue(){return new Value();}}
        class Owner {int reads;public Object[] getVariables(){return ++reads<4?new Object[]{new BslPlaceholderVariable()}:new Object[]{new RealVariable()};}}
        Owner owner=new Owner();var result=EdtDebugService.readVariableSnapshot(owner,null,false);assertFalse(result.pending());assertTrue(owner.reads>=4);assertEquals("RealVariable",result.variables()[0].getClass().getSimpleName());
    }
    @Test public void closedOwnedLaunchIsNeverReusedWhenNativeTerminationFlagLags(){
        Fixture f=new Fixture();Config config=new Config("Test","Attach");f.manager.configs.add(config);var result=f.service.launchDebugSession("Test",null,false);String id=(String)result.get("owned_debug_session_id");Object own=f.serviceOwnedLaunch(id);f.manager.launches.add((Launch)own);f.service.closeOwnedDebugSession("Test",id);assertTrue(((Launch)own).target.terminated);assertNotNull(f.service.launchDebugSession("Test",null,false).get("owned_debug_session_id"));assertEquals(2,config.launches);
    }
    @Test public void activeSameApplicationUnderAnotherConfigurationIsReusedWithoutOwnership(){
        Fixture f=new Fixture();Config requested=new Config("Test","CodePilot"),manual=new Config("Test","Manual");requested.app="app-id";manual.app="app-id";f.manager.configs.add(requested);Launch active=new Launch(manual);f.manager.launches.add(active);var result=f.service.launchDebugSession("Test","CodePilot",false);assertEquals(0,requested.launches);assertFalse(result.containsKey("owned_debug_session_id"));assertFalse(active.target.terminated);
    }
    @Test public void prepareRefreshesStaleApplicationBindingWithoutLaunching(){
        Fixture f=new Fixture();Config config=new Config("Test","CodePilot Test (debug)");config.app="old-app-id";f.manager.configs.add(config);assertEquals(Boolean.TRUE,f.service.prepareDebugConfiguration("Test",null,true).get("binding_changed"));assertEquals("old-app-id",config.app);assertThrows(EdtAstException.class,()->f.service.launchDebugSession("Test",null,false));f.service.prepareDebugConfiguration("Test",null,false);assertEquals("app-id",config.app);assertEquals(0,config.launches);
    }
    static final class Fixture {
        Object serviceOwnedLaunch(String id){try{var field=EdtDebugService.class.getDeclaredField("ownedLaunches");field.setAccessible(true);return ((java.util.Map<?,?>)field.get(service)).get(id);}catch(Exception e){throw new AssertionError(e);}}
        final Manager manager = new Manager();
        final EdtDebugService service = new EdtDebugService(null) {
            @Override protected IProject requireProject(String name) { return null; }
            @Override protected Object getDebugPlugin() { return new Plugin(manager); }
            @Override protected String defaultApplicationId(IProject project){return "app-id";}
        };
    }
    public static final class Plugin {
        final Manager manager; Plugin(Manager manager) { this.manager = manager; }
        public Manager getLaunchManager() { return manager; }
    }
    public static final class Manager {
        final List<Config> configs = new ArrayList<>(); final List<Launch> launches = new ArrayList<>();
        public Object[] getLaunchConfigurations() { return configs.toArray(); }
        public Object[] getLaunches() { return launches.toArray(); }
    }
    public static final class Config {
        final String project, name; String app="";String type = "com._1c.g5.v8.dt.launching.core.RuntimeClient"; String url = ""; int launches; String mode; boolean build;
        Config(String project, String name) { this.project = project; this.name = name; }
        public String getAttribute(String key, String fallback) { return key.endsWith("ATTR_PROJECT_NAME") ? project : key.endsWith("ATTR_DEBUG_SERVER_URL") ? url : key.endsWith("ATTR_APPLICATION_ID") ? app : fallback; }
        public Config getWorkingCopy(){return this;}
        public void setAttribute(String key,String value){if(key.endsWith("ATTR_APPLICATION_ID")){app=value;}}
        public Config doSave(){return this;}
        public String getName() { return name; }
        public Type getType() { return new Type(type); }
        public Launch launch(String mode, Object monitor, boolean build) {
            launches++; this.mode = mode; this.build = build; return new Launch(this);
        }
    }
    public static final class Type {
        final String id; Type(String id) { this.id = id; }
        public String getIdentifier() { return id; }
    }
    public static final class Launch {
        final Target target=new Target();final Config config; boolean debug = true; Launch(Config config) { this.config = config; }
        public Config getLaunchConfiguration() { return config; }
        public String getLaunchMode() { return debug ? "debug" : "run"; }
        public boolean isTerminated() { return false; }
        public void terminate() {}
        public Object[] getDebugTargets() { return debug ? new Object[]{target} : new Object[0]; }
    }
    public static final class Target {
        boolean terminated;public boolean isTerminated(){return terminated;}public void terminate(){terminated=true;}
        public String getDebugServerUrl() { return "http://localhost:1550"; }
    }
}
