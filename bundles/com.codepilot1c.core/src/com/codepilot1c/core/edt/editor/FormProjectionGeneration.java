// SPDX-License-Identifier: AGPL-3.0-only
package com.codepilot1c.core.edt.editor;

import java.util.*;
import java.util.concurrent.atomic.AtomicBoolean;

/** One causal native rebuild, independent of timers, pixel equality or mutable BM references. */
final class FormProjectionGeneration {
    interface Backend {
        boolean available();boolean dirty();boolean busy();
        Object editor();Object input();String fingerprint()throws Exception;
        Object session();Object layout();boolean installed(Object session,Object layout)throws Exception;
        void rebuild(Object mapping)throws Exception;
    }
    record Receipt(String requestId,String modelSha256,Object editor,Object input,Object session,Object layout) {
        boolean current(Backend backend)throws Exception {
            return backend.available()&&!backend.dirty()&&editor==backend.editor()&&input==backend.input()
                &&session==backend.session()&&layout==backend.layout()&&modelSha256.equals(backend.fingerprint())
                &&backend.installed(session,layout);
        }
    }
    static final class Unverified extends IllegalStateException {
        final String reason;
        Unverified(String reason){super(reason);this.reason=reason;}
    }
    private final String requestId=UUID.randomUUID().toString(),modelHash;
    private final Object editor,input;
    private final AtomicBoolean dispatched=new AtomicBoolean();
    FormProjectionGeneration(Backend backend,String expected)throws Exception {
        if(!backend.available())throw new Unverified("projection_unavailable");
        if(backend.dirty())throw new Unverified("dirty_editor_preserved");
        editor=backend.editor();input=backend.input();modelHash=backend.fingerprint();
        if(expected!=null&&!expected.equals(modelHash))throw new Unverified("model_precondition_changed");
    }
    boolean busy(Backend backend){return backend.busy();}
    Receipt complete(Backend backend,Object mapping)throws Exception {
        if(dispatched.get())throw new Unverified("refresh_already_dispatched");
        if(!backend.available()||backend.editor()!=editor||backend.input()!=input)throw new Unverified("editor_or_input_changed");
        if(backend.dirty())throw new Unverified("dirty_editor_preserved");
        if(!modelHash.equals(backend.fingerprint()))throw new Unverified("model_changed_before_refresh");
        if(backend.busy())throw new Unverified("native_rebuild_busy");
        if(!dispatched.compareAndSet(false,true))throw new Unverified("refresh_already_dispatched");
        Object previousSession=backend.session(),previousLayout=backend.layout();
        backend.rebuild(mapping);
        Object session=backend.session(),layout=backend.layout();
        if(session==null||layout==null||session==previousSession||layout==previousLayout)throw new Unverified("native_generation_not_replaced");
        Receipt receipt=new Receipt(requestId,modelHash,editor,input,session,layout);
        if(!receipt.current(backend))throw new Unverified("native_generation_or_model_changed");
        return receipt;
    }
}
