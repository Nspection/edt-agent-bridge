// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.*;
import org.eclipse.core.runtime.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.action.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.*;
import org.eclipse.ui.part.MultiPageEditorPart;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.core.operations.model.*;
import com._1c.g5.v8.dt.moxel.*;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditor;
import com._1c.g5.v8.dt.ui.editor.input.DtEmbeddedEditorInput;
import com.codepilot1c.core.edt.compare.*;

/** Native Moxel controls over private local BM drafts; Save commits only the native plan. */
public final class SpreadsheetMergeEditor extends MultiPageEditorPart {
    private SpreadsheetMergeInput input;
    private final List<IEditingContext> contexts=new ArrayList<>();
    private MoxelEditor resultEditor;
    private IEditingContext resultContext;
    private boolean localDirty;
    @Override public void init(IEditorSite site,IEditorInput input)throws PartInitException {
        if(!(input instanceof SpreadsheetMergeInput i))throw new PartInitException("Expected spreadsheet comparison input");
        this.input=i;super.init(site,input);setPartName("Табличное объединение");
    }
    @Override protected void createPages() {
        try {
            addSheet(input.sides.get("MAIN"),"Текущая конфигурация",true,"MAIN");
            addSheet(input.initialResult,"Результат объединения",false,"MAIN");
            addSheet(input.sides.get("OTHER"),"Конфигурация-источник",true,"OTHER");
            if(input.sides.get("COMMON_ANCESTOR")!=null)
                addSheet(input.sides.get("COMMON_ANCESTOR"),"Общий предок",true,"COMMON_ANCESTOR");
            resultContext.addListener(new IEditingContextListener(){
                @Override public void contextChanged(){localDirty=true;SpreadsheetMergeBridge.dirty(input.session,input.nodeId,true);firePropertyChange(PROP_DIRTY);}
            });
            setActivePage(1);
            var display=getSite().getShell().getDisplay();display.timerExec(250,new Runnable(){public void run(){
                if(resultEditor==null || resultEditor.getMoxelControl().isDisposed())return;
                SpreadsheetMergeBridge.dirty(input.session,input.nodeId,isDirty());display.timerExec(250,this);
            }});
            getEditorSite().getActionBars().getToolBarManager().add(new Action("Сохранить в план"){@Override public void run(){doSave(new NullProgressMonitor());}});
            getEditorSite().getActionBars().updateActionBars();
        }catch(Exception e){throw new IllegalStateException("Cannot open native spreadsheet merge editor",e);}
    }
    private void addSheet(String xml,String title,boolean readOnly,String side)throws Exception {
        var project=input.session.getDataSource(ComparisonSide.valueOf(side)).getDtProject();
        var resource=SpreadsheetMergeCodec.load(xml,project,URI.createURI("memory:/snapshot.mxlx"));
        var doc=(SpreadsheetDocument)resource.getContents().get(0);
        var model=new com.codepilot1c.core.edt.metadata.EdtMetadataGateway().getBmModelManager().getModel(project);
        var type=Platform.getBundle("com._1c.g5.v8.dt.core").loadClass("com._1c.g5.v8.dt.internal.core.operations.model.BmEditingContext");
        var context=(IEditingContext)type.getConstructor(com._1c.g5.v8.bm.integration.IBmModel.class).newInstance(model);contexts.add(context);
        context.initialize("EDT Bridge spreadsheet snapshot "+java.util.UUID.randomUUID());
        String fqn="BridgeSpreadsheetSnapshot."+java.util.UUID.randomUUID().toString().replace("-","");
        var attached=context.execute(new com._1c.g5.v8.bm.integration.AbstractBmTask<SpreadsheetDocument>("Private spreadsheet draft"){
            @Override public SpreadsheetDocument execute(com._1c.g5.v8.bm.core.IBmTransaction tx,IProgressMonitor monitor){tx.attachTopObject((com._1c.g5.v8.bm.core.IBmObject)doc,fqn);return (SpreadsheetDocument)tx.getTopObjectByFqn(fqn);}
        },new NullProgressMonitor());
        org.eclipse.core.commands.operations.OperationHistoryFactory.getOperationHistory().dispose(context.getUndoContext(),true,true,false);
        var editor=new SnapshotMoxelEditor(()->doSave(new NullProgressMonitor()),()->!readOnly && localDirty);
        int page=addPage(editor,new DtEmbeddedEditorInput(attached,context));setPageText(page,title);
        context.execute(attached,(d,m)->{editor.getMoxelControl().setReadOnlyMode(readOnly);return null;},new NullProgressMonitor());
        if(!readOnly){resultEditor=editor;resultContext=context;}
    }
    @Override public boolean isDirty(){return resultContext!=null && (localDirty || resultEditor!=null && resultEditor.getMoxelControl().isInplaceEdit());}
    @Override public void doSave(IProgressMonitor monitor) {
        if(resultEditor==null)return;
        try {
            resultEditor.getMoxelControl().finishInplaceEdit(false);
            String xml=resultContext.execute(resultEditor.getDocument(),(doc,m)->SpreadsheetMergeCodec.saveDocument(doc,input.session.getDataSource(ComparisonSide.MAIN).getDtProject()),monitor);
            var saved=SpreadsheetMergeSupport.commit(input.session,input.nodeId,xml,input.expectedHash);
            input.expectedHash=(String)saved.get("node_sha256");
            localDirty=false;SpreadsheetMergeBridge.dirty(input.session,input.nodeId,false);firePropertyChange(PROP_DIRTY);
        }catch(Exception e){com.codepilot1c.core.internal.VibeCorePlugin.logError(e);MessageDialog.openError(getSite().getShell(),"Результат не сохранён",e.getMessage());}
    }
    @Override public boolean isSaveAsAllowed(){return false;}
    @Override public void doSaveAs(){}
    @Override public void dispose() {
        try{super.dispose();}finally{
            com.codepilot1c.core.edt.editor.EdtEditorService.getInstance().forget(this);
            SpreadsheetMergeBridge.dirty(input.session,input.nodeId,false);
            for(var c:contexts)if(!c.isDisposed())c.dispose();
        }
    }
}
