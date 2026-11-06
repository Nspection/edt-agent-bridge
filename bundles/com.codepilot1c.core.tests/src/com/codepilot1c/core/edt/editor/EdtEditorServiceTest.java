package com.codepilot1c.core.edt.editor;
import static org.junit.Assert.*;
import java.util.*;
import org.junit.Test;
public class EdtEditorServiceTest {
    @Test public void restoresOnlyMinimizedWindowsUnlessExplicitlyDisabled() {
        assertTrue(EdtEditorService.shouldRestoreWindow(true,Map.of()));
        assertTrue(EdtEditorService.shouldRestoreWindow(true,Map.of("auto_restore",true)));
        assertFalse(EdtEditorService.shouldRestoreWindow(false,Map.of()));
        assertFalse(EdtEditorService.shouldRestoreWindow(true,Map.of("auto_restore",false)));
    }
    @Test public void rejectsNonBooleanRestoreSetting() {
        for(Object value:List.of("false",0,1)) {
            assertThrows(IllegalArgumentException.class,()->EdtEditorService.shouldRestoreWindow(true,Map.of("auto_restore",value)));
        }
    }
    @Test public void normalizesObjectAndFormPaths() {
        assertEquals("Documents/Doc/Doc.mdo",EdtEditorService.normalizePath("src\\Documents\\Doc\\Doc.mdo"));
        assertEquals("Documents/Doc/Forms/Main/Form.form",EdtEditorService.normalizePath("Documents/Doc/Forms/Main/Form.form"));
    }
    @Test public void rejectsTraversalAbsoluteAndUnsupportedFiles() {
        for(String path:List.of("../Doc.mdo","Documents/../Doc.mdo","C:\\Doc.mdo","/Doc.mdo","//server/Doc.mdo","Documents//Doc.mdo","Documents/./Doc.mdo","Documents/Doc/Module.bsl","")) {
            assertThrows(path,IllegalArgumentException.class,()->EdtEditorService.normalizePath(path));
        }
    }
    private List<Map<String,Object>> tabs(){return List.of(Map.of("index",0,"id","main","title","Основные"),Map.of("index",1,"id","data","title","Данные 9"));}
    @Test public void selectsStableIdAndExactDisplayedTitleAndIndex() {
        assertEquals(1,EdtEditorService.selectTab(tabs(),Map.of("tab_id","data")));
        assertEquals(1,EdtEditorService.selectTab(tabs(),Map.of("tab_title","Данные 9")));
        assertEquals(0,EdtEditorService.selectTab(tabs(),Map.of("tab_index",0.0)));
    }
    @Test public void rejectsMissingAmbiguousAndInvalidSelection() {
        for(Map<String,Object> selection:List.<Map<String,Object>>of(Map.of(),Map.of("tab_id","main","tab_index",0),Map.of("tab_index",-1),Map.of("tab_index",0.5),Map.of("tab_title","Данные"),Map.of("tab_id","missing"))) {
            assertThrows(IllegalArgumentException.class,()->EdtEditorService.selectTab(tabs(),selection));
        }
        var duplicates=List.<Map<String,Object>>of(Map.of("index",0,"id","a","title","Same"),Map.of("index",1,"id","b","title","Same"));
        assertThrows(IllegalArgumentException.class,()->EdtEditorService.selectTab(duplicates,Map.of("tab_title","Same")));
    }
}
