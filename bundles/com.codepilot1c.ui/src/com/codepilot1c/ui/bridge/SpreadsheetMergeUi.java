// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.layout.AbstractColumnLayout;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.*;
import org.eclipse.jface.dialogs.MessageDialog;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com.codepilot1c.core.edt.compare.*;

/** Attaches to ordinary native compare views without replacing editors or perspectives. */
public final class SpreadsheetMergeUi {
    private static final String KEY="edt.bridge.spreadsheet.column";
    private static boolean started;
    private static final java.util.concurrent.atomic.AtomicBoolean installing=new java.util.concurrent.atomic.AtomicBoolean();
    private SpreadsheetMergeUi() {}
    public static void start() {
        if(started)return;started=true;
        var display=PlatformUI.getWorkbench().getDisplay();
        Runnable poll=new Runnable(){@Override public void run(){
            if(!started || display.isDisposed() || !PlatformUI.isWorkbenchRunning())return;
            if(!SpreadsheetMergeBridge.installed() && installing.compareAndSet(false,true))CompletableFuture.runAsync(()->{try{SpreadsheetMergeBridge.install();}catch(IllegalStateException notReady){}finally{installing.set(false);}});
            for(var shell:display.getShells())scan(shell);
            display.timerExec(1800,this);
        }};display.timerExec(500,poll);
    }
    public static void stop(){started=false;}
    private static void scan(Control control) {
        if(control.isDisposed())return;
        if(control instanceof DtComparisonView view){attach(view);return;}
        if(control instanceof Composite c)for(Control child:c.getChildren())scan(child);
    }
    private static void attach(DtComparisonView view) {
        var viewer=view.getTreeControl().getTreeViewer();var tree=viewer.getTree();
        if(tree.getData(KEY)!=null)return;
        var col=new TreeViewerColumn(viewer,SWT.NONE);var nativeColumn=col.getColumn();nativeColumn.setText("Табл.");nativeColumn.setToolTipText("Табличное сравнение и ручной результат");nativeColumn.setWidth(44);nativeColumn.setData(KEY,Boolean.TRUE);
        if(tree.getParent().getLayout() instanceof AbstractColumnLayout l)l.setColumnData(nativeColumn,new ColumnPixelData(44,false));
        int own=tree.indexOf(nativeColumn);int after=-1;
        for(int i=1;i<own;i++)if(tree.getColumn(i).getText().isBlank())after=i;
        if(after>=0){var order=new ArrayList<Integer>();for(int i:tree.getColumnOrder())if(i!=own){order.add(i);if(i==after)order.add(own);}tree.setColumnOrder(order.stream().mapToInt(Integer::intValue).toArray());}
        tree.setData(KEY,Boolean.TRUE);
        var icon=new org.eclipse.swt.graphics.Image(tree.getDisplay(),16,16);var gc=new org.eclipse.swt.graphics.GC(icon);
        try{
            gc.setBackground(tree.getDisplay().getSystemColor(SWT.COLOR_WIDGET_BACKGROUND));gc.fillRectangle(0,0,16,16);
            gc.setForeground(tree.getDisplay().getSystemColor(SWT.COLOR_WIDGET_FOREGROUND));gc.setLineWidth(2);
            for(int angle=0;angle<360;angle+=45){double t=Math.toRadians(angle);gc.drawLine(8+(int)(4*Math.cos(t)),8+(int)(4*Math.sin(t)),8+(int)(7*Math.cos(t)),8+(int)(7*Math.sin(t)));}
            gc.drawOval(3,3,10,10);gc.drawOval(6,6,4,4);
        }finally{gc.dispose();}tree.addDisposeListener(event->icon.dispose());
        // A visible gear for native solid spreadsheet resources; no source reads in labels.
        var support=new HashMap<String,Boolean>();var pending=new HashSet<String>();
        col.setLabelProvider(new ColumnLabelProvider(){
            @Override public String getText(Object element){return "";}
            private String key(IPartialModelNode p){return System.identityHashCode(p.getComparisonSession())+":"+p.getNodeId();}
            @Override public org.eclipse.swt.graphics.Image getImage(Object element){
                if(!(element instanceof IPartialModelNode p))return null;
                String k=key(p);var session=p.getComparisonSession();long nodeId=p.getNodeId();if(!support.containsKey(k) && pending.add(k))CompletableFuture.runAsync(()->{
                    Boolean supported=null;try{supported=SpreadsheetMergeSupport.supports(session,nodeId);}catch(Exception error){com.codepilot1c.ui.internal.VibeUiPlugin.log(error);}
                    Boolean value=supported;var display=PlatformUI.getWorkbench().getDisplay();if(display.isDisposed())return;
                    display.asyncExec(()->{
                        if(tree.isDisposed())return;
                        if(value==null){display.timerExec(1800,()->{pending.remove(k);if(!tree.isDisposed())viewer.update(element,null);});return;}
                        pending.remove(k);support.put(k,value);viewer.update(element,null);
                    });
                });
                return Boolean.TRUE.equals(support.get(k))?icon:null;
            }
            @Override public String getToolTipText(Object element){return element instanceof IPartialModelNode p && Boolean.TRUE.equals(support.get(key(p)))?"Открыть табличные варианты и редактируемый результат объединения":null;}
        });
        ColumnViewerToolTipSupport.enableFor(viewer);
        tree.addListener(SWT.MouseDown,event->{
            var item=tree.getItem(new org.eclipse.swt.graphics.Point(event.x,event.y));if(item==null || !(item.getData() instanceof IPartialModelNode p))return;
            if(!item.getBounds(tree.indexOf(nativeColumn)).contains(event.x,event.y))return;
            if(!Boolean.TRUE.equals(support.get(System.identityHashCode(p.getComparisonSession())+":"+p.getNodeId())))return;
            var session=p.getComparisonSession();long nodeId=p.getNodeId();
            CompletableFuture.runAsync(()->{try{SpreadsheetMergeSupport.open(session,nodeId);}catch(Exception e){tree.getDisplay().asyncExec(()->{if(!tree.isDisposed())MessageDialog.openError(tree.getShell(),"Табличное объединение",e.getMessage());});}});
        });
    }
}
