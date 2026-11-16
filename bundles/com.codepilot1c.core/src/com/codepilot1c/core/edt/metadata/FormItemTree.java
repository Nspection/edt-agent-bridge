// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.metadata;

import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.dt.form.model.FormItem;
import com._1c.g5.v8.dt.form.model.FormItemContainer;
import com._1c.g5.v8.dt.mcore.NamedElement;

/** All contained form items, including command bars; never follows cross references. */
final class FormItemTree {
    private FormItemTree() {}
    static java.util.List<FormItem> children(EObject root) {
        return root == null ? java.util.List.of() : root.eContents().stream().filter(FormItem.class::isInstance).map(FormItem.class::cast).toList();
    }
    static FormItem find(FormItemContainer root, Integer id, String name) {
        if (root == null) return null;
        var contents = root.eAllContents();
        while (contents.hasNext()) {
            EObject object = contents.next();
            if (object instanceof FormItem item
                    && ((id != null && item.getId() == id)
                    || (name != null && object instanceof NamedElement named
                        && name.equalsIgnoreCase(named.getName())))) return item;
        }
        return null;
    }
    static FormItemContainer parent(FormItemContainer root, FormItem item) {
        if (root == null || item == null) return null;
        EObject current = item;
        while (current != null && current != root) current = current.eContainer();
        if (current != root) return null;
        return item.eContainer() instanceof FormItemContainer parent && parent.getItems().contains(item)
                ? parent : null;
    }
    static int nextId(FormItemContainer root) {
        int maximum = 0;
        var contents = root.eAllContents();
        while (contents.hasNext()) if (contents.next() instanceof FormItem item) maximum = Math.max(maximum, item.getId());
        return Math.addExact(maximum, 1);
    }
}
