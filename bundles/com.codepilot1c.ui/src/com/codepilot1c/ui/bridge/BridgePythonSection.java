// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;
import java.util.*;
import java.util.concurrent.CompletableFuture;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import com.codepilot1c.core.edt.development.PythonEnvironmentService;
import com.codepilot1c.core.tools.workspace.BackgroundJobRegistry;
/** Uses the same managed environment preparation as MCP, never shell pip. */
final class BridgePythonSection {
 private final Display display;private final Label status;private final Button prepare;private String job;
 BridgePythonSection(Composite parent){
  display=parent.getDisplay();
  Group group=new Group(parent,SWT.NONE);group.setText("Python / прямой TestClient");group.setLayout(new GridLayout(2,false));group.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));
  status=new Label(group,SWT.WRAP);status.setText("Проверка окружения…");var data=new GridData(SWT.FILL,SWT.CENTER,true,false);data.widthHint=400;status.setLayoutData(data);
  prepare=new Button(group,SWT.PUSH);prepare.setText("Подготовить Python и TestClient");prepare.addListener(SWT.Selection,e->launch());
  Label note=new Label(group,SWT.WRAP);note.setText("Используется совместимый Python на ПК; при отсутствии загружается собственный CPython. Системная установка, PATH и база не меняются.");note.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));refresh();
 }
 private void ui(Runnable task){BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(!status.isDisposed())task.run();});}
 private void text(String message){status.setText(message);status.getParent().layout(true,true);}
 private void refresh(){CompletableFuture.runAsync(()->{try{var s=PythonEnvironmentService.workspace().inspect(null);ui(()->{prepare.setEnabled(true);text(Boolean.TRUE.equals(s.get("ready"))?"Окружение TestClient готово":"Окружение: "+s.get("state"));});}catch(Exception error){ui(()->{prepare.setEnabled(true);text("Не удалось проверить окружение. Повторите подготовку или используйте MCP environment_status.");});}});}
 private void launch(){prepare.setEnabled(false);text("Подготовка окружения…");CompletableFuture.runAsync(()->{try{var r=PythonEnvironmentService.workspace().prepareJob(Map.of());job=(String)r.get("job_id");ui(this::poll);}catch(Exception error){ui(()->{prepare.setEnabled(true);text("Подготовка недоступна. Проверьте исходное задание через MCP.");});}});}
 private void poll(){if(status.isDisposed())return;var r=BackgroundJobRegistry.getInstance().getStatus(job);if(r.isEmpty()){prepare.setEnabled(true);text("Задание недоступно; проверьте environment_status через MCP.");return;}String state=r.get().getState().name();if(Set.of("DONE","FAILED").contains(state)){if(state.equals("DONE"))refresh();else{prepare.setEnabled(true);text("Подготовка завершилась ошибкой. Диагностика доступна по исходному job_id в MCP.");}return;}status.getDisplay().timerExec(1000,this::poll);}
}
