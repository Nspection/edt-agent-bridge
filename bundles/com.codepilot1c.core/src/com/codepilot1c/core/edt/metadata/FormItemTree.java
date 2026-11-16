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
        if (root == null || id == null && (name == null || name.isBlank())) return null;
        FormItem match = null;
        var contents = root.eAllContents();
        while (contents.hasNext()) {
            EObject object = contents.next();
            if (!(object instanceof FormItem item)) continue;
            boolean found = (id == null || item.getId() == id)
                    && (name == null || object instanceof NamedElement named && name.equalsIgnoreCase(named.getName()));
            if (found) {
                if (match != null) throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE,
                        "Ambiguous form item selector; use an exact unique name", false);
                match = item;
            }
        }
        return match;
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
    record AllocationSnapshot(java.util.IdentityHashMap<FormItem,Integer> ids,java.util.IdentityHashMap<FormItem,String> names) {}
    private static String normalizedName(FormItem item) {return item.getName()==null ? "" : item.getName().toLowerCase(java.util.Locale.ROOT);}
    static AllocationSnapshot snapshot(FormItemContainer root) {
        var ids = new java.util.IdentityHashMap<FormItem,Integer>();var names = new java.util.IdentityHashMap<FormItem,String>();
        var contents = root.eAllContents();
        while (contents.hasNext()) if (contents.next() instanceof FormItem item) {ids.put(item,item.getId());names.put(item,normalizedName(item));}
        return new AllocationSnapshot(ids,names);
    }
    static void verifyAllocation(FormItemContainer root, AllocationSnapshot before) {
        var live = snapshot(root).ids();
        var ids = new java.util.HashMap<Integer, java.util.List<FormItem>>();
        for (var entry : live.entrySet()) {
            var item = entry.getKey();int id = entry.getValue();
            if (before.ids().containsKey(item) && before.ids().get(item).intValue() != id)
                throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, "Existing form item ID changed unexpectedly", false);
            if (!before.ids().containsKey(item) && (id < 0 && !(item instanceof com._1c.g5.v8.dt.form.model.AutoCommandBar && id == -1)
                    || id == 0 && item.getName() != null && !item.getName().isBlank()))
                throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, "New named form item has no valid native ID", false);
            if (id > 0) ids.computeIfAbsent(id, key -> new java.util.ArrayList<>()).add(item);
        }
        for (var group : ids.values()) if (group.size() > 1 && group.stream().anyMatch(item -> !before.ids().containsKey(item)))
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, "Native form allocation produced a duplicate item ID; transaction rejected", false);
        var names = new java.util.HashMap<String, java.util.List<FormItem>>();
        for (var item : live.keySet()) if (item.getName() != null && !item.getName().isBlank())
            names.computeIfAbsent(item.getName().toLowerCase(java.util.Locale.ROOT), key -> new java.util.ArrayList<>()).add(item);
        for (var group : names.values()) if (group.size() > 1 && group.stream().anyMatch(item -> !before.ids().containsKey(item) || !normalizedName(item).equals(before.names().get(item))))
            throw new MetadataOperationException(MetadataOperationCode.INVALID_METADATA_CHANGE, "Native form allocation produced a duplicate item name; transaction rejected", false);
    }
}
