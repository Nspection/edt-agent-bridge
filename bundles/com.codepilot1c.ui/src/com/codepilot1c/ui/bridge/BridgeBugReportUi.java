// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import org.eclipse.jface.dialogs.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.*;
import com.codepilot1c.core.edt.development.*;

/** One human dialog at a time. UI inputs never enter MCP or background-job payloads. */
public final class BridgeBugReportUi implements BugReportInteraction {
 private static BridgeBugReportUi instance;private ServiceRegistration<BugReportInteraction> registration;
 private CompletableFuture<Map<String,Object>> active;private WorkDialog dialog;
 public static synchronized void start(BundleContext context){if(instance!=null)return;instance=new BridgeBugReportUi();instance.registration=context.registerService(BugReportInteraction.class,instance,null);}
 public static synchronized void stop(){if(instance==null)return;instance.close();instance.registration.unregister();instance=null;}
 public static synchronized BridgeBugReportUi get(){if(instance==null)throw new IllegalStateException("GitHub UI unavailable");return instance;}
 private synchronized void close(){if(dialog!=null&&dialog.preserveOnClose()){var pending=dialog;pending.onUi(pending::close);}else if(active!=null)active.cancel(false);}
 private synchronized CompletableFuture<Map<String,Object>> open(String key,java.util.function.BiConsumer<Shell,CompletableFuture<Map<String,Object>>> create){
  if(active!=null&&!active.isDone())return CompletableFuture.completedFuture(Map.of("state","ui_busy","reason","complete_current_github_dialog"));
  if(!PlatformUI.isWorkbenchRunning())return CompletableFuture.completedFuture(Map.of("state","ui_unavailable"));
  var display=PlatformUI.getWorkbench().getDisplay();var future=new CompletableFuture<Map<String,Object>>();active=future;
  boolean queued=BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!future.isDone()){Shell shell=display.getActiveShell();if(shell==null)shell=PlatformUI.getWorkbench().getActiveWorkbenchWindow()==null?null:PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();if(shell==null||shell.isDisposed())future.complete(Map.of("state","ui_unavailable"));else create.accept(shell,future);}});
  if(!queued)future.complete(Map.of("state","ui_unavailable"));return future;
 }
 public CompletableFuture<Map<String,Object>> login(String method){if(!Set.of("browser","token","choose").contains(method))return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported login method"));return open("login",(shell,result)->show(new LoginDialog(shell,result,method)));}
 public CompletableFuture<Map<String,Object>> review(String id,String hash){return open("review:"+id+":"+hash,(shell,result)->show(new ReportDialog(shell,result,id,hash)));}
 private void show(WorkDialog value){synchronized(this){dialog=value;}value.open();}
 static String errorText(Throwable failure){
  while((failure instanceof CompletionException||failure instanceof ExecutionException)&&failure.getCause()!=null)failure=failure.getCause();
  if(failure instanceof BugReportGitHub.HttpFailure http)return switch(http.status()){case 401->"GitHub отклонил вход. Проверьте токен или войдите через браузер.";case 403->"GitHub отказал в доступе. Проверьте права токена и доступ к репозиторию.";case 404->"GitHub не видит репозиторий с этим доступом.";case 429->"GitHub ограничил частоту запросов. Повторите позже.";default->"GitHub вернул HTTP "+http.status()+". Действие не подтверждено.";};
  if(failure instanceof BugReportService.UserActionRequired required)return switch(required.reason()){case "login_required"->"Сначала войдите в GitHub.";case "reporting_disabled"->"Отправка отключена. Выберите режим с подтверждением в настройках EDT Agent Bridge.";case "account_changed","stale_report"->"Аккаунт или отчёт изменился. Обновите черновик и проверьте текст заново.";case "daily_quota"->"Достигнут лимит: до 5 отчётов в сутки в этой рабочей области.";case "report_already_handled"->"Этот отчёт уже обработан. Агент может прочитать его статус.";default->"Проверьте и подтвердите актуальный текст отчёта.";};
  if(failure instanceof IllegalArgumentException)return "Проверьте формат введённых данных. Токен не должен содержать пробелы или переносы строк.";
  if(failure instanceof TimeoutException)return "Время ожидания истекло. Проверьте соединение с GitHub.";
  return "Действие не выполнено. Проверьте Git for Windows с Credential Manager и соединение с GitHub.";
 }
 private abstract static class WorkDialog extends TitleAreaDialog {
  final CompletableFuture<Map<String,Object>> result;final AtomicBoolean cancelled=new AtomicBoolean();volatile Thread worker;
  WorkDialog(Shell shell,CompletableFuture<Map<String,Object>> result){super(shell);this.result=result;setShellStyle(getShellStyle()|SWT.RESIZE);result.whenComplete((v,e)->{if(result.isCancelled()){cancelled.set(true);Thread w=worker;if(w!=null)w.interrupt();Display display=shell.getDisplay();BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(getShell()!=null&&!getShell().isDisposed())close();});}});}
  boolean active(){return !cancelled.get()&&!result.isDone();}
  void work(java.util.concurrent.Callable<Map<String,Object>> action){worker=new Thread(()->{try{var outcome=action.call();result.complete(outcome);onUi(this::close);}catch(Exception failure){var recovered=failureResult(failure);if(recovered!=null){result.complete(recovered);onUi(this::close);}else onUi(()->{if(active()){setErrorMessage(errorText(failure));busy(false);}});}finally{workerFinished();Thread.interrupted();}},"bridge-github-user-action");worker.setDaemon(true);worker.start();}
  Map<String,Object> failureResult(Exception failure){return null;}
  void workerFinished(){}
  boolean preserveOnClose(){return false;}
  void onUi(Runnable action){Shell shell=getShell();if(shell==null)return;var display=shell.getDisplay();BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!shell.isDisposed())action.run();});}
  abstract void busy(boolean value);
  @Override public boolean close(){if(!result.isDone()&&!preserveOnClose()){cancelled.set(true);result.complete(Map.of("state","cancelled"));Thread w=worker;if(w!=null)w.interrupt();}return super.close();}
 }
 private static final class LoginDialog extends WorkDialog {
  final String method;Text token;Button browser,save;
  LoginDialog(Shell parent,CompletableFuture<Map<String,Object>> result,String method){super(parent,result);this.method=method;}
  protected Control createDialogArea(Composite parent){
   setTitle("Вход в GitHub");setMessage("Один вход для этой рабочей области. Пароль вводится только на сайте GitHub; токен сохраняется в Windows Credential Manager.");var area=(Composite)super.createDialogArea(parent);var body=new Composite(area,SWT.NONE);body.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));body.setLayout(new GridLayout(1,false));
   new Label(body,SWT.NONE).setText("Токен — если выбран вход по токену:");token=new Text(body,SWT.BORDER|SWT.PASSWORD);token.setData("bugreport-control","token");var data=new GridData(SWT.FILL,SWT.CENTER,true,false);data.widthHint=520;token.setLayoutData(data);
   var link=new Link(body,SWT.WRAP);link.setText("<a>Создать токен на GitHub</a> (для публичных issues: classic public_repo или подходящие права Issues).");link.addListener(SWT.Selection,e->org.eclipse.swt.program.Program.launch("https://github.com/settings/tokens/new?description=EDT%20Agent%20Bridge%20reports&scopes=public_repo"));return area;
  }
  protected void createButtonsForButtonBar(Composite parent){browser=createButton(parent,1001,"Войти через браузер",!method.equals("token"));save=createButton(parent,1002,"Проверить и сохранить токен",method.equals("token"));createButton(parent,IDialogConstants.CANCEL_ID,"Отмена",false);if(method.equals("browser"))getShell().getDisplay().asyncExec(this::browser);else token.setFocus();}
  protected void buttonPressed(int id){if(id==1001)browser();else if(id==1002){char[] secret=token.getTextChars();token.setText("");busy(true);setErrorMessage(null);work(()->{try{return BugReportService.workspace().tokenFromUserInterface(secret,this::active);}finally{Arrays.fill(secret,'\0');}});}else super.buttonPressed(id);}
  private void browser(){if(!active()||browser==null||browser.isDisposed()||!browser.getEnabled())return;busy(true);setErrorMessage(null);setMessage("Завершите вход в открытом браузере. После проверки аккаунта это окно закроется.");work(()->BugReportService.workspace().connectFromUserInterface("",this::active));}
  void busy(boolean value){if(browser!=null&&!browser.isDisposed())browser.setEnabled(!value);if(save!=null&&!save.isDisposed())save.setEnabled(!value);if(token!=null&&!token.isDisposed())token.setEnabled(!value);}
  @Override public boolean close(){if(token!=null&&!token.isDisposed())token.setText("");return super.close();}
 }
 private static final class ReportDialog extends WorkDialog {
  final String id,hash;Button send;Text preview;Map<String,Object> row;volatile boolean sendInFlight;
  ReportDialog(Shell parent,CompletableFuture<Map<String,Object>> result,String id,String hash){super(parent,result);this.id=id;this.hash=hash;}
  protected Control createDialogArea(Composite parent){setTitle("Отправить багрепорт в GitHub");setMessage("Проверьте текст: именно он будет опубликован в открытом Nspection/edt-agent-bridge.");var area=(Composite)super.createDialogArea(parent);preview=new Text(area,SWT.MULTI|SWT.READ_ONLY|SWT.WRAP|SWT.V_SCROLL|SWT.BORDER);preview.setData("bugreport-control","preview");var data=new GridData(SWT.FILL,SWT.FILL,true,true);data.widthHint=700;data.heightHint=380;preview.setLayoutData(data);
   preview.setText("Чтение черновика…");getShell().getDisplay().asyncExec(()->{if(!active())return;
    worker=new Thread(()->{try{var draft=BugReportService.workspace().previewFromUserInterface(id,hash);onUi(()->{if(active()){row=draft;preview.setText(row.get("title")+"\n\n"+row.get("body"));busy(false);}});}catch(Exception error){onUi(()->{if(active())setErrorMessage(errorText(error));});}},"bridge-report-preview");worker.setDaemon(true);worker.start();
   });return area;}
  protected void createButtonsForButtonBar(Composite parent){send=createButton(parent,IDialogConstants.OK_ID,"Отправить в GitHub",true);send.setData("bugreport-control","send");send.setEnabled(row!=null&&"prepared".equals(row.get("state"))&&hash.equals(row.get("payload_sha256")));createButton(parent,IDialogConstants.CANCEL_ID,"Отмена",false);}
  protected void okPressed(){sendInFlight=true;busy(true);setErrorMessage(null);work(()->{var service=BugReportService.workspace();service.approveFromUserInterface(id,hash);return service.submit(id,hash);});}
  void busy(boolean value){if(send!=null&&!send.isDisposed())send.setEnabled(!value&&row!=null);}
  @Override boolean preserveOnClose(){return sendInFlight;}
  @Override void workerFinished(){sendInFlight=false;}
  @Override Map<String,Object> failureResult(Exception failure){
   try{var service=BugReportService.workspace();String reason=failure instanceof BugReportService.UserActionRequired required?required.reason():failure instanceof BugReportGitHub.HttpFailure?"authentication_failed":"not_dispatched";return service.releaseApprovalAfterFailure(id,hash,reason);}
   catch(Exception unavailable){return Map.of("state","delivery_unknown","report_id",id,"reason","original_receipt_unavailable","next_call",BugReportRequests.call(Map.of("action","status","report_id",id)));}
  }
 }
}
