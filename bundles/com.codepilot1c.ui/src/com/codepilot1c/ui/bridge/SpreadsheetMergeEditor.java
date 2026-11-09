// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.*;
import java.util.List;
import org.eclipse.core.runtime.*;
import org.eclipse.emf.common.util.URI;
import org.eclipse.jface.action.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.ui.*;
import org.eclipse.ui.part.MultiPageEditorPart;
import org.eclipse.swt.SWT;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.custom.SashForm;
import com._1c.g5.v8.dt.compare.model.ComparisonSide;
import com._1c.g5.v8.dt.core.operations.model.*;
import com._1c.g5.v8.dt.moxel.*;
import com._1c.g5.v8.dt.moxel.ui.editor.MoxelEditor;
import com._1c.g5.v8.dt.ui.editor.input.DtEmbeddedEditorInput;
import com.codepilot1c.core.edt.compare.*;

/** Native Moxel controls over private local BM drafts; Save commits only the native plan. */
public final class SpreadsheetMergeEditor extends MultiPageEditorPart {
    private org.eclipse.ui.handlers.IHandlerService clipboardHandlers;
    private final List<org.eclipse.ui.handlers.IHandlerActivation> clipboardActivations=new ArrayList<>();
    private final List<org.eclipse.core.commands.IHandler> clipboardGuards=new ArrayList<>();
    private void bindClipboardGuards() {
        if(clipboardHandlers==null)return;
        clipboardActivations.forEach(clipboardHandlers::deactivateHandler);clipboardActivations.clear();
        clipboardGuards.forEach(org.eclipse.core.commands.IHandler::dispose);clipboardGuards.clear();
        var child=getAdapter(MoxelEditor.class);if(!(child instanceof SnapshotMoxelEditor snapshot)||child.getMoxelControl()==null)return;
        snapshot.setEditingStateListener(this::bindClipboardGuards);
        if(child.getMoxelControl().isInplaceEdit())return;
        for(String name:List.of("copy","cut")) {
            var expression=new org.eclipse.core.expressions.Expression(){
                @Override public org.eclipse.core.expressions.EvaluationResult evaluate(org.eclipse.core.expressions.IEvaluationContext context){
                    var control=child.getMoxelControl();return org.eclipse.core.expressions.EvaluationResult.valueOf(!control.isDisposed()&&!control.isInplaceEdit()&&(name.equals("copy")||!control.isReadOnlyMode()));
                }
                @Override public void collectExpressionInfo(org.eclipse.core.expressions.ExpressionInfo info){info.addVariableNameAccess("selection");info.addVariableNameAccess("activeEditor");info.addAccessedPropertyName("com._1c.g5.v8.dt.moxel.ui.handlers.isInplaceEdit");info.addAccessedPropertyName("com._1c.g5.v8.dt.moxel.ui.handlers.isReadOnlyMode");}
            };
            var handler=new org.eclipse.core.commands.AbstractHandler(){
                @Override public Object execute(org.eclipse.core.commands.ExecutionEvent event)throws org.eclipse.core.commands.ExecutionException {
                    try {
                        if(snapshot.hasUnsafeClipboardSelection())throw new org.eclipse.core.commands.ExecutionException("Копирование отменено: выделение содержит нейтральный текст (#), который штатный буфер EDT теряет. Исходник и буфер не изменены. Используйте выбор стороны или ручную правку результата.");
                        String type="com._1c.g5.v8.dt.moxel.ui.handlers."+(name.equals("copy")?"CopyCommandHandler":"CutCommandHandler");
                        var nativeHandler=(org.eclipse.core.commands.IHandler)Platform.getBundle("com._1c.g5.v8.dt.moxel.ui").loadClass(type).getConstructor().newInstance();
                        try{return nativeHandler.execute(event);}finally{nativeHandler.dispose();}
                    }catch(org.eclipse.core.commands.ExecutionException e){throw e;}catch(Exception e){throw new org.eclipse.core.commands.ExecutionException("Не удалось безопасно проверить буфер обмена",e);}
                }
            };
            clipboardGuards.add(handler);clipboardActivations.add(clipboardHandlers.activateHandler("org.eclipse.ui.edit."+name,handler,expression));
        }
    }
    private SpreadsheetMergeInput input;
    private org.eclipse.ui.contexts.IContextService moxelContexts;
    private org.eclipse.ui.contexts.IContextActivation moxelContext;
    @Override public <T> T getAdapter(Class<T> adapter) {
        if(adapter==MoxelEditor.class) {
            int page=getActivePage();
            if(page>=0 && getEditor(page) instanceof MoxelEditor editor)return adapter.cast(editor);
        }
        return super.getAdapter(adapter);
    }
    @Override protected void pageChange(int page) {
        super.pageChange(page);
        bindClipboardGuards();
        var child=getAdapter(MoxelEditor.class);
        if(child!=null && child.getMoxelControl()!=null) {
            var bars=getEditorSite().getActionBars();
            boolean writable=!child.getMoxelControl().isReadOnlyMode();
            for(var factory:List.of(org.eclipse.ui.actions.ActionFactory.UNDO,org.eclipse.ui.actions.ActionFactory.REDO)) {
                IAction action=writable?child.getAction(factory.getId()):new Action(){};
                if(!writable)action.setEnabled(false);
                bars.setGlobalActionHandler(factory.getId(),action);
            }
            bars.updateActionBars();
        }
        // The outer editor stays active when switching snapshots; re-evaluate the
        // native read-only/in-place predicates against its newly adapted child.
        var evaluation=getSite().getService(org.eclipse.ui.services.IEvaluationService.class);
        if(evaluation!=null)for(String property:List.of("isReadOnlyMode","isInplaceEdit","isAcceptableClipboard","isSingleSelection"))
            evaluation.requestEvaluation("com._1c.g5.v8.dt.moxel.ui.handlers."+property);
    }
    private final List<IEditingContext> contexts=new ArrayList<>();
    private MoxelEditor resultEditor;
    private IEditingContext resultContext;
    private boolean localDirty,readingResult,modelCheckPending,lastSaveSucceeded;
    private String resultFqn,savedResultXml,lastPreviewXml,lastPreviewRevision;
    private int differenceRefreshes;
    private final Map<String,org.eclipse.swt.graphics.Color> diffColors=new HashMap<>();
    private Composite snapshotControl;
    private int overlayPaints,overlayMarks,diffOffset;
    private Table diffTable;
    private Label diffStatus;
    private boolean highlighting=true,refreshPending;
    private Action highlightingAction;
    private ToolBarManager reviewToolbar,documentToolbar;
    private final List<Action> reviewActions=new ArrayList<>();
    private final List<Action> selectionActions=new ArrayList<>();
    private List<SpreadsheetSemanticMerge.Difference> differences=List.of();
    @Override public void createPartControl(Composite parent){
        var display=parent.getDisplay();diffColors.put("MAIN",new org.eclipse.swt.graphics.Color(display,170,210,255));diffColors.put("OTHER",new org.eclipse.swt.graphics.Color(display,175,230,180));diffColors.put("CONFLICT",new org.eclipse.swt.graphics.Color(display,255,180,100));diffColors.put("MANUAL",new org.eclipse.swt.graphics.Color(display,255,240,160));diffColors.put("SHARED",new org.eclipse.swt.graphics.Color(display,220,230,230));
        var root=new Composite(parent,SWT.NONE);snapshotControl=root;var rootLayout=new GridLayout(1,false);rootLayout.marginWidth=0;rootLayout.marginHeight=0;root.setLayout(rootLayout);
        var documentHeader=new Composite(root,SWT.NONE);documentHeader.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));var documentLayout=new GridLayout(2,false);documentLayout.marginWidth=4;documentLayout.marginHeight=0;documentHeader.setLayout(documentLayout);var documentTitle=new Label(documentHeader,SWT.NONE);documentTitle.setText("Табличное объединение");documentTitle.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
        documentToolbar=new ToolBarManager(SWT.FLAT|SWT.RIGHT);documentToolbar.createControl(documentHeader);documentToolbar.getControl().setLayoutData(new GridData(SWT.END,SWT.CENTER,false,false));documentHeader.addDisposeListener(e->documentToolbar.dispose());
        var sash=new SashForm(root,SWT.VERTICAL);sash.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));sash.addDisposeListener(e->diffColors.values().forEach(org.eclipse.swt.graphics.Color::dispose));var editorArea=new Composite(sash,SWT.NONE);editorArea.setLayout(new FillLayout());
        var panel=new Composite(sash,SWT.NONE);panel.setLayout(new GridLayout(1,false));
        var header=new Composite(panel,SWT.NONE);header.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));var headerLayout=new GridLayout(2,false);headerLayout.marginWidth=0;headerLayout.marginHeight=0;header.setLayout(headerLayout);
        reviewToolbar=new ToolBarManager(SWT.FLAT);reviewToolbar.createControl(header);reviewToolbar.getControl().setLayoutData(new GridData(SWT.BEGINNING,SWT.CENTER,false,false));header.addDisposeListener(e->reviewToolbar.dispose());
        diffStatus=new Label(header,SWT.NONE);diffStatus.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
        diffTable=new Table(panel,SWT.BORDER|SWT.FULL_SELECTION|SWT.V_SCROLL|SWT.H_SCROLL);diffTable.setHeaderVisible(true);diffTable.setLinesVisible(true);diffTable.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
        for(String title:List.of("Изменение","Общий предок","Текущая конфигурация","Источник","Результат")){var column=new TableColumn(diffTable,SWT.NONE);column.setText(title);column.setWidth(title.equals("Изменение")?260:180);}
        diffTable.addListener(SWT.Selection,e->revealSelected());
        diffTable.addListener(SWT.MouseHover,e->{var item=diffTable.getItem(new org.eclipse.swt.graphics.Point(e.x,e.y));if(item!=null&&item.getData() instanceof SpreadsheetSemanticMerge.Difference d)diffTable.setToolTipText(label(d)+"\nБыло: "+d.ancestor()+"\nТекущее: "+d.main()+"\nИсточник: "+d.other()+"\nРезультат: "+d.result());else diffTable.setToolTipText(null);});
        clipboardHandlers=getSite().getService(org.eclipse.ui.handlers.IHandlerService.class);
        moxelContexts=getSite().getService(org.eclipse.ui.contexts.IContextService.class);
        if(moxelContexts!=null)moxelContext=moxelContexts.activateContext("com._1c.g5.v8.dt.moxel.ui.context");
        super.createPartControl(editorArea);sash.setWeights(75,25);
    }
    private void revealSelected(){
        updateSelectionActions();if(diffTable.getSelectionCount()==0||resultEditor==null)return;
        var d=(SpreadsheetSemanticMerge.Difference)diffTable.getSelection()[0].getData();setActivePage(1);
        if(d.row()>0){var c=resultEditor.getMoxelControl();int col=Math.max(1,d.col());c.setSelectionToCellSelection(d.row()-1,col-1);c.ensureCellVisible(d.row()-1,col-1);c.setFocus();}
        diffStatus.setText(label(d)+" — "+origin(d.origin())+ (d.conflict()&&!d.resolved()?"; конфликт не разрешён":""));
    }
    private void navigate(int direction){if(differences.isEmpty())return;int selected=diffTable.getSelectionIndex();int current=selected<0?(direction<0?0:-1):diffOffset+selected;int i=Math.floorMod(current+direction,differences.size());revealDifference(differences.get(i).id());}
    private void scheduleRefresh(){if(refreshPending||diffTable==null||diffTable.isDisposed())return;refreshPending=true;diffTable.getDisplay().timerExec(500,()->{refreshPending=false;if(!diffTable.isDisposed())refreshDifferences();});}
    private String serializeResult(){
        return resultContext.executeReadonlyTask(new com._1c.g5.v8.bm.integration.AbstractBmTask<String>("Read spreadsheet draft"){
            @Override public String execute(com._1c.g5.v8.bm.core.IBmTransaction tx,IProgressMonitor monitor){return SpreadsheetMergeCodec.saveDocument((SpreadsheetDocument)tx.getTopObjectByFqn(resultFqn),input.session.getDataSource(ComparisonSide.MAIN).getDtProject());}
        },new NullProgressMonitor());
    }
    private void refreshDifferences(){
        if(resultEditor==null||resultEditor.getMoxelControl().isDisposed()||resultEditor.getMoxelControl().isInplaceEdit())return;
        try{
            String xml;readingResult=true;try{xml=serializeResult();}finally{readingResult=false;}
            if(savedResultXml==null)savedResultXml=xml;
            boolean wasDirty=localDirty;localDirty=!xml.equals(savedResultXml);modelCheckPending=false;SpreadsheetMergeBridge.dirty(input.session,input.nodeId,isDirty());if(wasDirty!=localDirty)firePropertyChange(PROP_DIRTY);
            String revision=SpreadsheetMergeSemantics.revision(input.session,input.nodeId);if(xml.equals(lastPreviewXml)&&Objects.equals(revision,lastPreviewRevision))return;
            lastPreviewXml=xml;lastPreviewRevision=revision;differenceRefreshes++;
            var analysis=SpreadsheetMergeSemantics.preview(input.session,input.nodeId,xml);differences=analysis.differences();populateDifferences(Math.min(diffOffset,Math.max(0,differences.size()-1)));
            diffStatus.setText("Различий: "+differences.size()+"; нерешённых конфликтов: "+analysis.unresolved()+". Синий — текущая, зелёный — источник, оранжевый — конфликт, жёлтый — ручная правка.");resultEditor.getMoxelControl().redraw();resultEditor.getMoxelControl().update();
        }catch(Exception e){diffStatus.setText("Не удалось пересчитать различия: "+e.getMessage());com.codepilot1c.core.internal.VibeCorePlugin.logError(e);}
    }
    private void populateDifferences(int offset){String selected=diffTable.getSelectionCount()==0?null:((SpreadsheetSemanticMerge.Difference)diffTable.getSelection()[0].getData()).id();diffOffset=offset;diffTable.removeAll();for(var d:differences.stream().skip(offset).limit(500).toList()){var row=new TableItem(diffTable,SWT.NONE);row.setData(d);row.setText(new String[]{(d.row()>0?"R"+d.row()+(d.col()>0?"C"+d.col():"")+" ":"")+label(d),d.ancestor(),d.main(),d.other(),d.result()});row.setBackground(color(d));if(d.id().equals(selected))diffTable.setSelection(row);}updateSelectionActions();}
    private void updateSelectionActions(){boolean selected=diffTable!=null&&!diffTable.isDisposed()&&diffTable.getSelectionCount()>0;selectionActions.forEach(a->a.setEnabled(selected));}
    private String origin(String value){return switch(value){case "MAIN"->"текущая конфигурация";case "OTHER"->"источник";case "CONFLICT","STRUCTURE"->"конфликт";case "MANUAL"->"ручная правка";default->"общее изменение";};}
    private String label(SpreadsheetSemanticMerge.Difference d){
        String path=d.path();if(path.equals("/structure"))return "Структура макета";
        if(path.contains("/text/content/"))return "Текст ("+path.substring(path.lastIndexOf(':')+1)+")";
        if(path.contains("/formatIndex")){
            String property=path.substring(path.lastIndexOf('/')+1);
            return "Оформление: "+switch(property){case "bold"->"жирный шрифт";case "font"->"шрифт";case "size"->"размер шрифта";case "height"->"высота";case "width"->"ширина";case "backColor"->"фон";case "textColor"->"цвет текста";default->property;};
        }
        if(path.equals("/height"))return "Высота макета";
        if(path.contains("/allColumns/"))return "Полоса колонок";
        if(path.contains("/namedItems/"))return "Именованная область";
        if(path.contains("/rows/"))return d.col()==0?"Строка":"Ячейка";
        return switch(path){case "/merges"->"Объединённые ячейки";case "/drawings"->"Рисунки";case "/columns/size"->"Количество колонок";case "/printSettings"->"Параметры печати";default->path.substring(path.lastIndexOf('/')+1);};
    }
    private org.eclipse.swt.graphics.Color color(SpreadsheetSemanticMerge.Difference d){return diffColors.getOrDefault(d.origin().equals("STRUCTURE")?(d.resolved()?"MANUAL":"CONFLICT"):d.origin(),diffColors.get("SHARED"));}
    private void semanticAction(String action,String resolution){
        if(isDirty()){doSave(new NullProgressMonitor());if(isDirty())return;}
        try{
            var args=new HashMap<String,Object>();args.put("action",action);args.put("expected_node_sha256",input.expectedHash);
            if(resolution!=null){if(diffTable.getSelectionCount()==0)return;var d=(SpreadsheetSemanticMerge.Difference)diffTable.getSelection()[0].getData();args.put("resolutions",Map.of(d.id(),resolution));}
            SpreadsheetMergeSemantics.execute(input.session,input.nodeId,args);
            getSite().getShell().getDisplay().asyncExec(()->{try{SpreadsheetMergeSupport.open(input.session,input.nodeId);}catch(Exception e){com.codepilot1c.core.internal.VibeCorePlugin.logError(e);}});
        }catch(Exception e){MessageDialog.openError(getSite().getShell(),"Табличное объединение",e.getMessage());}
    }
    private void resetResult(){
        if(!MessageDialog.openQuestion(getSite().getShell(),"Сбросить результат?","Ручные правки макета и решения конфликтов будут удалены. Результат вернётся к текущей конфигурации. Исходники проекта не изменятся."))return;
        var display=getSite().getShell().getDisplay();var session=input.session;long nodeId=input.nodeId;String expected=input.expectedHash;
        display.asyncExec(()->{try{SpreadsheetMergeSemantics.reset(session,nodeId,expected,true);}catch(Exception e){com.codepilot1c.core.internal.VibeCorePlugin.logError(e);MessageDialog.openError(display.getActiveShell(),"Результат не сброшен",e.getMessage());}});
    }
    private void saveAndReturn(){
        doSave(new NullProgressMonitor());if(!lastSaveSucceeded||isDirty())return;
        try{EdtComparisonService.getInstance().showComparison(input.session,input.nodeId);getSite().getPage().closeEditor(this,false);}
        catch(Exception error){com.codepilot1c.core.internal.VibeCorePlugin.logError(error);MessageDialog.openError(getSite().getShell(),"Переход к сравнению",error.getMessage());}
    }
    private void closeReview(){
        if(isDirty()){
            int choice=new MessageDialog(getSite().getShell(),"Закрыть табличный редактор?",null,"В результате есть несохранённые правки. Само сравнение и сохранённый план останутся открытыми.",MessageDialog.QUESTION,new String[]{"Сохранить и закрыть","Закрыть без сохранения","Отмена"},2).open();
            if(choice<0||choice==2)return;if(choice==0){doSave(new NullProgressMonitor());if(isDirty())return;}
        }else if(!MessageDialog.openQuestion(getSite().getShell(),"Закрыть табличный редактор?","Закрыть редактор табличного документа? Сравнение и сохранённый план останутся открытыми. Вернуться можно через шестерёнку в колонке «Табл.»."))return;
        getSite().getPage().closeEditor(this,false);
    }
    private void installOverlay(){
        var c=resultEditor.getMoxelControl();((SnapshotMoxelEditor)resultEditor).setOverlay((viewport,gc,area)->{
            if(viewport.getDevicePosition().isEmpty())return;overlayPaints++;overlayMarks=0;if(!highlighting)return;
            int alpha=gc.getAlpha();var clip=gc.getClipping();var background=gc.getBackground();try{
                gc.setClipping(viewport.getDevicePosition().intersection(area));gc.setAlpha(100);var cells=new LinkedHashMap<String,SpreadsheetSemanticMerge.Difference>();
                for(var d:differences)if(d.row()>0){String key=d.row()+":"+Math.max(1,d.col());var previous=cells.get(key);if(previous==null||priority(d)>priority(previous))cells.put(key,d);}
                for(var d:cells.values())if(viewport.isCellInViewPort(d.row()-1,Math.max(1,d.col())-1)){
                    var rect=viewport.getCellPositionPixel(d.row()-1,Math.max(1,d.col())-1).intersection(viewport.getDevicePosition());gc.setBackground(color(d));gc.fillRectangle(rect);overlayMarks++;
                }
            }finally{gc.setAlpha(alpha);gc.setClipping(clip);gc.setBackground(background);}
        });
    }
    private int priority(SpreadsheetSemanticMerge.Difference d){return switch(d.origin()){case "CONFLICT","STRUCTURE"->5;case "MANUAL"->4;case "OTHER"->3;case "MAIN"->2;default->1;};}
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
                @Override public void contextChanged(){if(readingResult)return;modelCheckPending=true;SpreadsheetMergeBridge.dirty(input.session,input.nodeId,true);scheduleRefresh();}
            });
            installOverlay();refreshDifferences();
            setActivePage(1);
            var display=getSite().getShell().getDisplay();display.timerExec(250,new Runnable(){public void run(){
                if(resultEditor==null || resultEditor.getMoxelControl().isDisposed())return;
                SpreadsheetMergeBridge.dirty(input.session,input.nodeId,isDirty());display.timerExec(250,this);
            }});
            var toolbar=reviewToolbar;
            addTool(documentToolbar,new Action("Сохранить в план"){@Override public void run(){doSave(new NullProgressMonitor());}},sharedIcon(ISharedImages.IMG_ETOOL_SAVE_EDIT),"Сохранить результат в план сравнения. Исходники проекта пока не меняются.");
            addTool(documentToolbar,new Action("Сохранить и вернуться к сравнению"){@Override public void run(){saveAndReturn();}},sharedIcon(ISharedImages.IMG_ETOOL_SAVEALL_EDIT),"Сохранить изменения в план, закрыть табличный документ и перейти к сравнению/объединению. Исходники проекта ещё не применяются.");
            highlightingAction=new Action("Подсветка",Action.AS_CHECK_BOX){@Override public void run(){setHighlighting(isChecked());}};highlightingAction.setChecked(highlighting);
            addTool(toolbar,highlightingAction,compareIcon("twowaycompare_co"),"Показать или скрыть цветовую подсветку изменений макета.");
            toolbar.add(new Separator());
            addTool(toolbar,new Action("Предыдущее отличие"){@Override public void run(){navigate(-1);}},compareIcon("prev_diff_nav"),"Перейти к предыдущему отличию.");
            addTool(toolbar,new Action("Следующее отличие"){@Override public void run(){navigate(1);}},compareIcon("next_diff_nav"),"Перейти к следующему отличию.");
            toolbar.add(new Separator());
            addTool(documentToolbar,new Action("Объединить независимые изменения"){@Override public void run(){semanticAction("auto_merge",null);}},compareIcon("smartmode_co"),"Объединить независимые изменения. Сохранить ручные правки; спорные свойства оставить конфликтами.");
            addTool(toolbar,new Action("Взять текущее"){@Override public void run(){semanticAction("resolve_conflicts","MAIN");}},compareIcon("copy_r_co"),"Для выбранного отличия взять значение текущей конфигурации (слева).");
            addTool(toolbar,new Action("Взять источник"){@Override public void run(){semanticAction("resolve_conflicts","OTHER");}},compareIcon("copy_l_co"),"Для выбранного отличия взять значение конфигурации-источника (справа).");
            addTool(toolbar,new Action("Подтвердить результат"){@Override public void run(){semanticAction("resolve_conflicts","RESULT");}},org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin("org.eclipse.ui.ide","icons/full/obj16/complete_tsk.png"),"Подтвердить текущий результат выбранного отличия как решение конфликта.");
            documentToolbar.add(new Separator());
            addTool(documentToolbar,new Action("Сбросить результат"){@Override public void run(){resetResult();}},sharedIcon(ISharedImages.IMG_TOOL_UNDO),"Сбросить все ручные правки этого макета и решения конфликтов к текущей конфигурации. Потребуется подтверждение.");
            documentToolbar.add(new Separator());
            addTool(documentToolbar,new Action("Закрыть табличный редактор"){@Override public void run(){closeReview();}},org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin("com.codepilot1c.ui","resources/icons/close-red.png"),"Закрыть только редактор табличного документа с подтверждением. Сравнение и сохранённый план останутся открытыми.");
            toolbar.update(true);documentToolbar.update(true);
        }catch(Exception e){throw new IllegalStateException("Cannot open native spreadsheet merge editor",e);}
    }
    private org.eclipse.jface.resource.ImageDescriptor sharedIcon(String key){return PlatformUI.getWorkbench().getSharedImages().getImageDescriptor(key);}
    private org.eclipse.jface.resource.ImageDescriptor compareIcon(String name){return org.eclipse.ui.plugin.AbstractUIPlugin.imageDescriptorFromPlugin("org.eclipse.compare","icons/full/elcl16/"+name+".png");}
    private void addTool(IToolBarManager toolbar,Action action,org.eclipse.jface.resource.ImageDescriptor icon,String tooltip){
        if(Set.of("Взять текущее","Взять источник","Подтвердить результат").contains(action.getText())){selectionActions.add(action);action.setEnabled(diffTable.getSelectionCount()>0);}
        action.setImageDescriptor(icon==null?sharedIcon(ISharedImages.IMG_OBJ_ELEMENT):icon);action.setToolTipText(tooltip);action.setDescription(tooltip);reviewActions.add(action);var contribution=new ActionContributionItem(action);if(action.getText().equals("Сохранить и вернуться к сравнению"))contribution.setMode(ActionContributionItem.MODE_FORCE_TEXT);toolbar.add(contribution);
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
        if(!readOnly){resultEditor=editor;resultContext=context;resultFqn=fqn;}
    }
    public Control getSnapshotControl(){return snapshotControl;}
    public Map<String,Object> semanticUiState(){return Map.of("highlighting",highlighting,"difference_count",differences.size(),"visible_difference_count",diffTable.getItemCount(),"overlay_paints",overlayPaints,"overlay_marks",overlayMarks,"refresh_pending",refreshPending,"difference_refreshes",differenceRefreshes,"dirty",isDirty(),"toolbar_actions",reviewActions.stream().map(a->Map.of("label",a.getText(),"tooltip",a.getToolTipText(),"has_icon",a.getImageDescriptor()!=null)).toList());}
    public void setHighlighting(boolean value){highlighting=value;if(highlightingAction!=null)highlightingAction.setChecked(value);resultEditor.getMoxelControl().redraw();resultEditor.getMoxelControl().update();}
    public void revealDifference(String id){for(int i=0;i<differences.size();i++)if(differences.get(i).id().equals(id)){if(i<diffOffset||i>=diffOffset+diffTable.getItemCount())populateDifferences(i/500*500);diffTable.setSelection(i-diffOffset);diffTable.showSelection();revealSelected();return;}throw new IllegalArgumentException("Difference is stale; refresh semantic_diff");}
    @Override public boolean isDirty(){return resultContext!=null && (localDirty || modelCheckPending || resultEditor!=null && resultEditor.getMoxelControl().isInplaceEdit());}
    @Override public void doSave(IProgressMonitor monitor) {
        try{persistResult(monitor);}
        catch(Exception error){com.codepilot1c.core.internal.VibeCorePlugin.logError(error);MessageDialog.openError(getSite().getShell(),"Результат не сохранён",error.getMessage());}
    }
    /** MCP gets the real save outcome and never waits for a human to dismiss an error dialog. */
    public void savePlanForAgent()throws Exception{persistResult(new NullProgressMonitor());}
    private void persistResult(IProgressMonitor monitor)throws Exception {
        lastSaveSucceeded=false;if(resultEditor==null)throw new IllegalStateException("Spreadsheet editor is not ready");
        resultEditor.getMoxelControl().finishInplaceEdit(false);
        String xml=serializeResult();
        var saved=SpreadsheetMergeSupport.commit(input.session,input.nodeId,xml,input.expectedHash);
        input.expectedHash=(String)saved.get("node_sha256");
        lastSaveSucceeded=true;savedResultXml=xml;localDirty=false;modelCheckPending=false;SpreadsheetMergeBridge.dirty(input.session,input.nodeId,false);firePropertyChange(PROP_DIRTY);scheduleRefresh();
    }
    @Override public boolean isSaveAsAllowed(){return false;}
    @Override public void doSaveAs(){}
    @Override public void dispose() {
        try{
            if(clipboardHandlers!=null)clipboardActivations.forEach(clipboardHandlers::deactivateHandler);
            clipboardGuards.forEach(org.eclipse.core.commands.IHandler::dispose);
            if(moxelContexts!=null && moxelContext!=null)moxelContexts.deactivateContext(moxelContext);
            super.dispose();
        }finally{
            com.codepilot1c.core.edt.editor.EdtEditorService.getInstance().forget(this);
            SpreadsheetMergeBridge.dirty(input.session,input.nodeId,false);
            for(var c:contexts)if(!c.isDisposed())c.dispose();
        }
    }
}
