package com.codepilot1c.core.qa;
import static org.junit.Assert.*;
import java.lang.reflect.Method;
import com.google.gson.JsonObject;
import com.codepilot1c.core.tools.qa.QaRunTool;
import com.codepilot1c.core.edt.runtime.EdtRuntimeService;
import org.junit.Test;
public class QaRunClientParamsTest {
    @Test public void emitsVanessaSettingsClientTableInsteadOfFormAttribute() throws Exception {
        QaConfig config=new QaConfig(); QaConfig.TestClient c=new QaConfig.TestClient();
        c.name="TestClient";c.ib_connection="File=fixture;";c.port=1538;
        config.test_clients.add(c);JsonObject params=new JsonObject();
        Method m=QaRunTool.class.getDeclaredMethod("applyTestClients",JsonObject.class,QaConfig.class,
                String.class,boolean.class,EdtRuntimeService.AccessSettings.class);
        m.setAccessible(true);m.invoke(null,params,config,null,false,null);
        assertFalse(params.has("ДанныеКлиентовТестирования"));
        assertEquals(1,params.getAsJsonArray("КлиентыТестирования").size());
        assertEquals("TestClient",params.getAsJsonArray("КлиентыТестирования").get(0).getAsJsonObject().get("Имя").getAsString());
    }
}
