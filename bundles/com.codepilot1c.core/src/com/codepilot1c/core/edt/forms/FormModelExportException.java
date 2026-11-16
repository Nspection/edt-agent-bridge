// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.forms;
import java.util.Map;
import com.codepilot1c.core.edt.metadata.*;
/** The BM write committed; an unverified export must never replay the mutation. */
public final class FormModelExportException extends MetadataOperationException {
 private static final long serialVersionUID=1L;
 private final Map<String,Object> outcome;
 public FormModelExportException(String project,String form,String operation,int applied,Throwable cause){super(MetadataOperationCode.EDT_TRANSACTION_FAILED,"Form BM changes committed; source export is not verified. Inspect the saved form/source before further edits; do not replay the mutation.",false,cause);outcome=Map.of("project",project,"form_fqn",form,"operation_id",operation,"operations_applied",applied,"state","partial","bm_committed",true,"export_verified",false,"retry_safe",false,"automatic_replay",false,"next_action","inspect_form_and_source_do_not_replay");}
 public Map<String,Object> outcome(){return outcome;}
}
