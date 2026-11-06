package com.codepilot1c.core.edt.metadata;
import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.AtomicReference;
import org.eclipse.core.resources.IProject;
import org.eclipse.emf.ecore.*;
import org.junit.Test;
import com._1c.g5.v8.dt.core.model.IModelObjectFactory;
import com._1c.g5.v8.dt.core.platform.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.dt.platform.version.Version;
public class EdtNativeCreationTest {
    @SuppressWarnings("unchecked") static <T>T proxy(Class<T> type,java.lang.reflect.InvocationHandler call){return (T)Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},call);}
    static class Fixture {
        final IProject project=proxy(IProject.class,(p,m,a)->null);
        final IV8Project v8=proxy(IV8Project.class,(p,m,a)->m.getName().equals("getVersion")?Version.LATEST:null);
        final AtomicReference<Object[]> arguments=new AtomicReference<>();
        final AtomicReference<IProject> requested=new AtomicReference<>();
        boolean declined;
        final IModelObjectFactory factory=proxy(IModelObjectFactory.class,(p,m,a)->{
            if(m.getName().equals("create")){arguments.set(a);return declined?null:MdClassFactory.eINSTANCE.createCatalog();}return null;
        });
        final IV8ProjectManager manager=proxy(IV8ProjectManager.class,(p,m,a)->{if(m.getName().equals("getProject")){requested.set((IProject)a[0]);return v8;}return null;});
        final EdtNativeCreation service=new EdtNativeCreation(new EdtMetadataGateway(){@Override public IV8ProjectManager getV8ProjectManager(){return manager;}},()->factory);
    }
    @Test public void topCreationUsesProjectAwareInitializer(){Fixture f=new Fixture();f.service.inProject(f.project,()->f.service.create(MdClassPackage.Literals.CATALOG,null));assertSame(f.v8,f.arguments.get()[1]);assertSame(f.project,f.requested.get());}
    @Test public void memberCreationUsesOwnerAndProjectVersion(){Fixture f=new Fixture();Catalog owner=MdClassFactory.eINSTANCE.createCatalog();f.service.inProject(f.project,()->f.service.create(MdClassPackage.Literals.CATALOG,owner));assertSame(owner,f.arguments.get()[1]);assertSame(Version.LATEST,f.arguments.get()[2]);}
    @Test public void declinedFactoryNeverReturnsBareObject(){Fixture f=new Fixture();f.declined=true;assertThrows(MetadataOperationException.class,()->f.service.inProject(f.project,()->f.service.create(MdClassPackage.Literals.CATALOG,null)));}
    @Test public void projectScopeIsClearedAfterFailure(){Fixture f=new Fixture();assertThrows(IllegalStateException.class,()->f.service.inProject(f.project,()->{throw new IllegalStateException();}));assertThrows(MetadataOperationException.class,()->f.service.create(MdClassPackage.Literals.CATALOG,null));}
    @Test public void nestedScopeRestoresOuterProject(){Fixture f=new Fixture();IProject inner=proxy(IProject.class,(p,m,a)->null);f.service.inProject(f.project,()->{f.service.inProject(inner,()->f.service.create(MdClassPackage.Literals.CATALOG,null));f.service.create(MdClassPackage.Literals.CATALOG,null);return null;});assertSame(f.project,f.requested.get());}
    @Test public void effectiveDefaultsAndPresenceAreDistinct(){Catalog catalog=MdClassFactory.eINSTANCE.createCatalog();var properties=EdtCreationProperties.inspect(catalog,Set.of("codeLength"));assertEquals(1,properties.size());assertEquals(false,((Map<?,?>)properties.get("codeLength")).get("model_is_set"));catalog.setCodeLength(9);properties=EdtCreationProperties.inspect(catalog,Set.of("codeLength"));assertEquals(9,((Map<?,?>)properties.get("codeLength")).get("effective"));assertEquals(true,((Map<?,?>)properties.get("codeLength")).get("model_is_set"));}

    @Test public void inlineLanguageRequiresContainedDefinitionRatherThanReference() {
        assertFalse(EdtMetadataService.containsInlineLanguage("<Configuration><content>Language.English</content></Configuration>","Language.English"));
        assertTrue(EdtMetadataService.containsInlineLanguage("<Configuration><languages uuid='id'><name>English</name></languages></Configuration>","Language.English"));
        assertFalse(EdtMetadataService.containsInlineLanguage("<Configuration><languages uuid='id'><name>Other</name></languages></Configuration>","Language.English"));
    }
    @Test public void addingLanguageDoesNotCreateBrokenGenericContentLink() throws Exception {
        var service=new EdtMetadataService();var configuration=MdClassFactory.eINSTANCE.createConfiguration();
        var language=MdClassFactory.eINSTANCE.createLanguage();language.setName("English");
        var method=EdtMetadataService.class.getDeclaredMethod("addTopLevelObject",Configuration.class,MetadataKind.class,MdObject.class);method.setAccessible(true);
        method.invoke(service,configuration,MetadataKind.LANGUAGE,language);
        assertTrue(configuration.getLanguages().contains(language));assertFalse(configuration.getContent().contains(language));assertSame(configuration,language.eContainer());
    }
    @Test public void registerRequisiteUsesAttributeKindAndEnumAliasRemainsValue() throws Exception {
        var service=new EdtMetadataService();var method=EdtMetadataService.class.getDeclaredMethod("normalizeChildKind",MdObject.class,MetadataChildKind.class);method.setAccessible(true);
        assertEquals(MetadataChildKind.ATTRIBUTE,method.invoke(service,MdClassFactory.eINSTANCE.createInformationRegister(),MetadataChildKind.REQUISITE));
        assertEquals(MetadataChildKind.ENUM_VALUE,method.invoke(service,MdClassFactory.eINSTANCE.createEnum(),MetadataChildKind.REQUISITE));
    }
    @Test public void unresolvedReferenceIsReportedAsProxyWithoutInventedDefaults() {
        var command=MdClassFactory.eINSTANCE.createCommonCommand();var group=MdClassFactory.eINSTANCE.createCommandGroup();
        ((org.eclipse.emf.ecore.InternalEObject)group).eSetProxyURI(org.eclipse.emf.common.util.URI.createURI("platform:/resource/standard#ActionsPanelTools"));command.setGroup(group);
        var property=(Map<?,?>)EdtCreationProperties.inspect(command,Set.of("group")).get("group");var value=(Map<?,?>)property.get("effective");
        assertEquals("platform:/resource/standard#ActionsPanelTools",value.get("proxy_uri"));assertFalse(value.containsKey("category"));
    }

    @Test public void auditCleanupRejectsOrdinaryProjectAndMismatchedPath() throws Exception {
        var root=java.nio.file.Files.createTempDirectory("bridge-cleanup-");
        var project=java.nio.file.Files.createDirectory(root.resolve("project"));
        try {
            assertThrows(IllegalArgumentException.class,()->EdtNativeProjects.checkAuditCleanupLocation(project,project));
            assertThrows(IllegalArgumentException.class,()->EdtNativeProjects.checkAuditCleanupLocation(project,root));
        } finally {java.nio.file.Files.delete(project);java.nio.file.Files.delete(root);}
    }

    @Test public void nonSpreadsheetTemplateDoesNotReceiveMoxcelContent() {
        for (TemplateType type : TemplateType.values()) {
            assertEquals(type==TemplateType.SPREADSHEET_DOCUMENT,EdtMetadataService.needsSpreadsheetArtifact(type));
        }
    }
}
