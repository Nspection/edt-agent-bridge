package com.codepilot1c.core.edt.compare;
import org.eclipse.emf.ecore.EObject;
import com._1c.g5.v8.dt.moxel.content.LocalString;
/** Native MXL clipboard normalizes away nonempty neutral strings; fail before Copy/Cut. */
public final class SpreadsheetClipboardSafety {
    private SpreadsheetClipboardSafety(){}
    public static boolean hasNeutralText(EObject root) {
        if(neutral(root))return true;
        var contents=root.eAllContents();while(contents.hasNext())if(neutral(contents.next()))return true;
        return false;
    }
    private static boolean neutral(EObject value){if(!(value instanceof LocalString s))return false;String text=s.getContent().get("#");return text!=null&&!text.isEmpty();}
}
