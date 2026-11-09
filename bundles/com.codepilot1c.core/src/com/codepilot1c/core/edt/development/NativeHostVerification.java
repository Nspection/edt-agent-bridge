// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.development;
import java.util.Map;
/** Verification cannot be transferred across bases or inferred from a queued/unknown request. */
final class NativeHostVerification {
    private NativeHostVerification(){}
    static void requireSameBase(String uuid,String identity,Map<String,Object> target){
        if(uuid==null||uuid.isBlank()||identity==null||identity.isBlank()||!uuid.equals(target.get("base_uuid"))||!identity.equals(target.get("base_identity_sha256")))throw new IllegalArgumentException("Cleanup verifier belongs to another base or lacks identity proof");
    }
    static void requireAbsent(Map<String,Object> result){
        if(!Boolean.TRUE.equals(result.get("terminal"))||!"completed".equals(result.get("state"))||!(result.get("execution") instanceof Map<?,?> execution)||!Boolean.TRUE.equals(execution.get("success"))||!Boolean.TRUE.equals(execution.get("value")))throw new IllegalStateException("Temporary metadata is still present or verification outcome is unknown");
    }
}
