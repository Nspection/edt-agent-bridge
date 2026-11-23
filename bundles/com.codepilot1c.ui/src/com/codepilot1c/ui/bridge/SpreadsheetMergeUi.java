// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.*;
import java.util.List;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jface.viewers.*;
import org.eclipse.jface.layout.AbstractColumnLayout;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.layout.*;
import org.eclipse.jface.dialogs.Dialog;
import org.eclipse.ui.*;
import org.eclipse.jface.dialogs.MessageDialog;
import com._1c.g5.v8.dt.compare.ui.editor.DtComparisonView;
import com._1c.g5.v8.dt.compare.ui.partialmodel.node.IPartialModelNode;
import com.codepilot1c.core.edt.compare.*;

/** Attaches to ordinary native compare views without replacing editors or perspectives. */
public final class SpreadsheetMergeUi {
    private static final String KEY="edt.bridge.spreadsheet.column";
    private static final com.codepilot1c.core.edt.development.UiPollLifecycle polling=new com.codepilot1c.core.edt.development.UiPollLifecycle();
    private static final java.util.concurrent.atomic.AtomicBoolean installing=new java.util.concurrent.atomic.AtomicBoolean();
    private SpreadsheetMergeUi() {}
    public static void start() {
        var display=PlatformUI.getWorkbench().getDisplay();
        polling.start(display::timerExec,old->{BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->display.timerExec(-1,old));},()->{
            if(display.isDisposed() || !PlatformUI.isWorkbenchRunning())return;
            if(!SpreadsheetMergeBridge.installed() && installing.compareAndSet(false,true))CompletableFuture.runAsync(()->{try{SpreadsheetMergeBridge.install();}catch(IllegalStateException notReady){}finally{installing.set(false);}});
            for(var shell:display.getShells())scan(shell);
        });
    }
    public static void stop(){polling.stop();}
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
        var support=new HashMap<String,SpreadsheetMergeSupport.Access>();var pending=new HashSet<String>();
        var labels=new ColumnLabelProvider(){
            @Override public String getText(Object element){return "";}
            private String key(IPartialModelNode p){return System.identityHashCode(p.getComparisonSession())+":"+p.getNodeId();}
            @Override public org.eclipse.swt.graphics.Image getImage(Object element){
                if(tree.isDisposed() || !(element instanceof IPartialModelNode p) || p.getComparisonSession()==null || p.getNodeId()<0)return null;
                String k=key(p);var session=p.getComparisonSession();long nodeId=p.getNodeId();if(!SpreadsheetMergeSupport.probeReady(session,nodeId))return null;
                if(!support.containsKey(k) && pending.add(k))CompletableFuture.runAsync(()->{
                    var supported=SpreadsheetMergeSupport.Access.UNSUPPORTED;try{supported=SpreadsheetMergeSupport.access(session,nodeId);}catch(Exception stale){/* Label probing is best effort; a closed/replaced model must not retry forever. */}
                    var value=supported;var display=PlatformUI.getWorkbench().getDisplay();if(display.isDisposed())return;
                    display.asyncExec(()->{
                        if(tree.isDisposed())return;
                        pending.remove(k);
                        if(value==null || p.getComparisonSession()!=session || p.getNodeId()!=nodeId || !SpreadsheetMergeSupport.probeReady(session,nodeId))return;
                        support.put(k,value);viewer.update(element,null);
                    });
                });
                var mode=support.get(k);
                return mode==SpreadsheetMergeSupport.Access.MERGE || mode==SpreadsheetMergeSupport.Access.COMPARE ? icon : null;
            }
            @Override public String getToolTipText(Object element){
                if(!(element instanceof IPartialModelNode p))return null;
                var mode=support.get(key(p));
                if(mode==SpreadsheetMergeSupport.Access.COMPARE)return "Сравнить ячейки (только чтение)";
                return mode==SpreadsheetMergeSupport.Access.MERGE?"Открыть табличные варианты и редактируемый результат объединения":null;
            }
        };
        col.setLabelProvider(labels);
        // Native compare refreshes may replace cell presentation after editor activation.
        // Own only our column's foreground so the gear survives those refreshes.
        tree.addListener(SWT.EraseItem,event->{if(event.index==tree.indexOf(nativeColumn))event.detail&=~SWT.FOREGROUND;});
        tree.addListener(SWT.PaintItem,event->{
            if(event.index!=tree.indexOf(nativeColumn)||!(event.item instanceof TreeItem item))return;
            var image=labels.getImage(item.getData());if(image==null||image.isDisposed())return;
            var bounds=item.getBounds(event.index);var size=image.getBounds();event.gc.drawImage(image,bounds.x+Math.max(0,(bounds.width-size.width)/2),bounds.y+Math.max(0,(bounds.height-size.height)/2));
        });
        ColumnViewerToolTipSupport.enableFor(viewer);
        tree.addListener(SWT.MouseDown,event->{
            var item=tree.getItem(new org.eclipse.swt.graphics.Point(event.x,event.y));if(item==null || !(item.getData() instanceof IPartialModelNode p))return;
            if(!item.getBounds(tree.indexOf(nativeColumn)).contains(event.x,event.y))return;
            var mode=support.get(System.identityHashCode(p.getComparisonSession())+":"+p.getNodeId());
            if(mode!=SpreadsheetMergeSupport.Access.MERGE && mode!=SpreadsheetMergeSupport.Access.COMPARE)return;
            var session=p.getComparisonSession();long nodeId=p.getNodeId();var display=tree.getDisplay();
            CompletableFuture.runAsync(()->{
                try {
                    if(mode==SpreadsheetMergeSupport.Access.MERGE) {
                        SpreadsheetMergeSupport.open(session,nodeId);
                    } else {
                        var differences=SpreadsheetMergeSupport.compare(session,nodeId);
                        if(!display.isDisposed())display.asyncExec(()->{
                            if(!tree.isDisposed())showComparison(tree.getShell(),differences);
                        });
                    }
                } catch(Exception e) {
                    if(!display.isDisposed())display.asyncExec(()->{
                        if(!tree.isDisposed())MessageDialog.openError(tree.getShell(),"Табличное сравнение",e.getMessage());
                    });
                }
            });
        });
    }

    private static void showComparison(Shell parent,List<SpreadsheetSemanticMerge.Difference> differences) {
        new Dialog(parent) {
            @Override protected boolean isResizable(){return true;}

            @Override protected void configureShell(Shell shell) {
                super.configureShell(shell);
                shell.setText("Табличное сравнение");
            }

            @Override protected Control createDialogArea(Composite parent) {
                var area=(Composite)super.createDialogArea(parent);
                var status=new Label(area,SWT.NONE);
                status.setText("Только чтение. Различий: "+differences.size());
                var table=new Table(area,SWT.VIRTUAL|SWT.BORDER|SWT.FULL_SELECTION|SWT.V_SCROLL|SWT.H_SCROLL);
                table.setHeaderVisible(true);
                table.setLinesVisible(true);
                var layout=new GridData(SWT.FILL,SWT.FILL,true,true);
                layout.widthHint=780;
                layout.heightHint=360;
                table.setLayoutData(layout);
                for(String title:List.of("Ячейка","Свойство","Текущий вариант","Сравниваемый вариант")){
                    var column=new TableColumn(table,SWT.NONE);
                    column.setText(title);
                    column.setWidth(title.equals("Ячейка")?85:title.equals("Свойство")?195:245);
                }
                table.addListener(SWT.SetData,event->{
                    var item=(TableItem)event.item;
                    var difference=differences.get(event.index);
                    String cell=difference.row()>0?"R"+difference.row()+
                            (difference.col()>0?"C"+difference.col():""):"";
                    item.setText(new String[]{cell,SpreadsheetMergeEditor.label(difference),
                            difference.main(),difference.other()});
                });
                table.setItemCount(differences.size());
                return area;
            }

            @Override protected void createButtonsForButtonBar(Composite parent) {
                createButton(parent,org.eclipse.jface.dialogs.IDialogConstants.OK_ID,"Закрыть",true);
            }
        }.open();
    }
}
