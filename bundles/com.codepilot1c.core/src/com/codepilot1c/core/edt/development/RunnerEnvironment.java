package com.codepilot1c.core.edt.development;
import java.util.*;
/** Only OS variables needed by PowerShell and 1C cross the automation process boundary. */
public final class RunnerEnvironment {
    private RunnerEnvironment(){}
    private static final Set<String> ALLOWED=Set.of("APPDATA","LOCALAPPDATA","USERPROFILE","TEMP","TMP","SYSTEMROOT","WINDIR","COMSPEC","PATH","PROGRAMFILES","PROGRAMFILES(X86)","PROGRAMW6432","COMMONPROGRAMFILES","COMMONPROGRAMFILES(X86)","COMMONPROGRAMW6432","PROCESSOR_ARCHITECTURE","NUMBER_OF_PROCESSORS","USERNAME","USERDOMAIN","COMPUTERNAME","HOMEDRIVE","HOMEPATH","SYSTEMDRIVE","ALLUSERSPROFILE","PUBLIC","PATHEXT","LANG");
    public static Map<String,String> isolated(Map<String,String> inherited){Map<String,String> result=new LinkedHashMap<>();inherited.forEach((name,value)->{if(ALLOWED.contains(name.toUpperCase(Locale.ROOT))){result.put(name,value);}});return result;}
}
