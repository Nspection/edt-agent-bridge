# SPDX-License-Identifier: AGPL-3.0-only
from pathlib import Path
import sys, types, unittest
from unittest.mock import patch
sys.dont_write_bytecode=True
sys.path.insert(0,str(Path(__file__).parents[2]/"bundles/com.codepilot1c.core/resources/test-client"))
from worker import Worker, Rejected
from dialogs import DialogEngine
class Fake(Worker):
 def __init__(self):
  super().__init__(); self.client=types.SimpleNamespace(_pending=0,close=lambda:None);self.mutations_blocked=True;self.effects=[];self.hidden=True;self.text="Continue editing?";self.drift=False;self.fail=False
  self.guids=types.SimpleNamespace(CURRENT_VISIBLE="visible",CURRENT_ENABLE="enabled",CLICK="click")
  self.wire=types.SimpleNamespace(decode_bool=lambda x:x)
 def identity(self):
  if self.broken:raise Rejected("broken")
 def form(self):return {"key":"window", "handle":"w-h"},{"key":"form","handle":"changed" if self.drift else "f-h"},{"form_name":"MessageBox85", "title":"Application"}
 def elements(self,form):return [{"name":"Message","key":"message","handle":"m-h","title":self.text,"class":"Decoration"}]+[dict(name="Button"+str(i),key="b"+str(i),handle="b-h"+str(i),title=t,**{"class":"Button"}) for i,t in enumerate(["Yes","No","Command2"])]
 def send(self,g,key=None,**kw):return not(key=="b2" and self.hidden) if g=="visible" else True
 def mutate(self,g,key,**kw):
  self.dispatched=True;self.effects.append(key)
  if self.fail:raise TimeoutError("lost answer")
 def bind(self):self.blocked_dialog=DialogEngine(self,Rejected).read();self.blocked_dialog["operation_id"]="o";return self
 def request(self):
  h=self.blocked_dialog["dialog_hash"];grant=dict(application_confirmation=True,base_uuid="base",blocking_operation_id="o",dialog_hash=h,button="Button0")
  return dict(operation="dialog_answer",base_uuid="base",blocking_operation_id="o",dialog_hash=h,button="Button0",authorization=grant)
class Contract(unittest.TestCase):
 def test_inspect_filters_hidden_and_exposes_no_handles(self):
  w=Fake().bind();r=DialogEngine(w,Rejected).inspect("o");self.assertEqual([b["title"] for b in r["buttons"]],["Yes","No"]);self.assertNotIn("handle",str(r));self.assertTrue(r["original_outcome_unknown"])
 def test_one_answer_preserves_barrier_and_does_not_replay(self):
  w=Fake().bind();r=w.handle(w.request());self.assertTrue(r["success"]);self.assertFalse(r["postcondition_verified"]);self.assertTrue(w.mutations_blocked);self.assertEqual(w.effects,["b0"]);self.assertFalse(w.handle(w.request())["success"]);self.assertEqual(w.effects,["b0"])
 def test_wrong_hash_or_operation_never_dispatches(self):
  for field in ["dialog_hash","blocking_operation_id"]:
   w=Fake().bind();r=w.request();r[field]="other";self.assertFalse(w.handle(r)["success"]);self.assertFalse(w.effects)
 def test_changed_dialog_never_dispatches(self):
  for change in ["text","drift","hidden"]:
   w=Fake().bind();setattr(w,change,"Other?" if change=="text" else change=="drift");self.assertFalse(w.handle(w.request())["success"]);self.assertFalse(w.effects)
 def test_pending_reply_never_resyncs(self):
  w=Fake().bind();w.client._pending=1;self.assertFalse(w.handle(w.request())["success"]);self.assertFalse(w.effects)
 def test_text_changes_during_button_flags_without_form_change(self):
  w=Fake().bind();send=w.send
  def race(*a,**kw):
   value=send(*a,**kw);w.text="Changed question?";return value
  w.send=race;self.assertFalse(w.handle(w.request())["success"]);self.assertFalse(w.effects)
 def test_missing_window_handle_is_not_a_proven_identity(self):
  w=Fake();form=w.form
  def missing():
   win,frm,public=form();win.pop("handle");return win,frm,public
  w.form=missing
  with self.assertRaises(Rejected):w.bind()
 def test_missing_authorization_never_dispatches(self):
  w=Fake().bind();r=w.request();r.pop("authorization");self.assertFalse(w.handle(r)["success"]);self.assertFalse(w.effects)
 def test_negative_answer_consumed_without_advertising_another_answer(self):
  w=Fake().bind()
  def negative(*a,**kw):w.dispatched=True;raise Rejected("complete negative answer")
  w.mutate=negative;r=w.handle(w.request());self.assertTrue(r["outcome_unknown"]);self.assertEqual(r["transport_state"],"usable");self.assertFalse(r["dialog_available"]);self.assertEqual(w.blocked_dialog["operation_id"],"o");self.assertTrue(w.blocked_dialog["answer_attempted"])
 def test_unknown_answer_is_consumed(self):
  w=Fake().bind();w.fail=True;r=w.handle(w.request());self.assertTrue(r["outcome_unknown"]);self.assertTrue(w.broken);self.assertFalse(w.handle(w.request())["success"]);self.assertEqual(w.effects,["b0"])
 def test_privileged_prompt_never_eligible(self):
  for text in ["Enter password?","Trust this EPF?","Лицензия","Разрешение безопасности"]:
   w=Fake();w.text=text
   with self.assertRaises(Rejected):w.bind()
 def test_unbound_dialog_cannot_be_answered(self):
  w=Fake();self.assertFalse(w.handle(dict(operation="dialog_inspect",blocking_operation_id="o"))["success"])
class FailureCapture(unittest.TestCase):
 def failed(self,kind="action",phase="reply_rejected",dispatched=True,error=None,capture=None):
  w=Fake();w.mutations_blocked=False
  def act(request):
   w.dispatched=dispatched;w.last_rpc=dict(kind=kind,phase=phase);raise error or Rejected("full negative")
  w.act=act
  with patch.object(DialogEngine,"read",side_effect=capture if isinstance(capture,Exception) else None,return_value=capture or dict(dialog_hash="h",text="Continue?",buttons=[])) as read:
   r=w.handle(dict(operation="action",_operation_id="original"));return w,r,read.call_count
 def test_complete_negative_binds_only_original_operation(self):
  w,r,n=self.failed();self.assertTrue(r["outcome_unknown"]);self.assertTrue(r["dialog_available"]);self.assertEqual(w.blocked_dialog["operation_id"],"original");self.assertEqual(n,1)
 def test_read_failure_never_captures(self):
  w,r,n=self.failed(kind="read",dispatched=False);self.assertFalse(r["outcome_unknown"]);self.assertFalse(r["dialog_available"]);self.assertEqual(n,0)
 def test_timeout_never_captures(self):
  w,r,n=self.failed(phase="incomplete_exchange",error=TimeoutError());self.assertTrue(r["outcome_unknown"]);self.assertTrue(w.broken);self.assertEqual(n,0)
 def test_local_not_sent_never_captures(self):
  w,r,n=self.failed(phase="not_sent",dispatched=False);self.assertFalse(r["dialog_available"]);self.assertEqual(n,0)
 def test_later_unrelated_dialog_does_not_become_bound(self):
  w,r,n=self.failed(capture=Rejected("no dialog"));self.assertFalse(r["dialog_available"]);self.assertFalse(w.handle(dict(operation="dialog_inspect",blocking_operation_id="original"))["success"])
 def test_capture_timeout_breaks_channel_preserving_original_failure(self):
  w,r,n=self.failed(capture=TimeoutError());self.assertTrue(r["outcome_unknown"]);self.assertEqual(r["diagnostics"]["last_rpc_phase"],"reply_rejected");self.assertEqual(r["transport_state"],"broken");self.assertFalse(r["dialog_available"])
if __name__=="__main__":unittest.main()
