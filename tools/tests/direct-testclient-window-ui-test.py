# SPDX-License-Identifier: AGPL-3.0-only
"""Native-UI ownership, provenance and unknown outcomes; no Windows input is sent."""
from pathlib import Path
import copy, sys, time, unittest
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).parents[2]/"bundles/com.codepilot1c.core/resources/test-client"))
from window_ui import WindowEngine, Unknown, NotStarted, dialog_identity

class Rejected(ValueError): pass

def reference(menu=False,dialog=False):
    frame={"window":{"Handle":"0x10","Process":12,"Thread":33,"Owner":"0x0","Class":"V8Window0.main"},
           "root_rid":"root","caption_rid":"caption","header_rid":"header"}
    return dict(frame=frame,menu=(dict(window=dict(Handle="0x20",Process=12,Thread=33,Owner="0x10",Class="V8Window20000.0",X=10,Y=10,Width=100,Height=100),runtime_id="menu-a",items=[dict(name="Visible item",enabled=True,runtime_id="item-a")]) if menu else None),
                dialog=(dict(window=dict(Handle="0x30",Process=12,Thread=33,Owner="0x10",Class="V8Window0.dialog"),runtime_id="dialog-a") if dialog else None))

class Worker:
    def __init__(self):
        self.pid=12;self.process_started_at="2026-11-17T00:00:00Z";self.deadline=time.monotonic()+60
        self.dispatched=False;self.snapshots={};self.current_operation_id="original-operation";self.window_ui_failure=None
        self.original=({"key":"source-key"},{"handle":"source-form"},{"id":"source-id","title":"Own form","form_name":"Catalog.Own.Form.Main"})
        self.active=copy.deepcopy(self.original)
    def form(self):return copy.deepcopy(self.active)
    def identity(self):pass
    def dialog(self,id="dialog-id"):
        self.active=({"key":id},None,{"id":id,"title":"Настройка формы","form_name":None})
    def ordinary(self):
        w,f,p=self.form();self.snapshots["ordinary"]={"signature":(w["key"],(f or {}).get("handle"),p["title"])}
        return dict(success=True,snapshot_id="ordinary",window=p)
    def wait_capture(self,request,expect,timeout,expected_form):
        r=self.ordinary();r["success"]=r["window"]["title"]==expect["window"] and r["window"]["form_name"]==expected_form
        return r

class Helper:
    def __init__(self,w):self.w=w;self.current=reference();self.calls=[];self.apply_error=None;self.after_apply=None
    def __call__(self,request,timeout):
        self.calls.append(copy.deepcopy(request));mode=request["mode"]
        if mode!="probe":
            if self.apply_error:raise self.apply_error
            if request["reference"]!=self.current:
                return dict(success=False,action_dispatched=False,reason="ui_reference_changed",phase="probe")
            if mode=="open":self.current=reference(menu=True)
            else:
                old=copy.deepcopy(self.current);self.current=reference()
                if request["op"]=="dialog":self.w.active=copy.deepcopy(self.w.original)
            if self.after_apply:self.after_apply(self)
            result_ref=self.current if mode=="open" or request["op"]=="menu" else old
        else:result_ref=self.current
        return dict(success=True,action_dispatched=mode!="probe",reference=copy.deepcopy(result_ref),foreground_changed=False)

class Contract(unittest.TestCase):
    def initial(self):
        w=Worker();h=Helper(w);e=WindowEngine(w,Rejected,h);s=e.capture({"view":"menu"},*w.form());return w,h,e,s
    def request(self,s,action="menu_open"):
        return dict(action=action,snapshot_id=s["snapshot_id"],ui_backend="window_message_click" if action=="menu_open" else "window_message_close")
    def run_action(self,e,w,s,action="menu_open"):
        return e.run(self.request(s,action),w.snapshots[s["snapshot_id"]],*w.form())
    def bound_dialog(self):
        w=Worker();h=Helper(w);e=WindowEngine(w,Rejected,h)
        source=dict(title=w.original[2]["title"],form_name=w.original[2]["form_name"],signature=("source-key","source-form","Own form"),element="ФормаИзменитьФорму",operation_id="command-op")
        w.dialog();h.current=reference(dialog=True);assert e.bind_dialog(source,w.ordinary())
        s=e.capture({"view":"system_dialog"},*w.form());return w,h,e,s
    def test_open_proves_final_same_menu_with_one_input(self):
        w,h,e,s=self.initial();r=self.run_action(e,w,s);self.assertTrue(r["postcondition_verified"]);self.assertTrue(r["native_ui"]["header_menu_proven"]);self.assertEqual(["probe","open","probe"],[c["mode"] for c in h.calls])
    def test_initial_header_discovery_is_not_menu_visibility_proof(self):
        w,h,e,s=self.initial();self.assertFalse(s["native_ui"]["header_menu_open"]);self.assertFalse(s["native_ui"]["header_menu_proven"]);self.assertEqual([],s["native_ui"]["items"])
    def test_stale_reference_rejects_before_native_input(self):
        w,h,e,s=self.initial();h.current["frame"]["header_rid"]="replacement"
        with self.assertRaises(Rejected):self.run_action(e,w,s)
        self.assertFalse(w.dispatched);self.assertEqual("ui_reference_changed",w.window_ui_failure["reason"])
    def test_open_then_menu_disappears_cannot_report_success(self):
        w,h,e,s=self.initial();original=h.__class__.__call__
        def helper(request,timeout):
            if request["mode"]=="probe" and w.dispatched:h.current=reference()
            return original(h,request,timeout)
        e.helper=helper
        with self.assertRaisesRegex(Rejected,"disappeared"):self.run_action(e,w,s)
        self.assertTrue(w.dispatched)
    def test_close_then_another_menu_cannot_report_success(self):
        w,h,e,s=self.initial();opened=self.run_action(e,w,s);s=opened
        def replacement(helper):
            helper.current=reference(menu=True);helper.current["menu"]["runtime_id"]="replacement"
        h.after_apply=replacement
        with self.assertRaises(Rejected):self.run_action(e,w,s,"menu_close")
        self.assertTrue(w.dispatched)
    def test_close_only_worker_opened_menu(self):
        w,h,e,s=self.initial();h.current=reference(menu=True);s=e.capture({"view":"menu"},*w.form())
        self.assertFalse(s["native_ui"]["header_menu_proven"])
        with self.assertRaisesRegex(Rejected,"opened by this worker"):self.run_action(e,w,s,"menu_close")
        self.assertFalse(w.dispatched)
    def test_open_and_close_restore_source_and_clear_ownership(self):
        w,h,e,s=self.initial();opened=self.run_action(e,w,s);r=self.run_action(e,w,opened,"menu_close");self.assertTrue(r["postcondition_verified"]);self.assertFalse(r["native_ui"]["header_menu_open"]);self.assertIsNone(w.owned_header_menu)
    def test_lost_helper_reply_preserves_unknown_no_fallback(self):
        w,h,e,s=self.initial();h.apply_error=Unknown("lost helper reply")
        with self.assertRaises(Rejected):self.run_action(e,w,s)
        self.assertTrue(w.dispatched);self.assertEqual(["probe","open"],[c["mode"] for c in h.calls]);self.assertEqual("unknown",w.window_ui_failure["action_dispatch_state"])
    def test_proven_helper_not_started_does_not_invent_input(self):
        w,h,e,s=self.initial();h.apply_error=NotStarted("ownership failed")
        with self.assertRaises(Rejected):self.run_action(e,w,s)
        self.assertFalse(w.dispatched)
    def test_no_open_command_cannot_adopt_later_same_title_dialog(self):
        w=Worker();h=Helper(w);e=WindowEngine(w,Rejected,h)
        source=dict(title="Own form",form_name="Catalog.Own.Form.Main",signature=("source-key","source-form","Own form"),element="ФормаИзменитьФорму",operation_id="command-op")
        self.assertFalse(e.bind_dialog(source,w.ordinary()));w.dialog();h.current=reference(dialog=True)
        with self.assertRaises(Rejected):e.capture({"view":"system_dialog"},*w.form())
        self.assertEqual([],h.calls)
    def test_later_same_title_dialog_with_other_testclient_identity_is_rejected(self):
        w,h,e,s=self.bound_dialog();w.dialog("dialog-b");calls=len(h.calls)
        with self.assertRaises(Rejected):e.capture({"view":"system_dialog"},*w.form())
        self.assertEqual(calls,len(h.calls));self.assertIsNone(w.window_ui_source)
    def test_reused_testclient_id_with_other_native_instance_is_rejected(self):
        w,h,e,s=self.bound_dialog();h.current["dialog"]["runtime_id"]="dialog-b"
        with self.assertRaisesRegex(Rejected,"Original system dialog"):e.capture({"view":"system_dialog"},*w.form())
        self.assertIsNone(w.window_ui_source);self.assertFalse(w.dispatched)
    def test_system_close_requires_recorded_return_form_and_restores_same_instance(self):
        w,h,e,s=self.bound_dialog();r=self.request(s,"system_dialog_close");r["return_form"]=w.original[2]["form_name"]
        observed=e.run(r,w.snapshots[s["snapshot_id"]],*w.form());self.assertTrue(observed["postcondition_verified"]);self.assertEqual("request_close_not_discard",observed["close_semantics"]);self.assertIsNone(w.window_ui_source)
    def test_wrong_return_form_is_rejected_before_close(self):
        w,h,e,s=self.bound_dialog();r=self.request(s,"system_dialog_close");r["return_form"]="Other.Form"
        with self.assertRaises(Rejected):e.run(r,w.snapshots[s["snapshot_id"]],*w.form())
        self.assertFalse(w.dispatched)
    def test_security_dialog_cannot_be_closed(self):
        w,h,e,s=self.bound_dialog();w.active[2]["title"]="Предупреждение безопасности"
        with self.assertRaises(Rejected):e.capture({"view":"system_dialog"},*w.form())
        self.assertFalse(w.dispatched)
    def test_budget_rejection_happens_before_helper(self):
        w=Worker();h=Helper(w);e=WindowEngine(w,Rejected,h);w.deadline=time.monotonic()+5
        with self.assertRaises(Rejected):e.capture({"view":"menu"},*w.form())
        self.assertEqual([],h.calls);self.assertFalse(w.dispatched)

if __name__=="__main__":unittest.main()
