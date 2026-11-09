// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;
import java.util.*;
import java.util.function.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.core.runtime.IProgressMonitor;
import org.eclipse.jface.action.IAction;
import org.eclipse.ui.*;
import org.eclipse.ui.operations.*;
import org.eclipse.ui.actions.ActionFactory;
import com._1c.g5.v8.dt.moxel.*;
import com._1c.g5.v8.dt.moxel.ui.editor.*;
import com._1c.g5.v8.dt.core.operations.model.IEditingContext;
import com._1c.g5.v8.dt.md.ui.presentation.IPresentationService;
import com._1c.g5.v8.dt.ui.editor.input.IDtEmbeddedEditorInput;
import com.codepilot1c.core.edt.compare.EdtComparisonService;
/** Native Moxel controls/operations over a private local BM draft, never source-editor autosave. */
public final class SnapshotMoxelEditor extends MoxelEditor {
    private final Runnable save;
    private final BooleanSupplier dirty;
    private Runnable editingStateChanged=()->{};
    public void setEditingStateListener(Runnable listener){editingStateChanged=Objects.requireNonNull(listener);}
    public interface OverlayPainter {void paint(ViewPort viewport,org.eclipse.swt.graphics.GC gc,org.eclipse.swt.graphics.Rectangle area);}
    private OverlayPainter overlay;
    public void setOverlay(OverlayPainter painter){overlay=painter;}
    private UndoActionHandler undo;
    private RedoActionHandler redo;
    public SnapshotMoxelEditor(Runnable save,BooleanSupplier dirty){this.save=save;this.dirty=dirty;}
    @Override public void init(IEditorSite site,IEditorInput input)throws PartInitException {
        var embedded=(IDtEmbeddedEditorInput)input;setSite(site);setInput(input);
        document=(SpreadsheetDocument)embedded.getEmbeddedObject();editingContext=embedded.getEditingContext();
        presentationService=EdtComparisonService.nativeService("com._1c.g5.v8.dt.moxel.ui","com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditorPlugin",IPresentationService.class);
    }
    @Override public void createPartControl(Composite parent) {
        viewer=new OverlayViewer(parent,SWT.NO_BACKGROUND|SWT.BORDER|SWT.V_SCROLL|SWT.H_SCROLL,presentationService,document,editingContext,(viewport,gc,area)->{if(overlay!=null)overlay.paint(viewport,gc,area);},this::refreshInplaceCommands);
        var c=viewer.getMoxelControl();c.setEditingContext(editingContext);c.setDocument(document);
        c.addSelectionFormatListener(this::refreshInplaceCommands);
        getSite().setSelectionProvider(viewer);createContextMenu();
        undo=new UndoActionHandler(getSite(),editingContext.getUndoContext());redo=new RedoActionHandler(getSite(),editingContext.getUndoContext());
        getEditorSite().getActionBars().setGlobalActionHandler(ActionFactory.UNDO.getId(),undo);
        getEditorSite().getActionBars().setGlobalActionHandler(ActionFactory.REDO.getId(),redo);
        c.addDisposeListener(e->{undo.dispose();redo.dispose();});
    }
    private void refreshInplaceCommands() {
        var evaluation=getSite().getService(org.eclipse.ui.services.IEvaluationService.class);
        if(evaluation!=null)evaluation.requestEvaluation("com._1c.g5.v8.dt.moxel.ui.handlers.isInplaceEdit");
        editingStateChanged.run();
    }
    public boolean hasUnsafeClipboardSelection() {
        var control=getMoxelControl();var cells=control.getCellsSelection();var drawings=control.getSelectedDrawgins();
        return editingContext.executeReadonlyTask(new com._1c.g5.v8.bm.integration.AbstractBmTask<Boolean>("Validate clipboard text"){
            @Override public Boolean execute(com._1c.g5.v8.bm.core.IBmTransaction tx,IProgressMonitor monitor) {
                if(control.isDrawingsSelection())return drawings.stream().anyMatch(com.codepilot1c.core.edt.compare.SpreadsheetClipboardSafety::hasNeutralText);
                for(var selection:cells){var copy=com._1c.g5.v8.dt.moxel.util.ClipboardCopyUtil.copySheet(control.getSheet(),selection,false);
                    if(com.codepilot1c.core.edt.compare.SpreadsheetClipboardSafety.hasNeutralText(copy))return true;}
                return false;
            }
        },new org.eclipse.core.runtime.NullProgressMonitor());
    }
    @Override public IAction getAction(String id){if("Undo".equalsIgnoreCase(id))return undo;if("Redo".equalsIgnoreCase(id))return redo;return super.getAction(id);}
    @Override public void setFocus(){if(viewer!=null && !viewer.getControl().isDisposed())viewer.getControl().setFocus();}
    @Override public boolean isDirty(){return dirty.getAsBoolean();}
    @Override public void doSave(IProgressMonitor monitor){save.run();}
    /** Native viewport callback paints into EDT's live offscreen buffer before it is disposed. */
    private static final class OverlayViewer extends MoxelViewer {
        private MoxelControl actual;
        OverlayViewer(Composite parent,int style,IPresentationService service,SpreadsheetDocument document,IEditingContext context,OverlayPainter painter,Runnable editingStateChanged){
            super(parent,style,service);super.getMoxelControl().setEditingContext(context);super.getMoxelControl().setDocument(document);super.getMoxelControl().dispose();
            actual=new MoxelControl(parent,style,service){
                @Override public void finishInplaceEdit(boolean cancel){super.finishInplaceEdit(cancel);if(!isDisposed())editingStateChanged.run();}
                @Override public void paintViewPort(ViewPort viewport,org.eclipse.swt.graphics.GC gc,org.eclipse.swt.graphics.Rectangle area,boolean buffered){super.paintViewPort(viewport,gc,area,buffered);painter.paint(viewport,gc,area);}};
            actual.addSelectionChangedListener(this);
        }
        @Override public org.eclipse.swt.widgets.Control getControl(){return actual==null?super.getControl():actual;}
        @Override public MoxelControl getMoxelControl(){return actual==null?super.getMoxelControl():actual;}
        @Override public org.eclipse.jface.viewers.ISelection getSelection(){var selection=getMoxelControl().getSelection();return selection==null?org.eclipse.jface.viewers.StructuredSelection.EMPTY:selection;}
        @Override public void setSelection(org.eclipse.jface.viewers.ISelection selection,boolean reveal){getMoxelControl().setSelection(selection);if(reveal)getMoxelControl().ensureSelectionVisible();}
    }
}
