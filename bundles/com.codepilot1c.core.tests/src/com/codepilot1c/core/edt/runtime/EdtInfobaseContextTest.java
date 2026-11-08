package com.codepilot1c.core.edt.runtime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.eclipse.core.resources.IProject;
import com._1c.g5.v8.dt.platform.services.core.infobases.*;
import com._1c.g5.v8.dt.platform.services.model.InfobaseReference;
public class EdtInfobaseContextTest {
    @Test public void associationAndDefaultUseTheCurrentBranchAndRetryDoesNotAssociateAgain() {
        var branch=InfobaseAssociationContext.of("refs/heads/codex/fixture");
        IProject project=(IProject)Proxy.newProxyInstance(IProject.class.getClassLoader(),new Class<?>[]{IProject.class},(p,m,a)->m.getName().equals("getName")?"Fixture":null);
        InfobaseReference reference=(InfobaseReference)Proxy.newProxyInstance(InfobaseReference.class.getClassLoader(),new Class<?>[]{InfobaseReference.class},(p,m,a)->m.getName().equals("getName")?"FixtureBase":null);
        List<InfobaseReference> bases=new ArrayList<>();AtomicInteger associated=new AtomicInteger(),defaults=new AtomicInteger();
        IInfobaseAssociation association=new IInfobaseAssociation(){public IProject getProject(){return project;}public Collection<InfobaseReference> getInfobases(){return bases;}public InfobaseReference getDefaultInfobase(){return null;}};
        IInfobaseAssociationManager manager=(IInfobaseAssociationManager)Proxy.newProxyInstance(IInfobaseAssociationManager.class.getClassLoader(),new Class<?>[]{IInfobaseAssociationManager.class},(p,m,a)->{
            if(m.getName().equals("getAssociation")){assertEquals(2,a.length);assertEquals(branch,a[1]);return Optional.of(association);}
            if(m.getName().equals("associate")){assertEquals(branch,((InfobaseAssociationSettings)a[2]).getContext());bases.add(reference);associated.incrementAndGet();return null;}
            if(m.getName().equals("setDefaultInfobase")){assertEquals(branch,a[2]);defaults.incrementAndGet();return null;}
            return null;
        });
        EdtRuntimeGateway gateway=new EdtRuntimeGateway(){@Override public IInfobaseAssociationManager getInfobaseAssociationManager(){return manager;}};
        EdtInfobaseConnectService service=new EdtInfobaseConnectService(gateway){@Override protected InfobaseAssociationContext currentContext(IProject value){assertSame(project,value);return branch;}};
        assertTrue(service.associate(project,reference,true));assertTrue(service.associate(project,reference,true));assertEquals(1,associated.get());assertEquals(2,defaults.get());
    }
    @Test public void runtimeResolvesTheDefaultBaseInTheSameBranchAsConnection() {
        var branch=InfobaseAssociationContext.of("refs/heads/codex/runtime-fixture");
        IProject project=(IProject)Proxy.newProxyInstance(IProject.class.getClassLoader(),new Class<?>[]{IProject.class},(p,m,a)->m.getName().equals("exists")?true:m.getName().equals("getName")?"Fixture":null);
        InfobaseReference reference=(InfobaseReference)Proxy.newProxyInstance(InfobaseReference.class.getClassLoader(),new Class<?>[]{InfobaseReference.class},(p,m,a)->null);
        IInfobaseAssociation association=new IInfobaseAssociation(){public IProject getProject(){return project;}public Collection<InfobaseReference> getInfobases(){return List.of(reference);}public InfobaseReference getDefaultInfobase(){return reference;}};
        IInfobaseAssociationManager manager=(IInfobaseAssociationManager)Proxy.newProxyInstance(IInfobaseAssociationManager.class.getClassLoader(),new Class<?>[]{IInfobaseAssociationManager.class},(p,m,a)->{
            if(m.getName().equals("getAssociation")){assertEquals(2,a.length);assertEquals(branch,a[1]);return Optional.of(association);}return null;
        });
        EdtRuntimeGateway gateway=new EdtRuntimeGateway(){@Override public IProject resolveProject(String name){return project;}@Override public IInfobaseAssociationManager getInfobaseAssociationManager(){return manager;}};
        EdtRuntimeService runtime=new EdtRuntimeService(gateway){@Override protected InfobaseAssociationContext currentContext(IProject value){assertSame(project,value);return branch;}};
        assertSame(reference,runtime.resolveDefaultInfobase("Fixture"));
    }
}
