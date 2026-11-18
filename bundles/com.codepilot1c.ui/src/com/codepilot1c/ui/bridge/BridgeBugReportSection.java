// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;
import java.util.*;
import java.util.concurrent.*;
import org.eclipse.jface.dialogs.MessageDialog;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import com.codepilot1c.core.edt.development.BugReportService;
import com.codepilot1c.core.edt.development.BugReportGitHub;

/** Clear account identity, one-time login, and explicit exact-text publishing. */
final class BridgeBugReportSection {
 private final Combo policy;private final Label account,status;private final Button browser,token,verify,review;private final Link issue;private final Display display;
 private String original="off",issueUrl="",expectedAccount="",expectedRevision="",expectedLogin="";private boolean policyEdited;
 private final boolean managedLogin=BugReportGitHub.credentialManagerAvailable();
 BridgeBugReportSection(Composite parent){
  display=parent.getDisplay();Group group=new Group(parent,SWT.NONE);group.setText("Сообщить об ошибке — GitHub");group.setLayout(new GridLayout(2,false));group.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));
  Label info=new Label(group,SWT.WRAP);info.setText("Багрепорты отправляются в Nspection/edt-agent-bridge. Агент может подготовить описание любой ошибки и открыть окно проверки. Публикуется только показанный текст; код, журналы и снимки не прикладываются автоматически.");var wrap=new GridData(SWT.FILL,SWT.TOP,true,false,2,1);wrap.widthHint=520;info.setLayoutData(wrap);
  new Label(group,SWT.NONE).setText("Аккаунт GitHub");account=new Label(group,SWT.WRAP);account.setData("bugreport-control","account-status");account.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));account.setText("Вход не выполнен");
  var actions=new Composite(group,SWT.NONE);actions.setLayout(new RowLayout());actions.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));
  browser=button(actions,managedLogin?"Подключить GitHub":"Открыть баг-репорт на GitHub","login-browser",()->login("browser"));
  token=button(actions,"Войти с токеном…","login-token",()->login("token"));
  verify=button(actions,"Проверить подключение","verify-github",()->background(()->{var result=BugReportService.workspace().verifyAccount();ui(()->showAccount(result));}));
  visible(token,managedLogin);
  visible(verify,false);
  new Label(group,SWT.NONE).setText("Отправка отчётов");policy=new Combo(group,SWT.READ_ONLY);policy.setItems("Выключена","Каждый отчёт — показать и спросить","Автоматически — только технические коды");policy.select(0);policy.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));policy.addListener(SWT.Selection,e->policyEdited=true);
  status=new Label(group,SWT.WRAP);status.setText("Нет подготовленного отчёта");status.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));review=button(group,"Последний черновик…","review-report",this::review);
  issue=new Link(group,SWT.NONE);issue.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));issue.addListener(SWT.Selection,e->{if(!issueUrl.isBlank())org.eclipse.swt.program.Program.launch(issueUrl);});
  Label hint=new Label(group,SWT.WRAP);hint.setText(managedLogin?"Войдите в GitHub в браузере. Bridge сохранит подключение для этой рабочей области."
   :"Git Credential Manager не найден. Войти и отправить отчёт можно прямо на GitHub.");hint.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));refresh();
 }
 private Button button(Composite parent,String text,String data,Runnable action){var b=new Button(parent,SWT.PUSH);b.setText(text);b.setData("bugreport-control",data);b.addListener(SWT.Selection,e->action.run());return b;}
 private static void visible(Button button, boolean show){var data=new RowData();data.exclude=!show;button.setLayoutData(data);button.setVisible(show);}
 private boolean showBrowserReport(Map<String,Object> result){
  String message=BridgeBugReportUi.browserReportStatus(result);
  if(message==null)return false;
  status.setText(message);
  issueUrl=String.valueOf(result.get("browser_url"));
  issue.setText("<a>Открыть форму GitHub</a>");
  return true;
 }
 private static String verifiedWhen(String value){try{return java.time.format.DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm").withZone(java.time.ZoneId.systemDefault()).format(java.time.Instant.parse(value));}catch(RuntimeException invalid){return "ранее";}}
 private static String mode(int index){return switch(index){case 1->"ask";case 2->"auto_sanitized";default->"off";};}
 private void ui(Runnable task){BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!status.isDisposed())task.run();});}
 private void busy(boolean value){for(Button button:java.util.List.of(browser,token,verify,review))if(!button.isDisposed())button.setEnabled(!value);}
 private interface Work{void run()throws Exception;}
 private void background(Work work){busy(true);CompletableFuture.runAsync(()->{try{work.run();}catch(Exception error){ui(()->{status.setText(BridgeBugReportUi.errorText(error));status.getParent().layout(true,true);});}finally{ui(()->busy(false));}});}
 private void showAccount(Map<String,Object> state){String login=Objects.toString(state.get("configured_account"),"");String time=Objects.toString(state.get("last_verified_at"),"");account.setText(login.isBlank()?"Вход не выполнен":!managedLogin?"Сохранён @"+login+" — Git Credential Manager недоступен":time.isBlank()?"@"+login+" — подключение ещё не проверено":"Подключён @"+login+" — "+verifiedWhen(time));visible(verify,managedLogin&&!login.isBlank());if(managedLogin)browser.setText(login.isBlank()?"Подключить GitHub":"Сменить аккаунт GitHub");if(!policyEdited){original=Objects.toString(state.get("policy"),"off");expectedAccount=Objects.toString(state.get("account_id"),"");expectedRevision=Objects.toString(state.get("policy_revision"),"");expectedLogin=login;policy.select(original.equals("ask")?1:original.equals("auto_sanitized")?2:0);}account.getParent().layout(true,true);}
 private void refresh(){CompletableFuture.runAsync(()->{try{var state=BugReportService.workspace().status();ui(()->showAccount(state));}catch(Exception error){ui(()->account.setText("Не удалось прочитать настройку GitHub"));}});}
 private void login(String method){busy(true);try{BridgeBugReportUi.get().login(method).whenComplete((result,error)->ui(()->{busy(false);if(error!=null)status.setText(BridgeBugReportUi.errorText(error));else if(result.containsKey("configured_account")){showAccount(result);status.setText("Подключение сохранено.");}else if(!showBrowserReport(result))status.setText("Вход не завершён: "+result.getOrDefault("state","cancelled"));status.getParent().layout(true,true);}));}catch(Exception error){busy(false);status.setText(BridgeBugReportUi.errorText(error));}}
 private void review(){background(()->{var row=BugReportService.workspace().latestPrepared();ui(()->{
  if(!"prepared".equals(row.get("state"))){status.setText("Нет подготовленного отчёта. Попросите агента описать ошибку через edt_bug_report.");return;}
  busy(true);BridgeBugReportUi.get().review(String.valueOf(row.get("report_id")),String.valueOf(row.get("payload_sha256"))).whenComplete((result,error)->ui(()->{busy(false);if(error!=null)status.setText(BridgeBugReportUi.errorText(error));else if(showBrowserReport(result)){/* The browser owns the manual result. */}else if("submitted".equals(result.get("state"))){status.setText("Багрепорт отправлен: #"+result.get("issue_number"));issueUrl=String.valueOf(result.get("issue_url"));issue.setText("<a>Открыть issue на GitHub</a>");}else if(Boolean.TRUE.equals(result.get("delivery_rejected")))status.setText("GitHub отклонил обращение (HTTP "+result.get("rejected_http_status")+"). Проверьте доступ и подтвердите текст заново.");else if("not_submitted".equals(result.get("state")))status.setText("Не отправлено: "+("daily_quota".equals(result.get("reason"))?"достигнут суточный лимит":"проверьте вход, права и актуальность черновика"));else if("delivery_pending".equals(result.get("state")))status.setText("Отправка продолжается в фоне; агент проверит исходный отчёт.");else if("delivery_unknown".equals(result.get("state")))status.setText("Ответ GitHub не подтверждён. Повтор не выполняется; агент может проверить исходный отчёт.");else status.setText("Отправка не выполнена: "+result.getOrDefault("state","cancelled"));status.getParent().layout(true,true);}));
 });});}
 void save()throws Exception{if(!policyEdited)return;String selected=mode(policy.getSelectionIndex());if(selected.equals("auto_sanitized")&&!original.equals(selected)&&!MessageDialog.openConfirm(status.getShell(),"Автоматически отправлять технические отчёты?","Для аккаунта @"+expectedLogin+": разрешить агентам публиковать только очищенные технические коды без отдельного вопроса (до 5 отчётов в сутки)? Произвольные заголовки и описания всё равно требуют проверки текста и нажатия «Отправить»."))throw new IllegalStateException("Автоматическая отправка не разрешена");try{BugReportService.workspace().policyFromUserInterface(selected,expectedAccount,expectedRevision);original=selected;policyEdited=false;refresh();}catch(IllegalStateException changed){policyEdited=false;refresh();throw new IllegalStateException("Настройка GitHub изменилась. Выберите режим заново для текущего аккаунта.");}}
}
