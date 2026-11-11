// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.ui.bridge;

import org.eclipse.swt.SWT;
import org.eclipse.swt.layout.GridData;
import org.eclipse.swt.layout.GridLayout;
import org.eclipse.swt.widgets.Button;
import org.eclipse.swt.widgets.Composite;
import org.eclipse.swt.widgets.Group;
import org.eclipse.swt.widgets.Label;

/** Update controls kept separate from the MCP connection fields. */
final class BridgeUpdateSection {
    private final Button automatic;

    BridgeUpdateSection(Composite parent) {
        var service = BridgeUpdates.getInstance();
        service.start();
        Group group = new Group(parent, SWT.NONE);
        group.setText("Обновление плагина");
        group.setLayout(new GridLayout(2, false));
        group.setLayoutData(new GridData(SWT.FILL, SWT.TOP, true, false, 2, 1));
        Label version = new Label(group, SWT.NONE);
        version.setText("Установлена версия: " + service.installedVersion());
        version.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        automatic = new Button(group, SWT.CHECK);
        automatic.setText("Проверять после запуска EDT и раз в сутки");
        automatic.setSelection(service.automatic());
        automatic.setLayoutData(new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1));
        Label status = new Label(group, SWT.WRAP);
        var data = new GridData(SWT.FILL, SWT.CENTER, true, false, 2, 1);
        data.widthHint = 420;
        status.setLayoutData(data);
        Button check = new Button(group, SWT.PUSH);
        check.setText("Проверить обновления");
        check.addListener(SWT.Selection, e -> service.checkNow());
        Button install = new Button(group, SWT.PUSH);
        install.setText("Обновить плагин…");
        install.addListener(SWT.Selection, e -> service.openUpdateWizard(group.getShell()));
        AutoCloseable listener = service.listen(text -> {
            if (status.isDisposed()) return;
            status.setText(text);
            check.setEnabled(!service.checking());
            install.setEnabled(!service.updating());
            group.layout(true, true);
        });
        group.addListener(SWT.Dispose, e -> { try { listener.close(); } catch (Exception ignored) { } });
    }

    void save() throws org.osgi.service.prefs.BackingStoreException {
        BridgeUpdates.getInstance().setAutomatic(automatic.getSelection());
    }
}
