// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;
import org.eclipse.jface.preference.PreferencePage;
import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.*;
import org.eclipse.swt.widgets.*;
import org.eclipse.swt.dnd.*;
import org.eclipse.ui.*;
import com.codepilot1c.core.mcp.host.*;
/** Single connection page for external MCP agents; contains no account login. */
public final class BridgePreferencePage extends PreferencePage implements IWorkbenchPreferencePage {
 private McpHostConfig config;private Button enabled;private Spinner port;private Text token,tools;private Label status;private BridgeUpdateSection updates;private BridgeNativePackageSection nativePackage;
 public void init(IWorkbench workbench){config=McpHostConfigStore.getInstance().load();}
 protected Control createContents(Composite parent){
  if(config==null)config=McpHostConfigStore.getInstance().load();
  Composite body=new Composite(parent,SWT.NONE);body.setLayout(new GridLayout(2,false));
  Label intro=new Label(body,SWT.WRAP);intro.setText("MCP-сервер для внешних ИИ-агентов. Подключение локальное; вход в аккаунт и ключи LLM не требуются.");intro.setLayoutData(new GridData(SWT.FILL,SWT.TOP,true,false,2,1));
  enabled=new Button(body,SWT.CHECK);enabled.setText("Запускать MCP-сервер");enabled.setSelection(config.isEnabled());enabled.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false,2,1));
  label(body,"Адрес");label(body,"127.0.0.1 (только этот компьютер)");
  label(body,"Порт");port=new Spinner(body,SWT.BORDER);port.setMinimum(1024);port.setMaximum(65535);port.setSelection(config.getPort());
  label(body,"Токен MCP");token=new Text(body,SWT.BORDER|SWT.PASSWORD);token.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));token.setText(config.getBearerToken());
  label(body,"Инструменты");tools=new Text(body,SWT.BORDER);tools.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));tools.setText(config.getExposedToolsFilter());tools.setToolTipText("* — все инструменты EDT. Имена через запятую; -имя исключает инструмент.");
  label(body,"Состояние");status=new Label(body,SWT.WRAP);status.setLayoutData(new GridData(SWT.FILL,SWT.CENTER,true,false));refresh();
  Button copy=new Button(body,SWT.PUSH);copy.setText("Копировать конфигурацию MCP");copy.addListener(SWT.Selection,e->{var clipboard=new Clipboard(body.getDisplay());try{String json=new com.google.gson.GsonBuilder().setPrettyPrinting().create().toJson(java.util.Map.of("mcpServers",java.util.Map.of("edt-agent-bridge",java.util.Map.of("type","http","url","http://127.0.0.1:"+port.getSelection()+"/mcp","headers",java.util.Map.of("Authorization","Bearer "+token.getText())))));clipboard.setContents(new Object[]{json},new Transfer[]{TextTransfer.getInstance()});}finally{clipboard.dispose();}});
  Button check=new Button(body,SWT.PUSH);check.setText("Обновить состояние");check.addListener(SWT.Selection,e->refresh());
  updates=new BridgeUpdateSection(body);
  nativePackage=new BridgeNativePackageSection(body);
  new BridgePythonSection(body);
  new BridgeVanessaSection(body);
  return body;
 }
 private void label(Composite body,String text){new Label(body,SWT.NONE).setText(text);}
 private void refresh(){status.setText(McpHostManager.getInstance().isRunning()?"Работает":"Остановлен");status.getParent().layout();}
 public boolean performOk(){
  if(token.getText().isBlank()){setErrorMessage("Задайте непустой токен MCP");return false;}
  try{updates.save();nativePackage.save();boolean changed=config.isEnabled()!=enabled.getSelection()||config.getPort()!=port.getSelection()||!config.getBearerToken().equals(token.getText())||!config.getExposedToolsFilter().equals(tools.getText());if(changed){config.setEnabled(enabled.getSelection());config.setHttpEnabled(true);config.setBindAddress("127.0.0.1");config.setPort(port.getSelection());config.setBearerToken(token.getText());config.setAuthMode(McpHostConfig.AuthMode.BEARER_ONLY);config.setLlmEnabled(false);config.setExposedToolsFilter(tools.getText());McpHostConfigStore.getInstance().save(config);McpHostManager.getInstance().restart();}setErrorMessage(null);return true;}catch(Exception e){setErrorMessage(e.getMessage());return false;}
 }
}
