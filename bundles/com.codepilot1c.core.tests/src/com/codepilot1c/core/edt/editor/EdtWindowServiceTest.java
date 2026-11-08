package com.codepilot1c.core.edt.editor;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class EdtWindowServiceTest {
    @Test public void returnsFocusOnlyWhenEdtTookItFromAnotherExistingWindow(){
        assertTrue(EdtWindowService.shouldReturnFocus(10,20,20));
        assertFalse(EdtWindowService.shouldReturnFocus(0,20,20));
        assertFalse(EdtWindowService.shouldReturnFocus(20,20,20));
        assertFalse(EdtWindowService.shouldReturnFocus(10,30,20));
    }
    @Test public void defaultsToInspectionAndAcceptsOnlySupportedActions(){
        assertEquals("state",EdtWindowService.action(Map.of()));
        for(String action:List.of("state","minimize","restore")){
            assertEquals(action,EdtWindowService.action(Map.of("action",action)));
        }
    }
    @Test public void rejectsUnknownAndNonStringActionsBeforeTouchingUi(){
        for(Object action:List.of("close","",true,5)){
            assertThrows(IllegalArgumentException.class,()->EdtWindowService.action(Map.of("action",action)));
        }
    }
}
