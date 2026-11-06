package com.codepilot1c.core.edt.runtime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.lang.reflect.Proxy;
import java.util.*;
import java.util.concurrent.atomic.*;
import org.eclipse.core.runtime.Status;
import com._1c.g5.v8.dt.platform.services.core.infobases.*;
import com._1c.g5.v8.dt.platform.services.model.*;
public class EdtInfobasePersistenceTest {
    private InfobaseReference reference(AtomicReference<UUID> id) {
        FileConnectionString connection=(FileConnectionString)Proxy.newProxyInstance(FileConnectionString.class.getClassLoader(),new Class<?>[]{FileConnectionString.class},(p,m,a)->m.getName().equals("asConnectionString")?"File=\"C:/fixture/base\";":null);
        return (InfobaseReference)Proxy.newProxyInstance(InfobaseReference.class.getClassLoader(),new Class<?>[]{InfobaseReference.class},(p,m,a)->switch(m.getName()){case "getUuid"->id.get();case "setUuid"->{id.set((UUID)a[0]);yield null;}case "getName"->"fixture";case "getConnectionString"->connection;default->null;});
    }
    private void exercise(boolean existing,boolean loseOnReload) {
        var id=new AtomicReference<UUID>(existing?UUID.randomUUID():null);var ref=reference(id);var persisted=new AtomicReference<UUID>();var reloaded=new AtomicBoolean();var writes=new AtomicInteger();
        IInfobaseManager manager=(IInfobaseManager)Proxy.newProxyInstance(IInfobaseManager.class.getClassLoader(),new Class<?>[]{IInfobaseManager.class},(p,m,a)->switch(m.getName()){
            case "isPersistenceSupported"->true;
            case "findInfobaseByUuid"->Optional.ofNullable(reloaded.get() && Objects.equals(persisted.get(),a[0])?ref:null);
            case "findInfobasesByNames"->existing?List.of(ref):List.of();
            case "findInfobaseByName"->Optional.empty();
            case "add","update"->{assertNotNull("UUID must exist before disk write",((InfobaseReference)a[0]).getUuid());persisted.set(((InfobaseReference)a[0]).getUuid());writes.incrementAndGet();yield null;}
            case "reload"->{reloaded.set(true);if(loseOnReload){persisted.set(null);}yield null;}
            case "getLoadStatus"->Status.OK_STATUS;
            default->null;
        });
        EdtRuntimeGateway gateway=new EdtRuntimeGateway(){@Override public IInfobaseManager getInfobaseManager(){return manager;}};
        new EdtInfobaseConnectService(gateway).persistReference(ref);
        assertTrue(reloaded.get());assertEquals(1,writes.get());assertEquals(id.get(),persisted.get());
    }
    @Test public void assignsUuidBeforeNewRegistrationAndReadsBack(){exercise(false,false);}
    @Test public void savesExistingLegacyTransientUuidBeforeBinding(){exercise(true,false);}
    @Test(expected=IllegalStateException.class) public void refusesBindingWhenReloadLosesRegistration(){exercise(false,true);}
}
