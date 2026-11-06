package com.codepilot1c.core.edt.editor;
import static org.junit.Assert.*;
import org.junit.Test;
public class EdtEditorViewportServiceTest {
    @Test public void clampsOffsetsAndSupportsAbsoluteEndpoints(){
        assertEquals(500,EdtEditorViewportService.destination(450,0,500,100,"by",100));
        assertEquals(0,EdtEditorViewportService.destination(10,0,500,100,"by",-100));
        assertEquals(500,EdtEditorViewportService.destination(10,0,500,100,"end",0));
        assertEquals(0,EdtEditorViewportService.destination(450,0,500,100,"start",0));
    }
    @Test public void pageMovementDoesNotOverflowAndRejectsUnknownOperations(){
        assertEquals(1000,EdtEditorViewportService.destination(0,0,1000,100000,"page",Integer.MAX_VALUE));
        assertEquals(0,EdtEditorViewportService.destination(500,0,1000,100000,"page",Integer.MIN_VALUE));
        assertEquals(100,EdtEditorViewportService.destination(200,0,1000,100,"page",-1));
        assertThrows(IllegalArgumentException.class,()->EdtEditorViewportService.destination(0,0,100,10,"other",1));
    }
    @Test public void convertsSmallPixelDeltasToAtLeastOneRowWithoutLosingDirection(){
        assertEquals(0,EdtEditorViewportService.rowDelta(0,20));
        assertEquals(1,EdtEditorViewportService.rowDelta(1,20));
        assertEquals(-1,EdtEditorViewportService.rowDelta(-1,20));
        assertEquals(15,EdtEditorViewportService.rowDelta(300,20));
    }
}
