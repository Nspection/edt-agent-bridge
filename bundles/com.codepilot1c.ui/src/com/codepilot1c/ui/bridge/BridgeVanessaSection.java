// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import com.codepilot1c.core.edt.development.DependencyPackages;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** Shared package-only preparation; no automatic manager/client/database changes. */
final class BridgeVanessaSection {
 private final Display display;private final Label status;private final Button prepare;private String job;
 BridgeVanessaSection(Composite parent){
  display=parent.getDisplay();Group group=new Group(parent,SWT.NONE);group.setText("Vanessa Automation");group.setLayout(new GridLayout(2,false));group.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));
  status=new Label(group,SWT.WRAP);status.setText("Проверка пакета…");var data=new GridData(SWT.FILL,SWT.CENTER,true,false);data.widthHint=400;status.setLayoutData(data);
  prepare=new Button(group,SWT.PUSH);prepare.setText("Подготовить пакет Vanessa");prepare.addListener(SWT.Selection,e->launch());
  Label note=new Label(group,SWT.WRAP);note.setText("Загружаются проверенные EPF, VAExtension и client_mcp. Профиль, отдельная база менеджера, установка расширений и доверие настраиваются отдельно через MCP.");note.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));refresh();
 }
 private void ui(Runnable task){BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!status.isDisposed())task.run();});}
 private void text(String value){status.setText(value);status.getParent().layout(true,true);}
 private void refresh(){CompletableFuture.runAsync(()->{try{var s=DependencyPackages.status("vanessa");ui(()->{prepare.setEnabled(true);text(Boolean.TRUE.equals(s.get("ready"))?"Пакет готов; готовность менеджера проверяется отдельно":"Пакет не готов. Подготовьте его или проверьте package_status в MCP.");});}catch(Exception error){ui(()->{prepare.setEnabled(true);text("Не удалось проверить пакет. Диагностика доступна через MCP.");});}});}
 private void launch(){prepare.setEnabled(false);text("Получение и проверка пакета…");CompletableFuture.runAsync(()->{try{var r=DependencyPackages.prepare("vanessa");job=(String)r.get("job_id");ui(this::poll);}catch(Exception error){ui(()->{prepare.setEnabled(true);text("Не удалось начать подготовку; проверьте исходное задание через MCP.");});}});}
 private void poll(){if(status.isDisposed())return;var r=BackgroundJobRegistry.getInstance().getStatus(job);if(r.isEmpty()){prepare.setEnabled(true);text("Задание недоступно; проверьте package_status через MCP.");return;}String state=r.get().getState().name();if(Set.of("DONE","FAILED").contains(state)){if(state.equals("DONE"))refresh();else{prepare.setEnabled(true);text("Подготовка завершилась ошибкой. Проверьте исходный job_id через MCP.");}return;}display.timerExec(1000,this::poll);}
}
