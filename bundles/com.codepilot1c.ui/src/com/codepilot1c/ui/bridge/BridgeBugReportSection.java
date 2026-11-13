// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import com.codepilot1c.core.edt.development.BugReportService;
/** Sharing choices and exact-draft approvals are user UI actions, never MCP flags. */
final class BridgeBugReportSection {
 private final Combo policy;private final Label status;private final Button connect,review;private final Display display;
 private String original="off";
 BridgeBugReportSection(Composite parent){
  display=parent.getDisplay();Group group=new Group(parent,SWT.NONE);group.setText("Багрепорты в GitHub");group.setLayout(new GridLayout(2,false));group.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));
  Label info=new Label(group,SWT.WRAP);info.setText("Отчёты публикуются в открытом Nspection/edt-agent-bridge. Передаются только версия и разрешённые технические коды/счётчики; без кода, путей, имён баз и проектов, логов, снимков и учётных данных. Авторизация GitHub — через Git Credential Manager из Git for Windows; права OAuth показаны GitHub и могут быть шире Issues.");var infoData=new GridData(SWT.FILL,SWT.TOP,true,false,2,1);infoData.widthHint=480;info.setLayoutData(infoData);
  status=new Label(group,SWT.WRAP);status.setText("Проверка настройки…");status.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
  connect=new Button(group,SWT.PUSH);connect.setText("Подключить GitHub…");connect.addListener(SWT.Selection,e->background(()->{BugReportService.workspace().connectFromUserInterface();refresh();}));
  policy=new Combo(group,SWT.READ_ONLY);policy.setItems("Не отправлять","Подтверждать каждый отчёт","Разрешить агентам очищенные отчёты");policy.select(0);policy.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));
  review=new Button(group,SWT.PUSH);review.setText("Просмотреть и разрешить отчёт…");review.addListener(SWT.Selection,e->review());
  refresh();
 }
 private static String mode(int index){return switch(index){case 1->"ask";case 2->"auto_sanitized";default->"off";};}
 private void ui(Runnable task){BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!status.isDisposed())task.run();});}
 private void text(String value){status.setText(value);status.getParent().layout(true,true);}
 private interface Work{void run()throws Exception;}
 private void background(Work work){connect.setEnabled(false);review.setEnabled(false);CompletableFuture.runAsync(()->{try{work.run();}catch(Exception failure){ui(()->text("Не удалось выполнить действие. Проверьте Git Credential Manager, подключение и свежесть отчёта."));}finally{ui(()->{connect.setEnabled(true);review.setEnabled(true);});}});}
 private void refresh(){CompletableFuture.runAsync(()->{try{var state=BugReportService.workspace().status();ui(()->{original=String.valueOf(state.get("policy"));policy.select(original.equals("ask")?1:original.equals("auto_sanitized")?2:0);text("GitHub: "+state.get("configured_account")+"; настройка: "+original);});}catch(Exception failure){ui(()->text("Настройка отчётов недоступна"));}});}
 private void review(){background(()->{var row=BugReportService.workspace().latestPrepared();ui(()->{
  if(!"prepared".equals(row.get("state"))){text("Нет подготовленного отчёта");return;}
  String title=String.valueOf(row.get("title")),body=String.valueOf(row.get("body")),hash=String.valueOf(row.get("payload_sha256"));
  if(MessageDialog.openConfirm(status.getShell(),"Разрешить публикацию багрепорта?","Открытый репозиторий: Nspection/edt-agent-bridge\n\n"+title+"\n\n"+body+"\nSHA256: "+hash+"\n\nРазрешить агенту опубликовать именно этот отчёт?"))background(()->{BugReportService.workspace().approveFromUserInterface(String.valueOf(row.get("report_id")),hash);ui(()->text("Этот отчёт разрешён; агент может выполнить submit."));});
 });});}
 void save()throws Exception{
  String selected=mode(policy.getSelectionIndex());
  if(selected.equals("auto_sanitized")&&!original.equals(selected)&&!MessageDialog.openConfirm(status.getShell(),"Разрешить очищенные багрепорты?","Разрешить ИИ-агентам публиковать в открытом GitHub только техническую диагностику Bridge без отдельных вопросов? До 5 отчётов в сутки в этой EDT. Код, файлы, скриншоты и произвольный текст не поддерживаются. Разрешение можно отключить здесь."))throw new IllegalStateException("Отправка отчётов не разрешена");
  BugReportService.workspace().policyFromUserInterface(selected);original=selected;
 }
}
