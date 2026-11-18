// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.*;
import java.util.concurrent.*;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.function.Predicate;
import org.eclipse.jface.dialogs.*;
import org.eclipse.swt.SWT;
import org.eclipse.swt.custom.StackLayout;
import org.eclipse.swt.program.Program;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.ui.PlatformUI;
import org.osgi.framework.*;
import com.codepilot1c.core.edt.development.*;

/** One human dialog at a time. UI inputs never enter MCP or background-job payloads. */
public final class BridgeBugReportUi implements BugReportInteraction {
 private static final String ISSUE_FORM = "https://github.com/Nspection/edt-agent-bridge/issues/new";
 private static final String TOKEN_FORM = "https://github.com/settings/tokens/new?description=EDT%20Agent%20Bridge%20reports&scopes=public_repo";
 private final Predicate<String> launchBrowser;

 private BridgeBugReportUi(){this(Program::launch);}
 BridgeBugReportUi(Predicate<String> launchBrowser){this.launchBrowser=Objects.requireNonNull(launchBrowser);}

 private static BridgeBugReportUi instance;private ServiceRegistration<BugReportInteraction> registration;
 private CompletableFuture<Map<String,Object>> active;private WorkDialog dialog;private boolean stopped;
 public static synchronized void start(BundleContext context){if(instance!=null)return;var candidate=new BridgeBugReportUi();candidate.registration=context.registerService(BugReportInteraction.class,candidate,null);instance=candidate;}
 public static synchronized void stop(){if(instance==null)return;var closing=instance;instance=null;try{closing.close();}finally{if(closing.registration!=null)closing.registration.unregister();}}
 public static synchronized BridgeBugReportUi get(){if(instance==null)throw new IllegalStateException("GitHub UI unavailable");return instance;}
 private synchronized void close(){stopped=true;if(dialog!=null&&!dialog.claimCancellation()){var pending=dialog;pending.onUi(pending::close);}else if(active!=null)active.cancel(false);}
 private synchronized CompletableFuture<Map<String,Object>> open(String key,java.util.function.BiConsumer<Shell,CompletableFuture<Map<String,Object>>> create){
  if(stopped)return CompletableFuture.completedFuture(Map.of("state","ui_unavailable"));
  if(active!=null&&!active.isDone())return CompletableFuture.completedFuture(Map.of("state","ui_busy","reason","complete_current_github_dialog"));
  if(!PlatformUI.isWorkbenchRunning())return CompletableFuture.completedFuture(Map.of("state","ui_unavailable"));
  var display=PlatformUI.getWorkbench().getDisplay();var future=new CompletableFuture<Map<String,Object>>();active=future;dialog=null;
  boolean queued=BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!future.isDone()){Shell shell=display.getActiveShell();if(shell==null)shell=PlatformUI.getWorkbench().getActiveWorkbenchWindow()==null?null:PlatformUI.getWorkbench().getActiveWorkbenchWindow().getShell();if(shell==null||shell.isDisposed())future.complete(Map.of("state","ui_unavailable"));else create.accept(shell,future);}});
  if(!queued)future.complete(Map.of("state","ui_unavailable"));return future;
 }
 public CompletableFuture<Map<String,Object>> login(String method){
  if(!Set.of("browser","token","choose").contains(method))return CompletableFuture.failedFuture(new IllegalArgumentException("Unsupported login method"));
  return open("login",(shell,result)->{
   if(BugReportGitHub.credentialManagerAvailable())show(new LoginDialog(shell,result,method,launchBrowser));
   else openIssueForm(result);
  });
 }
 public CompletableFuture<Map<String,Object>> review(String id,String hash){
  return open("review:"+id+":"+hash,(shell,result)->{
   if(BugReportGitHub.credentialManagerAvailable())show(new ReportDialog(shell,result,id,hash));
   else openIssueForm(result);
  });
 }
 private synchronized void openIssueForm(CompletableFuture<Map<String,Object>> result){
  if(stopped||result!=active||result.isDone()){
   result.complete(Map.of("state","ui_unavailable"));
   return;
  }
  boolean opened;
  try{opened=launchBrowser.test(ISSUE_FORM);}catch(RuntimeException failure){opened=false;}
  result.complete(Map.of("state",opened?"browser_report_opened":"browser_unavailable",
   "browser_url",ISSUE_FORM,"report_submitted",false));
 }
 static String browserReportStatus(Map<String,Object> result){
  return switch(Objects.toString(result.get("state"),"")){
   case "browser_report_opened"->"Форма GitHub открыта. Напишите и отправьте отчёт в браузере.";
   case "browser_unavailable"->"Не удалось открыть браузер. Откройте ссылку на форму GitHub.";
   default->null;
  };
 }
 private void show(WorkDialog value){synchronized(this){if(stopped||value.result!=active||value.result.isDone()){value.result.complete(Map.of("state","ui_unavailable"));return;}dialog=value;}value.open();}
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
  boolean claimCancellation(){return !preserveOnClose();}
  void onUi(Runnable action){Shell shell=getShell();if(shell==null)return;var display=shell.getDisplay();BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!shell.isDisposed())action.run();});}
  abstract void busy(boolean value);
  @Override public boolean close(){if(!result.isDone()&&claimCancellation()){cancelled.set(true);result.complete(Map.of("state","cancelled"));Thread w=worker;if(w!=null)w.interrupt();}return super.close();}
 }
 private static final class LoginDialog extends WorkDialog {
  private static final int CONNECT_ID=1001;
  private final String method;
  private final Predicate<String> launchBrowser;
  private final java.util.concurrent.atomic.AtomicInteger publication=new java.util.concurrent.atomic.AtomicInteger();
  private Composite pages,browserPage,tokenPage;
  private StackLayout stack;
  private Text token;
  private Button connect,chooseToken,createToken;
  private boolean tokenMode;

  LoginDialog(Shell parent,CompletableFuture<Map<String,Object>> result,String method,Predicate<String> launchBrowser){
   super(parent,result);
   this.method=method;
   this.launchBrowser=launchBrowser;
   tokenMode=method.equals("token");
  }
  boolean beginCommit(){return active()&&publication.compareAndSet(0,2);}
  @Override boolean preserveOnClose(){return publication.get()==2;}
  @Override boolean claimCancellation(){return publication.compareAndSet(0,1)||publication.get()==1;}
  @Override Map<String,Object> failureResult(Exception failure){return publication.get()==2?Map.of("state","failed","reason","login_configuration_not_committed"):null;}
  @Override void workerFinished(){publication.compareAndSet(2,3);}

  @Override protected Control createDialogArea(Composite parent){
   var area=(Composite)super.createDialogArea(parent);
   pages=new Composite(area,SWT.NONE);
   pages.setLayoutData(new GridData(SWT.FILL,SWT.FILL,true,true));
   stack=new StackLayout();
   pages.setLayout(stack);
   browserPage=new Composite(pages,SWT.NONE);
   browserPage.setLayout(new GridLayout(1,false));
   if(method.equals("choose")){
    chooseToken=new Button(browserPage,SWT.PUSH);
    chooseToken.setText("Войти с токеном…");
    chooseToken.setData("bugreport-control","choose-token");
    chooseToken.addListener(SWT.Selection,e->showTokenPage());
   }
   updatePage();
   return area;
  }

  private void showTokenPage(){
   if(tokenPage==null){
    tokenPage=new Composite(pages,SWT.NONE);
    tokenPage.setLayout(new GridLayout(1,false));
    createToken=new Button(tokenPage,SWT.PUSH);
    createToken.setText("Создать токен");
    createToken.setData("bugreport-control","create-token");
    createToken.addListener(SWT.Selection,e->{
     try{if(launchBrowser.test(TOKEN_FORM)){setErrorMessage(null);return;}}
     catch(RuntimeException failure){/* Show the same recoverable browser error. */}
     setErrorMessage("Не удалось открыть страницу GitHub. Откройте настройки токенов GitHub в браузере.");
    });
    new Label(tokenPage,SWT.NONE).setText("Скопируйте созданный токен и вставьте его сюда.");
    token=new Text(tokenPage,SWT.BORDER|SWT.PASSWORD);
    token.setData("bugreport-control","token");
    var data=new GridData(SWT.FILL,SWT.CENTER,true,false);
    data.widthHint=420;
    token.setLayoutData(data);
   }
   tokenMode=true;
   updatePage();
   if(connect!=null)connect.setText("Подключить");
   Shell shell=getShell();
   if(shell.isVisible()){
    var preferred=shell.computeSize(SWT.DEFAULT,SWT.DEFAULT,true);
    var current=shell.getSize();
    shell.setSize(Math.max(current.x,preferred.x),Math.max(current.y,preferred.y));
   }
   shell.layout(true,true);
   token.setFocus();
  }

  private void updatePage(){
   setTitle(tokenMode?"Вход с токеном":"Подключить GitHub");
   setMessage(tokenMode?"На GitHub откроется форма токена с правом public_repo для публичных репозиториев."
    :"Войдите в свой аккаунт на GitHub и подтвердите доступ. Bridge проверит подключение автоматически.");
   stack.topControl=tokenMode&&tokenPage!=null?tokenPage:browserPage;
  }

  @Override protected void createButtonsForButtonBar(Composite parent){
   connect=createButton(parent,CONNECT_ID,tokenMode?"Подключить":"Подключить GitHub",true);
   connect.setData("bugreport-control","connect");
   createButton(parent,IDialogConstants.CANCEL_ID,"Отмена",false);
   if(tokenMode)showTokenPage();
   else if(method.equals("browser"))getShell().getDisplay().asyncExec(this::browser);
  }

  @Override protected void buttonPressed(int id){
   if(id!=CONNECT_ID){super.buttonPressed(id);return;}
   if(!tokenMode){browser();return;}
   if(token.getCharCount()==0){setErrorMessage("Вставьте токен, созданный на GitHub.");return;}
   char[] secret=token.getTextChars();
   token.setText("");
   busy(true);
   setErrorMessage(null);
   work(()->{try{return BugReportService.workspace().tokenFromUserInterface(secret,this::beginCommit);}
    finally{Arrays.fill(secret,'\0');}});
  }

  private void browser(){
   if(!active()||connect==null||connect.isDisposed()||!connect.getEnabled())return;
   busy(true);
   setErrorMessage(null);
   setMessage("Завершите вход в браузере. После проверки аккаунта это окно закроется.");
   work(()->BugReportService.workspace().connectFromUserInterface("",this::beginCommit));
  }

  @Override void busy(boolean value){
   for(Button button:new Button[]{connect,chooseToken,createToken}){
    if(button!=null&&!button.isDisposed())button.setEnabled(!value);
   }
   if(token!=null&&!token.isDisposed())token.setEnabled(!value);
  }

  @Override public boolean close(){
   publication.compareAndSet(0,1);
   if(token!=null&&!token.isDisposed())token.setText("");
   return super.close();
  }
 }
 private static final class ReportDialog extends WorkDialog {
  final String id,hash;Button send;Text preview;Map<String,Object> row;volatile boolean sendInFlight;
  ReportDialog(Shell parent,CompletableFuture<Map<String,Object>> result,String id,String hash){super(parent,result);this.id=id;this.hash=hash;}
  protected Control createDialogArea(Composite parent){setTitle("Отправить багрепорт в GitHub");setMessage("Проверьте текст на пароли, пути и личные данные: он будет опубликован в открытом Nspection/edt-agent-bridge.");var area=(Composite)super.createDialogArea(parent);preview=new Text(area,SWT.MULTI|SWT.READ_ONLY|SWT.WRAP|SWT.V_SCROLL|SWT.BORDER);preview.setData("bugreport-control","preview");var data=new GridData(SWT.FILL,SWT.FILL,true,true);data.widthHint=700;data.heightHint=380;preview.setLayoutData(data);
   preview.setText("Чтение черновика…");getShell().getDisplay().asyncExec(()->{if(!active())return;
    worker=new Thread(()->{try{var draft=BugReportService.workspace().previewFromUserInterface(id,hash);onUi(()->{if(active()){row=draft;preview.setText(row.get("title")+"\n\n"+row.get("body"));busy(false);}});}catch(Exception error){onUi(()->{if(active())setErrorMessage(errorText(error));});}},"bridge-report-preview");worker.setDaemon(true);worker.start();
   });return area;}
  protected void createButtonsForButtonBar(Composite parent){send=createButton(parent,IDialogConstants.OK_ID,"Отправить в GitHub",true);send.setData("bugreport-control","send");send.setEnabled(row!=null&&"prepared".equals(row.get("state"))&&hash.equals(row.get("payload_sha256")));createButton(parent,IDialogConstants.CANCEL_ID,"Отмена",false);}
  protected void okPressed(){sendInFlight=true;busy(true);setErrorMessage(null);work(()->{var service=BugReportService.workspace();service.approveFromUserInterface(id,hash);return service.submitFromUserInterface(id,hash);});}
  void busy(boolean value){if(send!=null&&!send.isDisposed())send.setEnabled(!value&&row!=null);}
  @Override boolean preserveOnClose(){return sendInFlight;}
  @Override void workerFinished(){sendInFlight=false;}
  @Override Map<String,Object> failureResult(Exception failure){
   try{var service=BugReportService.workspace();String reason=failure instanceof BugReportService.UserActionRequired required?required.reason():failure instanceof BugReportGitHub.HttpFailure?"authentication_failed":"not_dispatched";return service.releaseApprovalAfterFailure(id,hash,reason);}
   catch(Exception unavailable){return Map.of("state","delivery_unknown","report_id",id,"reason","original_receipt_unavailable","next_call",BugReportRequests.call(Map.of("action","status","report_id",id)));}
  }
 }
}
