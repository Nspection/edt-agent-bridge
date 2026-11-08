package com.codepilot1c.core.edt.metadata;
import java.nio.file.*;
import java.util.*;
import org.eclipse.core.resources.*;
import org.eclipse.core.runtime.NullProgressMonitor;
import org.osgi.framework.*;
import com._1c.g5.v8.dt.core.platform.IConfigurationProjectManager;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.platform.version.Version;
public final class EdtNativeProjects {
    public static Map<String,Object> create(String name,String location,String versionText,String configurationName) throws Exception {
        IWorkspace workspace=ResourcesPlugin.getWorkspace();
        if(name==null || !workspace.validateName(name,IResource.PROJECT).isOK()){throw new IllegalArgumentException("Invalid workspace project name");}
        if(!MetadataNameValidator.isValidName(configurationName)){throw new IllegalArgumentException("Invalid configuration name");}
        if(workspace.getRoot().getProject(name).exists()){throw new IllegalArgumentException("Project already exists: "+name);}
        Path path=Path.of(location).toAbsolutePath().normalize();
        if(!Path.of(location).isAbsolute() || Files.exists(path)){throw new IllegalArgumentException("project_path must be an absolute new directory");}
        Version version=new Version(versionText);
        if(!Version.getPlatformSupportVersions().contains(version)){throw new IllegalArgumentException("EDT does not support requested version: "+version);}
        var factory=EdtNativeCreation.platformFactory();
        Configuration configuration=factory.create(MdClassPackage.Literals.CONFIGURATION,version);
        if(configuration==null){throw new IllegalStateException("EDT declined Configuration creation");}
        factory.fillDefaultReferences(configuration);configuration.setName(configurationName);
        configuration.setScriptVariant(ScriptVariant.RUSSIAN);
        if(configuration.getDefaultLanguage()==null) {
            Language language=factory.create(MdClassPackage.Literals.LANGUAGE,version);
            language.setName("Русский");language.setLanguageCode("ru");
            if(language.getUuid()==null){language.setUuid(UUID.randomUUID());}
            configuration.getLanguages().add(language);configuration.setDefaultLanguage(language);
        }
        if(configuration.getUuid()==null){configuration.setUuid(UUID.randomUUID());}
        BundleContext context=FrameworkUtil.getBundle(EdtNativeProjects.class).getBundleContext();
        ServiceReference<IConfigurationProjectManager> reference=context.getServiceReference(IConfigurationProjectManager.class);
        if(reference==null){throw new IllegalStateException("EDT configuration project manager is not available");}
        var manager=context.getService(reference);
        try {
            if(manager==null){throw new IllegalStateException("EDT project manager stopped");}
            IProject created=manager.create(name,path,version,configuration,new NullProgressMonitor());
            if(created==null){throw new IllegalStateException("EDT returned no project; inspect workspace before retrying");}
            return Map.of("project",created.getName(),"project_path",created.getLocation().toOSString(),"strategy","edt-configuration-project-manager","created",true,"readiness","loading");
        } finally {context.ungetService(reference);}
    }

    /** Cleanup is deliberately restricted to marked disposable creation-audit fixtures. */
    public static Map<String,Object> cleanupCreationAudit(String name,String expectedLocation,boolean dryRun) throws Exception {
        IWorkspace workspace=ResourcesPlugin.getWorkspace();
        if(name==null || !name.startsWith("BridgeCreation") || !workspace.validateName(name,IResource.PROJECT).isOK()) {
            throw new IllegalArgumentException("Only BridgeCreation audit projects can be cleaned");
        }
        IProject project=workspace.getRoot().getProject(name);
        if(!project.exists() || project.getLocation()==null) {
            throw new IllegalArgumentException("Audit project does not exist");
        }
        Path actual=project.getLocation().toFile().toPath().toRealPath();
        checkAuditCleanupLocation(actual,Path.of(expectedLocation));
        var marker=com.google.gson.JsonParser.parseString(Files.readString(actual.resolve(".edt-bridge-creation-audit.json"))).getAsJsonObject();
        if(!"edt-bridge-creation-coverage".equals(marker.get("purpose").getAsString())
                || !name.equals(marker.get("project").getAsString())) {
            throw new IllegalArgumentException("Not a marked creation-audit project");
        }
        var factory=javax.xml.parsers.DocumentBuilderFactory.newInstance();
        factory.setFeature("http://apache.org/xml/features/disallow-doctype-decl",true);
        var configuration=factory.newDocumentBuilder().parse(actual.resolve("src/Configuration/Configuration.mdo").toFile()).getDocumentElement();
        if(!marker.get("configuration_uuid").getAsString().equals(configuration.getAttribute("uuid"))) {
            throw new IllegalArgumentException("Audit project identity changed");
        }
        if(!dryRun) {
            workspace.run(monitor -> project.delete(true,false,monitor),workspace.getRoot(),IWorkspace.AVOID_UPDATE,new NullProgressMonitor());
        }
        return Map.of("project",name,"project_path",actual.toString(),"dry_run",dryRun,"removed",!dryRun && !project.exists());
    }
    static void checkAuditCleanupLocation(Path actual,Path expected) throws java.io.IOException {
        if(!expected.isAbsolute() || !actual.equals(expected.toRealPath()) || !actual.equals(expected.toAbsolutePath().normalize())) {
            throw new IllegalArgumentException("Audit path must exactly match the project location, without symlinks");
        }
        String normalized=actual.toString().replace('\\','/');
        if(!normalized.contains("/.runs/creation-audit/") || !"project".equals(actual.getFileName().toString())) {
            throw new IllegalArgumentException("Cleanup is restricted to .runs/creation-audit/.../project fixtures");
        }
    }
}
