// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import static org.junit.Assert.*;
import org.junit.Test;
import java.util.Map;
public class NativeHostVerificationTest {
    @Test public void sameConnectionWithDifferentUuidCannotCertifyCleanup(){
        try{NativeHostVerification.requireSameBase("owned-uuid","connection",Map.of("base_uuid","other-uuid","base_identity_sha256","connection"));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void reusedUuidWithDifferentConnectionCannotCertifyCleanup(){
        try{NativeHostVerification.requireSameBase("owned-uuid","original",Map.of("base_uuid","owned-uuid","base_identity_sha256","replacement"));fail();}catch(IllegalArgumentException expected){}
    }
    @Test public void missingIdentityCannotCertifyCleanup(){
        try{NativeHostVerification.requireSameBase("","",Map.of("base_uuid","","base_identity_sha256",""));fail();}catch(IllegalArgumentException expected){}
        NativeHostVerification.requireSameBase("uuid","hash",Map.of("base_uuid","uuid","base_identity_sha256","hash"));
    }
    @Test public void onlyCompletedTypedTrueAbsenceIsAccepted(){
        var positive=Map.<String,Object>of("success",true,"value",true);
        for(String state:new String[]{"queued","delivered","failed","cancelled","outcome_unknown"}){
            try{NativeHostVerification.requireAbsent(Map.of("terminal",true,"state",state,"execution",positive));fail(state);}catch(IllegalStateException expected){}
        }
        NativeHostVerification.requireAbsent(Map.of("terminal",true,"state","completed","execution",positive));
        try{NativeHostVerification.requireAbsent(Map.of("terminal",false,"state","completed","execution",positive));fail();}catch(IllegalStateException expected){}
    }
    @Test public void malformedFailedOrPresentResultsCannotCertifyCleanup(){
        for(Object execution:new Object[]{"true",Map.of("success",false,"value",true),Map.of("success",true,"value",false),Map.of("success",true,"value","true"),Map.of("value",true)}){
            try{NativeHostVerification.requireAbsent(Map.of("terminal",true,"state","completed","execution",execution));fail();}catch(IllegalStateException expected){}
        }
    }
}
