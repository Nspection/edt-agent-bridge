package com.codepilot1c.core.edt.runtime;
import org.junit.Test;
import static org.junit.Assert.*;
import java.nio.file.Path;
public class EdtInfobaseConnectionIdentityTest {
    @Test public void equivalentFileConnectionsIgnoreTrailingSeparatorsAndCredentials() {
        String path=Path.of(System.getProperty("user.home"),"fixture-base").toString();
        assertTrue(EdtInfobaseConnectService.sameConnection("File=\""+path+"\";Usr=\"first\";", "File=\""+path+java.io.File.separator+"\";Usr=\"second\";"));
    }
    @Test public void distinctDatabasesAreNotEquivalent(){assertFalse(EdtInfobaseConnectService.sameConnection("File=\"C:/fixture/a\";","File=\"C:/fixture/b\";"));}
    @Test public void missingConnectionsAreNotEquivalent(){assertFalse(EdtInfobaseConnectService.sameConnection(null,"File=\"C:/fixture/a\";"));}
    @Test public void differentServerDatabasesStayDistinct(){assertFalse(EdtInfobaseConnectService.sameConnection("Srvr=host;Ref=first;","Srvr=host;Ref=second;"));}
}
