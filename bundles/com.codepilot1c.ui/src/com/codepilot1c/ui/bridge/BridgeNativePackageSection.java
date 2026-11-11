// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import java.util.Map;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import com.codepilot1c.core.edt.development.NativePackageManager;

/** Thin view over the same backend used by MCP. No network/disk verification on SWT. */
final class BridgeNativePackageSection {
 private final Button automatic;private boolean dirty;
 BridgeNativePackageSection(Composite parent){
  var service=NativePackageManager.getInstance();var group=new Group(parent,SWT.NONE);group.setText("Компонента Native");group.setLayout(new GridLayout(2,false));group.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));
  automatic=new Button(group,SWT.CHECK);automatic.setText("Получать совместимый Native ZIP после запуска и обновления плагина");automatic.setSelection(service.automatic());automatic.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));
  automatic.addListener(SWT.Selection,event->dirty=true);
  var status=new Label(group,SWT.WRAP);var data=new GridData(SWT.FILL,SWT.CENTER,true,false,2,1);data.widthHint=420;status.setLayoutData(data);
  var retry=new Button(group,SWT.PUSH);retry.setText("Подготовить / повторить загрузку");retry.setLayoutData(new GridData(SWT.LEFT,SWT.CENTER,false,false,2,1));
  retry.addListener(SWT.Selection,event->{try{service.prepare();}catch(RuntimeException unavailable){status.setText("Не удалось поставить загрузку в очередь. Повторите позже.");group.layout(true,true);}});
  var display=parent.getDisplay();AutoCloseable listener=service.listen(value->BridgeUiDispatch.enqueue(display::isDisposed,display::asyncExec,()->{if(status.isDisposed())return;if(!dirty&&value.get("automatic_download") instanceof Boolean enabled)automatic.setSelection(enabled);status.setText(text(value));retry.setEnabled(!"preparing".equals(value.get("state")));status.setToolTipText(value.get("native_path") instanceof String path?path:null);group.layout(true,true);}));
  group.addListener(SWT.Dispose,event->{try{listener.close();}catch(Exception ignored){}});
 }
 static String text(Map<String,Object> value){
  if(Boolean.TRUE.equals(value.get("ready")))return "Совместимый ZIP проверен и готов. Установка компоненты в 1С выполняется при запуске своей обработки.";
  if("preparing".equals(value.get("state")))return "Подготовка Native ZIP: "+value.getOrDefault("stage","в очереди")+"…";
  if("failed".equals(value.get("state")))return "Native ZIP пока не готов: "+value.getOrDefault("reason","загрузка недоступна")+". Проверьте сеть и повторите загрузку.";
  return "Native ZIP ещё не проверен. Загрузка и проверка выполняются в фоне.";
 }
 void save()throws Exception {var service=NativePackageManager.getInstance();if(dirty){service.configure(automatic.getSelection());dirty=false;}}
}
