// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.compare;
import java.util.*;
import org.eclipse.ui.*;
import org.eclipse.jface.resource.ImageDescriptor;
import com._1c.g5.v8.dt.compare.core.IComparisonSession;
/** Immutable source snapshots and guarded save callback, never a source IFile. */
public final class SpreadsheetMergeInput implements IEditorInput {
    public final IComparisonSession session;
    public final long nodeId;
    public final Map<String,String> sides;
    public final String initialResult;
    public String expectedHash;
    public SpreadsheetMergeInput(IComparisonSession s,long n,Map<String,String> sides,String result,String hash){session=s;nodeId=n;this.sides=Collections.unmodifiableMap(new LinkedHashMap<>(sides));initialResult=result;expectedHash=hash;}
    @Override public boolean exists(){return true;}
    @Override public ImageDescriptor getImageDescriptor(){return null;}
    @Override public String getName(){return "Табличное объединение";}
    @Override public String getToolTipText(){return "Результат сохраняется в план сравнения; проект изменится после объединения EDT";}
    @Override public IPersistableElement getPersistable(){return null;}
    @Override public <T>T getAdapter(Class<T> type){return null;}
    @Override public boolean equals(Object o){return o instanceof SpreadsheetMergeInput i && i.session==session && i.nodeId==nodeId;}
    @Override public int hashCode(){return System.identityHashCode(session)*31+Long.hashCode(nodeId);}
}
