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
    private UndoActionHandler undo;
    private RedoActionHandler redo;
    public SnapshotMoxelEditor(Runnable save,BooleanSupplier dirty){this.save=save;this.dirty=dirty;}
    @Override public void init(IEditorSite site,IEditorInput input)throws PartInitException {
        var embedded=(IDtEmbeddedEditorInput)input;setSite(site);setInput(input);
        document=(SpreadsheetDocument)embedded.getEmbeddedObject();editingContext=embedded.getEditingContext();
        presentationService=EdtComparisonService.nativeService("com._1c.g5.v8.dt.moxel.ui","com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditorPlugin",IPresentationService.class);
    }
    @Override public void createPartControl(Composite parent) {
        viewer=new MoxelViewer(parent,SWT.BORDER|SWT.V_SCROLL|SWT.H_SCROLL,presentationService);
        var c=viewer.getMoxelControl();c.setEditingContext(editingContext);c.setDocument(document);
        getSite().setSelectionProvider(viewer);createContextMenu();
        undo=new UndoActionHandler(getSite(),editingContext.getUndoContext());redo=new RedoActionHandler(getSite(),editingContext.getUndoContext());
        getEditorSite().getActionBars().setGlobalActionHandler(ActionFactory.UNDO.getId(),undo);
        getEditorSite().getActionBars().setGlobalActionHandler(ActionFactory.REDO.getId(),redo);
        c.addDisposeListener(e->{undo.dispose();redo.dispose();});
    }
    @Override public IAction getAction(String id){if("Undo".equalsIgnoreCase(id))return undo;if("Redo".equalsIgnoreCase(id))return redo;return super.getAction(id);}
    @Override public void setFocus(){if(viewer!=null && !viewer.getControl().isDisposed())viewer.getControl().setFocus();}
    @Override public boolean isDirty(){return dirty.getAsBoolean();}
    @Override public void doSave(IProgressMonitor monitor){save.run();}
}
