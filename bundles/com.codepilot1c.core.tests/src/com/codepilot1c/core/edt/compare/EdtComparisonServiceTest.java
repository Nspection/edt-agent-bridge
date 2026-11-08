// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;
import static org.junit.Assert.*;
import org.junit.Test;
import com._1c.g5.v8.dt.compare.model.MergeRule;
public class EdtComparisonServiceTest {
    @Test public void mcpComparisonViewDoesNotRestoreExpiredNativeIds() {
        var id=java.util.UUID.randomUUID();
        var persisted=stub(org.eclipse.ui.IPersistableElement.class,(p,m,a)->null);
        var initial=new java.util.concurrent.atomic.AtomicBoolean(true);
        var nativeInput=stub(com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInput.class,(p,m,a)->switch(m.getName()){
            case "getPersistable"->persisted;case "getUuid"->id;case "getName"->"Native comparison";
            case "setRunInitialComparison"->{initial.set((Boolean)a[0]);yield null;}case "runInitialComparison"->initial.get();default->null;
        });
        var input=EdtComparisonService.transientComparisonInput(nativeInput);
        assertNull(input.getPersistable());assertSame(id,input.getUuid());assertEquals("Native comparison",input.getName());
        input.setRunInitialComparison(false);assertFalse(nativeInput.runInitialComparison());assertTrue(input.equals(input));
        assertSame(persisted,nativeInput.getPersistable());
    }

    @Test public void missingNativeFeatureDoesNotHideTheNodeOrBreakTreeRendering() {
        var related=stub(com._1c.g5.v8.dt.compare.model.RelatedFeature.class,(p,m,a)->switch(m.getName()) {
            case "getParentFeatureEClassId" -> 17; case "getFeatureId" -> 3; default -> null;
        });
        var node=stub(com._1c.g5.v8.dt.compare.model.FeatureComparisonNode.class,(p,m,a)->switch(m.getName()) {
            case "getFeature" -> related; case "bmGetId" -> 42L; default -> null;
        });
        var session=stub(com._1c.g5.v8.dt.compare.core.IComparisonSession.class,(p,m,a)->null);
        assertEquals("FT:<unresolved:17/3>#42",EdtComparisonService.describeNode(session,node,null));
    }
    @Test public void technicalCollectionNodeRetainsItsIdentityWithoutResolvedFeature() {
        var node=stub(com._1c.g5.v8.dt.compare.model.CollectionElementComparisonNode.class,(p,m,a)->m.getName().equals("bmGetId")?43L:null);
        var session=stub(com._1c.g5.v8.dt.compare.core.IComparisonSession.class,(p,m,a)->null);
        assertEquals("CE:<unresolved:unknown>#43",EdtComparisonService.describeNode(session,node,null));
    }
    @Test public void directionsAreExplicit(){assertEquals(MergeRule.DO_NOT_MERGE,EdtComparisonService.rule("keep_main"));assertEquals(MergeRule.GET_FROM_OTHER,EdtComparisonService.rule("take_other"));assertThrows(IllegalArgumentException.class,()->EdtComparisonService.rule("left_or_right"));}
    @Test public void stalePlanOrNodeNeverPasses(){assertThrows(IllegalStateException.class,()->EdtComparisonService.requireHash("old","new"));EdtComparisonService.requireHash("same","same");}
    @Test public void patchPreservesSurroundingTextAndAllowsDeletion(){assertEquals("left\r\nright",EdtComparisonService.patch("left\r\nremove right","remove ",""));assertThrows(IllegalArgumentException.class,()->EdtComparisonService.patch("x x","x","new"));assertThrows(IllegalArgumentException.class,()->EdtComparisonService.patch("x","absent","new"));}
    @Test public void unresolvedConflictMarkersAreRejected(){assertThrows(IllegalArgumentException.class,()->EdtComparisonService.validateText("left\r\n<<<<<<< HEAD\r\nright"));EdtComparisonService.validateText("Return 1;\r\n");}
    @Test public void hashesDistinguishNullEmptyAndNewlines(){assertNotEquals(EdtComparisonService.hash(null),EdtComparisonService.hash(""));assertNotEquals(EdtComparisonService.hash("x\n"),EdtComparisonService.hash("x\r\n"));}

    @Test public void nativeParentPlaceholderIsNotMistakenForCustomText(){
        var settings=com._1c.g5.v8.dt.compare.model.CompareFactory.eINSTANCE.createMergeSettings();
        settings.setCustomSettings(com._1c.g5.v8.dt.compare.model.CompareFactory.eINSTANCE.createTextNodeCustomMergeSettings());
        settings.setMergeRule(MergeRule.MERGE_PRIORITIZING_MAIN);assertFalse(EdtComparisonService.requiresCustomValidation(settings));
        settings.setMergeRule(MergeRule.CUSTOM_MERGE);assertTrue(EdtComparisonService.requiresCustomValidation(settings));
        assertThrows(IllegalArgumentException.class,()->EdtComparisonService.validateText(null));
    }
    @Test public void successfulMergeSurvivesNativeSessionDiscard() {
        var state=new EdtComparisonService.Lifecycle();state.applyRequested=true;
        state.accept(com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus.MERGE_PROCESS_FINISHED);
        state.accept(com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus.MERGE_PROCESS_DISCARDED);
        assertTrue(state.applied);assertFalse(state.applyRequested);assertEquals("MERGE_PROCESS_FINISHED",state.effectiveStatus(null));
    }
    @Test public void cancelledMergeIsNotReportedAsApplied() {
        var state=new EdtComparisonService.Lifecycle();state.applyRequested=true;
        state.accept(com._1c.g5.v8.dt.compare.core.ComparisonProcessStatus.COMPARISON_MERGE_PROCESS_CANCELLED);
        assertFalse(state.applied);assertFalse(state.applyRequested);assertEquals("COMPARISON_MERGE_PROCESS_CANCELLED",state.effectiveStatus(null));
    }

    @SuppressWarnings("unchecked") private static <T>T stub(Class<T> type,java.lang.reflect.InvocationHandler handler) {
        return (T)java.lang.reflect.Proxy.newProxyInstance(type.getClassLoader(),new Class<?>[]{type},handler);
    }
    private static com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle handle() {
        var descriptor=stub(com._1c.g5.v8.dt.compare.datasource.IComparisonDataSourceDescriptor.class,(p,m,a)->m.getName().equals("equals")?p==a[0]:null);
        return new com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle(descriptor,descriptor,new com._1c.g5.v8.dt.compare.core.ComparisonScope(java.util.List.of(),java.util.List.of()));
    }
    @Test public void completedBindingCannotTargetANewRunWithTheSameHandle() {
        var handle=handle();
        var first=stub(com._1c.g5.v8.dt.compare.core.IComparisonSession.class,(p,m,a)->m.getName().equals("getHandle")?handle:null);
        var second=stub(com._1c.g5.v8.dt.compare.core.IComparisonSession.class,(p,m,a)->m.getName().equals("getHandle")?handle:null);
        var binding=new EdtComparisonService.SessionBinding(handle,null);
        assertTrue(binding.matches(first));assertFalse(binding.matches(second));assertSame(first,binding.get());
        binding.retire();assertFalse(binding.matches(first));assertFalse(binding.matches(second));
    }
    @Test public void pendingBindingDoesNotAttachToAnEqualButDifferentRequestHandle() {
        var requested=handle();
        var equal=new com._1c.g5.v8.dt.compare.core.ComparisonProcessHandle(requested.getMainDescriptor(),requested.getOtherDescriptor(),requested.getFullScope());
        assertEquals(requested,equal);assertNotSame(requested,equal);
        var foreign=stub(com._1c.g5.v8.dt.compare.core.IComparisonSession.class,(p,m,a)->m.getName().equals("getHandle")?equal:null);
        var binding=new EdtComparisonService.SessionBinding(requested,null);
        assertFalse(binding.matches(foreign));assertNull(binding.get());
    }
    @Test public void borrowedBindingRetainsTheExactNativeSession() {
        var handle=handle();
        var first=stub(com._1c.g5.v8.dt.compare.core.IComparisonSession.class,(p,m,a)->handle);
        var second=stub(com._1c.g5.v8.dt.compare.core.IComparisonSession.class,(p,m,a)->handle);
        var binding=new EdtComparisonService.SessionBinding(handle,first);
        assertTrue(binding.matches(first));assertFalse(binding.matches(second));
    }
    @Test public void orderAndNativeUserFlagsChangeTheSettingsFingerprint() {
        var settings=com._1c.g5.v8.dt.compare.model.CompareFactory.eINSTANCE.createMergeSettings();
        var order=com._1c.g5.v8.dt.compare.model.CompareFactory.eINSTANCE.createOrderRule();settings.setOrderRule(order);
        order.setOrderSide(com._1c.g5.v8.dt.compare.model.ComparisonSide.MAIN);
        String main=EdtComparisonService.settingsFingerprint(settings);
        order.setOrderSide(com._1c.g5.v8.dt.compare.model.ComparisonSide.OTHER);
        String other=EdtComparisonService.settingsFingerprint(settings);assertNotEquals(main,other);
        order.setOrderSetByUser(true);assertNotEquals(other,EdtComparisonService.settingsFingerprint(settings));
        String explicitOrder=EdtComparisonService.settingsFingerprint(settings);
        settings.setMergeRuleSetByUser(true);assertNotEquals(explicitOrder,EdtComparisonService.settingsFingerprint(settings));
    }
    @Test public void customTextAndExcludedNodeSettingsAreFingerprintInputs() {
        var settings=com._1c.g5.v8.dt.compare.model.CompareFactory.eINSTANCE.createMergeSettings();settings.setMustBeMerged(false);
        var text=com._1c.g5.v8.dt.compare.model.CompareFactory.eINSTANCE.createTextNodeCustomMergeSettings();settings.setCustomSettings(text);
        text.setCustomMergeResult("Return 1;");String before=EdtComparisonService.settingsFingerprint(settings);
        text.setCustomMergeResult("Return 2;");assertNotEquals(before,EdtComparisonService.settingsFingerprint(settings));
    }
    @Test public void handoffFindsAnExistingBorrowedEditorWithoutRestoringOtherParts() {
        var input=stub(com._1c.g5.v8.dt.compare.ui.editor.IDtComparisonEditorInput.class,(p,m,a)->null);
        var editor=stub(org.eclipse.ui.IEditorPart.class,(p,m,a)->m.getName().equals("getEditorInput")?input:null);
        var reference=stub(org.eclipse.ui.IEditorReference.class,(p,m,a)->{assertEquals("getEditor",m.getName());assertEquals(Boolean.FALSE,a[0]);return editor;});
        assertSame(editor,EdtComparisonService.findReusableEditor(java.util.List.of(reference),part->part==editor));
        assertNull(EdtComparisonService.findReusableEditor(java.util.List.of(reference),part->false));
    }
}
