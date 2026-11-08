// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.infobase;

import java.nio.file.*;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.eclipse.core.runtime.OperationCanceledException;
import com._1c.g5.v8.dt.platform.services.core.infobases.InfobaseReferences;
import com._1c.g5.v8.dt.platform.services.core.infobases.sync.IInfobaseSynchronizationManager;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.*;
import com._1c.g5.v8.dt.platform.services.core.runtimes.execution.IRuntimeComponentManager.ThickClientInfo;
import com._1c.g5.v8.dt.platform.services.model.*;
import com.codepilot1c.core.edt.runtime.*;
import com.codepilot1c.core.edt.metadata.EdtMetadataGateway;
import com.codepilot1c.core.edt.imports.EdtProjectImportGateway;

/** Same platform launcher and association/synchronization managers as EDT's Applications view. */
public class NativeInfobaseGateway extends EdtInfobaseConnectService {
    private final EdtRuntimeGateway gateway=new EdtRuntimeGateway();
    private final EdtRuntimeService runtime=new EdtRuntimeService();
    public IProject project(String name) {
        IProject p=gateway.resolveProject(name);
        if(p==null || !p.isAccessible()) throw new IllegalArgumentException("Open EDT project required: "+name);
        var metadata=new EdtMetadataGateway();
        if(metadata.getDtProjectManager().getDtProject(p)==null || metadata.getV8ProjectManager().getProject(p)==null
            || metadata.getConfigurationProvider().getConfiguration(p)==null)
            throw new IllegalStateException("EDT_PROJECT_NOT_READY: wait for the project context/model before starting an infobase operation: "+name);
        return p;
    }
    public Path stateRoot() {
        return com.codepilot1c.core.internal.VibeCorePlugin.getDefault().getStateLocation().toFile().toPath().resolve("infobase-operations");
    }
    public InfobaseReference reference(Path path) {
        InfobaseReference ref=InfobaseReferences.newFileInfobaseReference(path.toString());
        var resource=gateway.getInfobaseManager().getInfobasesResource();
        if(resource!=null) {
            var it=resource.getAllContents();
            while(it.hasNext()) if(it.next() instanceof InfobaseReference existing
                && existing.getConnectionString()!=null
                && InfobasePaths.connectionIdentity(existing.getConnectionString().asConnectionString()).equals(InfobasePaths.connectionIdentity(ref.getConnectionString().asConnectionString()))) return org.eclipse.emf.ecore.util.EcoreUtil.copy(existing);
        }
        ref.setName(path.getFileName().toString());ref.setUuid(UUID.randomUUID());return ref;
    }
    public InfobaseReference serverReference(ServerInfobaseTarget target) {
        var ref=target.reference();var resource=gateway.getInfobaseManager().getInfobasesResource();
        if(resource!=null){var it=resource.getAllContents();while(it.hasNext())if(it.next() instanceof InfobaseReference existing && existing.getConnectionString()!=null && InfobasePaths.connectionIdentity(existing.getConnectionString().asConnectionString()).equals(InfobasePaths.connectionIdentity(target.connection())))return org.eclipse.emf.ecore.util.EcoreUtil.copy(existing);}
        ref.setName(target.name());ref.setUuid(UUID.randomUUID());return ref;
    }
    public boolean isRegistered(InfobaseReference ref){return gateway.getInfobaseManager().findInfobaseByUuid(ref.getUuid()).isPresent();}
    public void checkPrimaryServer(String project,InfobaseReference ref,boolean primary,boolean replace){
        if(!primary || replace)return;
        var association=gateway.getInfobaseAssociationManager().getAssociation(project(project),currentContext(project(project)));
        var current=association.isEmpty()?null:association.get().getDefaultInfobase();
        if(current!=null && !InfobasePaths.connectionIdentity(current.getConnectionString().asConnectionString()).equals(InfobasePaths.connectionIdentity(ref.getConnectionString().asConnectionString())))throw new IllegalArgumentException("primary_exists: replace_primary=true required");
    }
    public void createServer(ThickClientInfo info,InfobaseReference ref,Map<String,Object> request,String database) throws Exception {
        var args=ModelFactory.eINSTANCE.createCreateServerInfobaseArguments();args.setLocale(Locale.forLanguageTag("ru-RU"));args.setDatabaseType(ServerInfobaseProvisionService.databaseType(request));
        args.setDatabaseServer(InfobaseProvisionService.required(request,"db_server"));args.setDatabaseName(database);args.setDatabaseUser(InfobaseProvisionService.required(request,"db_user"));args.setDatabasePassword(Objects.toString(request.get("db_password"),""));
        args.setForceCreate(true);args.setLockScheduledJobs(true);info.launcher().createInfobase(info.component(),ref,args,false);
    }
    public ThickClientInfo runtime(InfobaseReference ref,String version) {
        projectVersion(version);
        ThickClientInfo info=runtime.resolveThickClientInfo(ref,version);
        if(info==null) throw new IllegalStateException("Native EDT thick-client runtime could not be resolved");
        // Reuse EDT's Designer session pool. A CLI-only launcher competes with EDT's own
        // Designer agent and cannot export/dump an application that is already connected.
        try {
            var executor=gateway.getRuntimeComponentManager().resolveExecutor(ILaunchableRuntimeComponent.class,
                IDesignerSessionThickClientLauncher.class,info.installation(),
                "com._1c.g5.v8.dt.platform.services.core.componentTypes.ThickClient");
            return new ThickClientInfo(info.resolvable(),executor.getInstallation(),executor.getComponent(),executor.getExecutor());
        } catch(RuntimeExecutionException e) {throw new IllegalStateException("EDT DesignerSession launcher unavailable",e);}
    }
    public String runtimeDescription(InfobaseReference ref,String version) {return runtime(ref,version).component().getFile().toString();}
    public void pinVersion(InfobaseReference ref,String version) {
        // The persisted application must use the same build as restore/export. Otherwise
        // EDT's auto selection can open another build, producing a different session-pool key.
        ref.setVersion(version);
    }
    public void requireFull(InfobaseReference ref) throws Exception {InfobaseLifecycleState.requireFull(ref);}
    public boolean requiresFull(InfobaseReference ref) {return InfobaseLifecycleState.requiresFull(ref);}
    public Map<String,Object> primaryIdentity(String name) {
        var association=gateway.getInfobaseAssociationManager().getAssociation(project(name),currentContext(project(name)));
        var base=association.isEmpty()?null:association.get().getDefaultInfobase();
        return base==null?Map.of("present",false):Map.of("present",true,"name",Objects.toString(base.getName(),""),"uuid",Objects.toString(base.getUuid(),""));
    }
    public void checkPrimary(String project,Path path,boolean primary,boolean replace) {
        checkExistingPrimary(project(project),new ConnectRequest(project,path.toString(),ConnectionKind.FILE,null,null,primary,null,null,replace));
    }
    public RuntimeExecutionArguments arguments(String login,String password,IProgressMonitor monitor,Path log) {
        var a=new RuntimeExecutionArguments();a.setMonitor(monitor);a.setDisableStartupMessages(true);
        a.setAccess(login==null || login.isBlank()?InfobaseAccess.OS:InfobaseAccess.INFOBASE);
        if(login!=null && !login.isBlank()) {a.setUsername(login);a.setPassword(password==null?"":password);}
        a.setLogFile(log.toFile());return a;
    }
    public void create(ThickClientInfo info,InfobaseReference ref) throws Exception {
        var a=ModelFactory.eINSTANCE.createCreateInfobaseArguments();a.setLocale(Locale.forLanguageTag("ru-RU"));
        info.launcher().createInfobase(info.component(),ref,a,false);
    }
    public void restore(ThickClientInfo info,InfobaseReference ref,Path dt,RuntimeExecutionArguments args) throws Exception {
        info.launcher().importDtToInfobase(info.component(),ref,args,dt);
    }
    public void dump(ThickClientInfo info,InfobaseReference ref,Path dt,RuntimeExecutionArguments args) throws Exception {
        info.launcher().exportDtFromInfobase(info.component(),ref,args,dt);
        if(!Files.isRegularFile(dt) || Files.size(dt)==0) throw new IllegalStateException("EDT did not create a nonempty backup DT");
    }
    public Path export(ThickClientInfo info,InfobaseReference ref,Path destination,RuntimeExecutionArguments args) throws Exception {
        Files.createDirectories(destination);
        Path result=info.launcher().exportFullXmlFromInfobase(info.component(),ref,ConfigurationFilesFormat.HIERARCHICAL,ConfigurationFilesKind.PLAIN_FILES,args,destination);
        Path exported=result==null?destination:result;
        if(!Files.isRegularFile(exported.resolve("Configuration.xml"))) throw new IllegalStateException("EDT export has no Configuration.xml; restore/access not verified");
        return exported;
    }
    public List<String> ownerProjects(InfobaseReference ref) throws Exception {
        var manager=gateway.getInfobaseAssociationManager();var owners=new TreeSet<String>();
        var ordinary=manager.getAssociation(ref);ordinary.ifPresent(a->owners.add(a.getProject().getName()));
        for(IProject p:ResourcesPlugin.getWorkspace().getRoot().getProjects()) {
            for(var context:manager.getAssociationContexts(p)) {
                var association=manager.getAssociation(ref,context);
                if(association.isPresent()) owners.add(association.get().getProject().getName());
            }
        }
        return List.copyOf(owners);
    }
    public boolean detachApplication(String source,InfobaseReference ref) throws Exception {
        IProject p=project(source);boolean wasPrimary=Objects.equals(primaryIdentity(source).get("uuid"),Objects.toString(ref.getUuid(),""));
        disconnectBeforeRestore(source,ref);
        gateway.getInfobaseAssociationManager().dissociate(p,ref,currentContext(p));return wasPrimary;
    }
    public void restoreApplication(String source,InfobaseReference ref,boolean primary) {associate(project(source),ref,primary);}
    public void removeOwnAssociation(String target,InfobaseReference ref) throws Exception {
        IProject p=project(target);
        if(bound(target,ref)) {
            disconnectBeforeRestore(target,ref);
            gateway.getInfobaseAssociationManager().dissociate(p,ref,currentContext(p));
        }
    }
    public void selectRuntimeForApplication(String name,InfobaseReference ref,String version) throws Exception {
        // Launcher Version is not UPDATE's selection. EDT stores UPDATE's installation
        // separately for each project/application pair; configure it before association.
        persistReference(ref);
        gateway.getInfobaseAccessManager().updateSelectedInstallation(project(name),ref,runtime(ref,version).resolvable());
    }
    public void restoredAccess(InfobaseReference ref,String login,String password) {storeAccessSettings(ref,login,password);}
    public InfobaseReference bind(String name,InfobaseReference ref,String login,String password,boolean primary) {
        persistReference(ref);storeAccessSettings(ref,login,password);associate(project(name),ref,primary);return ref;
    }
    public boolean bound(String name,InfobaseReference ref) {
        return gateway.getInfobaseAssociationManager().getAssociation(project(name),currentContext(project(name))).map(a->a.getInfobases().stream()
            .anyMatch(r->InfobasePaths.connectionIdentity(r.getConnectionString().asConnectionString()).equals(InfobasePaths.connectionIdentity(ref.getConnectionString().asConnectionString())))).orElse(false);
    }
    public void disconnectBeforeRestore(String name,InfobaseReference ref) throws Exception {
        var manager=(IInfobaseSynchronizationManager)gateway.getInfobaseSynchronizationManager();
        // EDT 2026.1 isConnected(project, ref) ignores ref and only tests whether
        // the project has a synchronization container. It is NOT an ownership query.
        if(ownerProjects(ref).stream().anyMatch(owner->!owner.equals(name)))
            throw new IllegalStateException("Target application belongs to another project");
        IProject p=project(name);
        if(bound(name,ref) && manager.isConnected(p,ref)) manager.disconnectInfobase(p,ref,false,false,new NullProgressMonitor());
    }
    public Map<String,Object> update(String name,InfobaseReference ref,IProgressMonitor monitor) throws Exception {
        if(!runtime.updateInfobase(project(name),ref,true,monitor,true)) throw new IllegalStateException("Native full reload did not succeed");
        var manager=(IInfobaseSynchronizationManager)gateway.getInfobaseSynchronizationManager();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.SECONDS.toNanos(60);
        String equality;
        do {
            equality=manager.getEqualityState(project(name),ref).name();
            if(equality.equals("EQUAL")) break;
            if(monitor.isCanceled()) throw new OperationCanceledException();
            Thread.sleep(200);
        } while(System.nanoTime()<deadline);
        if(!equality.equals("EQUAL")) {
            requireFull(ref);
            throw new IllegalStateException("Full reload returned, but configuration equality is "+equality);
        }
        return Map.of("update_mode","full","native_method","reloadInfobase","configuration_synchronized",true,"equality",equality,"application_migrations_verified",false);
    }
    public static String projectVersion(String runtimeVersion) {
        String[] parts=runtimeVersion.split("\\.");
        if(parts.length!=4 || !runtimeVersion.matches("[0-9]+\\.[0-9]+\\.[0-9]+\\.[0-9]+"))
            throw new IllegalArgumentException("runtime_version must be an explicit four-component platform build");
        String support=String.join(".",parts[0],parts[1],parts[2]);
        if(!com._1c.g5.v8.dt.platform.version.Version.getPlatformSupportVersions().contains(new com._1c.g5.v8.dt.platform.version.Version(support)))
            throw new IllegalArgumentException("EDT platform support not installed for "+support);
        return support;
    }
    public void importNew(Path exported,Path target,String version) {
        var context=org.osgi.framework.FrameworkUtil.getBundle(NativeInfobaseGateway.class).getBundleContext();
        var reference=context.getServiceReference(com._1c.g5.v8.dt.core.lifecycle.IDtProjectResourceLifecycleBootstrap.class);
        if(reference==null) throw new IllegalStateException("EDT import lifecycle service unavailable");
        var bootstrap=context.getService(reference);
        if(bootstrap==null) {context.ungetService(reference);throw new IllegalStateException("EDT import lifecycle stopped");}
        try {
            if(Files.exists(target,LinkOption.NOFOLLOW_LINKS)) throw new IllegalStateException("Import target appeared while exporting; refusing to overwrite it");
            new EdtProjectImportGateway().getImportConfigurationFilesApi().importProject(exported,target,projectVersion(version),null);
            IProject imported=gateway.resolveProject(target.getFileName().toString());
            // CLI API deliberately suppresses refresh and holds a MANUAL import latch.
            // Release only this new project and publish its resources to the live IDE.
            bootstrap.permitImport(imported);
            imported.refreshLocal(IResource.DEPTH_INFINITE,new NullProgressMonitor());
            bootstrap.scheduleWorkspaceProjectsStart();
        } catch(org.eclipse.core.runtime.CoreException e) {throw new IllegalStateException("EDT imported project refresh failed",e);}
        finally {context.ungetService(reference);}
    }
    public void assertNoDirtyEditors(IProject project) {
        if(!org.eclipse.ui.PlatformUI.isWorkbenchRunning()) return;
        org.eclipse.ui.PlatformUI.getWorkbench().getDisplay().syncExec(()->{
            for(var window:org.eclipse.ui.PlatformUI.getWorkbench().getWorkbenchWindows())
                for(var page:window.getPages()) for(var editor:page.getDirtyEditors()) {
                    var input=editor.getEditorInput();
                    IResource resource=input.getAdapter(IResource.class);
                    if(input instanceof org.eclipse.ui.IFileEditorInput file) resource=file.getFile();
                    if(resource==null || project.equals(resource.getProject()))
                        throw new IllegalStateException("Unsaved editor content; temporary project removal refused");
                }
        });
    }
    public void awaitProject(String name) throws Exception {
        var metadata=new EdtMetadataGateway();
        long deadline=System.nanoTime()+java.util.concurrent.TimeUnit.MINUTES.toNanos(3);
        while(System.nanoTime()<deadline) {
            IProject p=gateway.resolveProject(name);
            if(p!=null && p.isAccessible()) {
                metadata.getBmModelManager().waitModelSynchronization(p);
                if(metadata.getConfigurationProvider().getConfiguration(p)!=null
                    && metadata.getDtProjectManager().getDtProject(p)!=null
                    && metadata.getV8ProjectManager().getProject(p)!=null) return;
            }
            Thread.sleep(250);
        }
        throw new IllegalStateException("Imported project/model not ready: "+name);
    }
}
