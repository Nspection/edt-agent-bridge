package com.codepilot1c.core.edt.metadata;
import static org.junit.Assert.*;
import org.junit.Test;
import java.lang.reflect.*;
import java.util.*;
import com._1c.g5.v8.dt.metadata.mdclass.*;
import com._1c.g5.v8.bm.core.IBmPlatformTransaction;
import com.codepilot1c.core.edt.validation.MetadataRequestValidationService;
public class CommandCreationArtifactTest {
    final EdtMetadataService service=new EdtMetadataService();
    Object invoke(String name,Class<?>[] types,Object... args)throws Exception {
        var m=EdtMetadataService.class.getDeclaredMethod(name,types);m.setAccessible(true);
        try{return m.invoke(service,args);}catch(InvocationTargetException e){if(e.getCause() instanceof Exception x)throw x;throw e;}
    }
    @SuppressWarnings("unchecked") List<String> paths(String clazz,String resource,String top,String owner,String form,String command,ModuleArtifactKind kind)throws Exception {
        var t=Class.forName(EdtMetadataService.class.getName()+"$ModuleTarget");
        var c=t.getDeclaredConstructor(String.class,String.class,String.class,String.class,String.class,String.class);c.setAccessible(true);
        return (List<String>)invoke("buildModuleCandidates",new Class<?>[]{t,ModuleArtifactKind.class},c.newInstance(clazz,resource,top,owner,form,command),kind);
    }
    @Test public void nestedCommandHasOnlyItsOwnPathForAllSupportedKinds()throws Exception {
        for(var kind:List.of(ModuleArtifactKind.AUTO,ModuleArtifactKind.MODULE,ModuleArtifactKind.COMMAND))
            assertEquals(List.of("src/Catalogs/Owner/Commands/Run/CommandModule.bsl"),paths("CatalogCommand","src/Catalogs/Owner/Owner.mdo","Catalog","Owner",null,"Run",kind));
    }
    @Test public void commandCannotCreateObjectOrManagerModules()throws Exception {
        for(var kind:List.of(ModuleArtifactKind.OBJECT,ModuleArtifactKind.MANAGER))
            assertThrows(MetadataOperationException.class,()->paths("CatalogCommand","src/Documents/Owner/Owner.mdo","Document","Owner",null,"Run",kind));
    }
    @Test public void commonCommandModuleAliasStillUsesItsCommandModule()throws Exception {
        assertEquals(List.of("src/CommonCommands/Run/CommandModule.bsl"),paths("CommonCommand","src/CommonCommands/Run/Run.mdo","CommonCommand","Run",null,null,ModuleArtifactKind.MODULE));
    }
    @Test public void formAndCommonModulePathsRemainStable()throws Exception {
        assertEquals(List.of("src/Catalogs/Owner/Forms/Card/Module.bsl"),paths("CatalogForm","src/Catalogs/Owner/Owner.mdo","Catalog","Owner","Card",null,ModuleArtifactKind.MODULE));
        assertEquals(List.of("src/CommonModules/Shared/Module.bsl"),paths("CommonModule","src/CommonModules/Shared/Shared.mdo","CommonModule","Shared",null,null,ModuleArtifactKind.AUTO));
    }
    @Test public void structuredCommandTypesArePreResolvedIncludingBatch()throws Exception {
        var type=Map.of("types",List.of("CatalogRef.Owner","String"));
        var request=new AddMetadataChildRequest("Own","Catalog.Owner",MetadataChildKind.COMMAND,"Run",null,null,Map.of("commandParameterType",type,"children",List.of(Map.of("name","More","commandParameterType",Map.of("types",List.of("DocumentRef.Doc"))))));
        assertEquals(Set.of("CatalogRef.Owner","String","DocumentRef.Doc"),invoke("collectChildTypeStrings",new Class<?>[]{AddMetadataChildRequest.class},request));
    }
    void properties(MdObject target,Map<String,Object> props)throws Exception {
        invoke("applyCommandCreateProperties",new Class<?>[]{Configuration.class,MdObject.class,Map.class,String.class,IBmPlatformTransaction.class,Map.class,String.class},MdClassFactory.eINSTANCE.createConfiguration(),target,props,"Catalog.Owner.Command.Run",null,new HashMap<>(),null);
    }
    @Test public void scalarPropertiesUseExistingSetterAndUnknownPropertiesFail()throws Exception {
        var command=MdClassFactory.eINSTANCE.createCatalogCommand();var feature=command.eClass().getEStructuralFeature("modifiesData");
        properties(command,Map.of("modifiesData",true));assertEquals(true,command.eGet(feature));
        assertThrows(MetadataOperationException.class,()->properties(command,Map.of("typoProperty",true)));
        assertThrows(MetadataOperationException.class,()->properties(command,Map.of("uuid","foreign")));
    }
    @Test public void omittedPropertiesPreserveNativeValues()throws Exception {
        var command=MdClassFactory.eINSTANCE.createCatalogCommand();var before=EdtCreationProperties.inspect(command,Set.of("group","representation","modifiesData"));
        properties(command,Map.of());assertEquals(before,EdtCreationProperties.inspect(command,Set.of("group","representation","modifiesData")));
    }
    @Test public void malformedParameterTypeIsRejectedBeforeMutation() {
        var validation=new MetadataRequestValidationService();
        assertThrows(MetadataOperationException.class,()->validation.normalizeAddChildPayload("Own","Catalog.Owner","Command","Run",null,null,Map.of("commandParameterType",Map.of("types",List.of()))));
    }
}
