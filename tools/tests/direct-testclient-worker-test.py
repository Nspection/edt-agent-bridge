# SPDX-License-Identifier: AGPL-3.0-only
"""Transport boundaries with a fake wire, no 1C and no input on the desktop."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
from unittest.mock import patch
sys.dont_write_bytecode = True
path = Path(__file__).parents[2] / "bundles/com.codepilot1c.core/resources/test-client/worker.py"
spec = importlib.util.spec_from_file_location("direct_worker", path)
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)

class Fake(module.Worker):
    def __init__(self):
        super().__init__()
        self.title = "Card"
        self.key = "SecondaryFrame[owned]"
        self.effects = 0
        self.lose = False
        self.client = types.SimpleNamespace(close=lambda: None)
        self.guids = types.SimpleNamespace(CLICK="click", CLOSE="close", EXECUTE_COMMAND="open")
        self.wire = types.SimpleNamespace(mk_command=lambda x: x)
        self.snapshots["owned"] = {"signature": (self.key, "form-handle", self.title), "elements": self.elements(None)}
    def identity(self):
        if self.broken: raise module.Rejected("Broken")
    def form(self):
        return ({"key": self.key}, {"handle": "form-handle"}, {"title": self.title, "form_name": "Catalog.Items.Object"})
    def elements(self, form):
        return [{"key": self.key + ".Button[Save]", "handle": "owned-button", "name": "Save", "class": "Button"}]
    def mutate(self, *args, **kwargs):
        self.dispatched = True
        self.effects += 1
        if self.lose: raise OSError("reply lost after effect")
    def capture(self, request):
        return {"success": True, "window": {"title": self.title}, "values": {}}

class Contract(unittest.TestCase):
    def test_listener_preflight_never_connects_or_dispatches(self):
        worker=module.Worker()
        with patch.object(module,"port_owner",return_value=123):
            result=worker.handle(dict(action="listener",port=4242,pid=123))
        self.assertTrue(result["listening"])
        self.assertFalse(result["action_dispatched"])
        self.assertIsNone(worker.client)
    def test_unready_listener_is_safe_to_observe_without_handshake(self):
        worker=module.Worker()
        with patch.object(module,"port_owner",side_effect=module.Rejected("no owned listener")):
            result=worker.handle(dict(action="listener",port=4242,pid=123))
        self.assertTrue(result["success"])
        self.assertFalse(result["listening"])
        self.assertFalse(result["action_dispatched"])
        self.assertIsNone(worker.client)

    def request(self, **kw):
        return dict(operation="action", action="command", element="Save", expected_window="Card", snapshot_id="owned", **kw)
    def test_button_command_traverses_elements_only_once(self):
        worker = Fake()
        with patch.object(worker, "elements", wraps=worker.elements) as elements:
            result = worker.handle(self.request())
        self.assertTrue(result["success"])
        self.assertEqual(elements.call_count, 1)
        self.assertEqual(worker.effects, 1)
    def test_decoration_command_reuses_resolved_element_tree(self):
        worker = Fake()
        item = {"key":"label", "handle":"label-handle", "name":"Save", "class":"Decoration", "type":"Label"}
        worker.snapshots["owned"]["elements"] = [item]
        with patch.object(worker, "elements", return_value=[item]) as elements, patch.object(module.ControlEngine, "run", return_value={"state_verified":False,"verification":"ack_only_requires_explicit_expect"}) as run:
            result = worker.handle(self.request())
        self.assertTrue(result["success"])
        self.assertEqual(elements.call_count, 1)
        self.assertEqual(run.call_args.args[2], [item])
    def test_complete_negative_action_reply_keeps_transport_but_blocks_unknown_mutation(self):
        worker=Fake();worker.client.send_cmd=lambda *a,**k:{"ok":False};worker.send=module.Worker.send.__get__(worker)
        worker.mutate=lambda *a,**k:worker.send("toggle",kind="action")
        result=worker.handle(self.request())
        self.assertFalse(result["success"]);self.assertTrue(result["outcome_unknown"]);self.assertEqual(result["transport_state"],"usable");self.assertTrue(result["mutation_blocked"])
    def test_calendar_scalar_getter_is_rejected_before_wire(self):
        worker=module.Worker();worker.send=lambda *a,**k: (_ for _ in ()).throw(AssertionError("No unsupported getter probe"))
        with self.assertRaises(module.Rejected):worker.value({"type":"CalendarField","name":"Calendar"})
        self.assertFalse(worker.dispatched)
    def test_calendar_window_only_does_not_verify_date(self):
        worker=Fake();worker.postcondition=lambda request:{"success":True,"values":{}}
        control={"state_verified":False,"verification":"date_command_ack_only_requires_explicit_expect"}
        with patch.object(module.ControlEngine,"run",return_value=control):
            out=worker.handle(dict(operation="action",action="set_date",element="Save",date="2026-02-28",expected_window="Card",snapshot_id="owned",expect={"window":"Card"}))
        self.assertTrue(out["success"]);self.assertFalse(out["postcondition_verified"])
    def test_choice_read_variant_is_observational(self):
        worker = module.Worker();worker.client = types.SimpleNamespace(send_cmd=lambda *a, **k: {"ok":False});worker.wire=types.SimpleNamespace()
        with self.assertRaises(module.ReadNotReady):worker.send("choices",kind="read818")
        self.assertFalse(worker.dispatched)
    def test_failed_intrinsic_control_state_never_marks_postcondition_verified(self):
        worker = Fake()
        result = {"name":"Save","state_verified":False,"verification":"exact_display_value"}
        with patch.object(module.ControlEngine,"run",return_value=result):
            out=worker.handle(dict(operation="action",action="activate",element="Save",expected_window="Card",snapshot_id="owned",expect={"window":"Card"}))
        self.assertFalse(out["success"]);self.assertFalse(out["postcondition_verified"])
    def test_stale_form_never_dispatches(self):
        worker = Fake(); worker.key = "SecondaryFrame[replacement]"
        result = worker.handle(self.request())
        self.assertFalse(result["success"]); self.assertFalse(result["outcome_unknown"]); self.assertEqual(worker.effects, 0)
    def test_wrong_window_never_dispatches(self):
        worker = Fake(); worker.title = "Other card"
        self.assertFalse(worker.handle(self.request())["success"]); self.assertEqual(worker.effects, 0)
    def test_transport_loss_is_unknown_and_broken(self):
        worker = Fake(); worker.lose = True
        result = worker.handle(self.request())
        self.assertTrue(result["outcome_unknown"]); self.assertTrue(worker.broken); self.assertEqual(worker.effects, 1)
        self.assertFalse(worker.handle(self.request())["success"]); self.assertEqual(worker.effects, 1)
    def test_batch_stops_on_postcondition_failure(self):
        worker = Fake(); first = dict(action="command", element="Save", expected_window="Card", expect={"window": "Changed"})
        result = worker.handle(dict(operation="batch", snapshot_id="owned", expected_window="Card", steps=[first, first]))
        self.assertFalse(result["success"]); self.assertEqual(worker.effects, 1); self.assertEqual(len(result["results"]), 1)
    def test_batch_unknown_does_not_replay_or_run_next_step(self):
        worker = Fake(); worker.lose = True; step = dict(action="command", element="Save", expected_window="Card")
        result = worker.handle(dict(operation="batch", snapshot_id="owned", expected_window="Card", steps=[step, step]))
        self.assertTrue(result["outcome_unknown"]); self.assertEqual(worker.effects, 1)
    def test_foreign_snapshot_never_dispatches(self):
        worker = Fake(); request = self.request(); request["snapshot_id"] = "another-client"
        self.assertFalse(worker.handle(request)["success"]); self.assertEqual(worker.effects, 0)
    def test_success_is_not_a_business_postcondition(self):
        worker = Fake(); result = worker.handle(self.request())
        self.assertTrue(result["success"]); self.assertFalse(result["postcondition_verified"])

class WaitContracts(unittest.TestCase):
    def worker(self, sequence):
        w = Fake(); w.reads = 0
        def capture(request):
            item = sequence[min(w.reads, len(sequence)-1)]; w.reads += 1
            if isinstance(item, Exception): raise item
            w.title = item
            w.snapshots["snapshot-"+str(w.reads)] = {"signature": (w.key, "form-handle", item), "elements": w.elements(None)}
            return {"success": True, "snapshot_id": "snapshot-"+str(w.reads), "window": {"title": item, "form_name": "Catalog.Items.Object"}, "values": {"Name": item}}
        w.capture = capture
        return w
    def wait(self, **kw):
        return dict(operation="action", action="wait", snapshot_id="owned", expected_window="Ready", wait_timeout_ms=500, **kw)
    def test_complete_reply_can_outlive_poll_budget_without_losing_transport(self):
        w=self.worker(['Ready']);clock=[0.0];capture=w.capture
        def delayed(request):
            self.assertEqual(w.client._io_deadline,60.0)
            clock[0]=2.0
            return capture(request)
        w.capture=delayed
        with patch.object(module.time,'monotonic',side_effect=lambda:clock[0]):
            result=w.handle(dict(operation='observe',view='windows',wait_timeout_ms=1000))
        self.assertTrue(result['success']);self.assertFalse(w.broken);self.assertEqual(w.reads,1)
    def test_late_negative_reply_stops_polls_but_keeps_channel(self):
        w=self.worker(['Loading']);clock=[0.0];capture=w.capture
        def delayed(request):
            self.assertEqual(w.client._io_deadline,60.0)
            clock[0]=2.0
            return capture(request)
        w.capture=delayed
        with patch.object(module.time,'monotonic',side_effect=lambda:clock[0]):
            result=w.handle(dict(self.wait(),wait_timeout_ms=1000))
        self.assertEqual(result['state'],'condition_timeout');self.assertFalse(w.broken);self.assertEqual(w.reads,1);self.assertFalse(result['outcome_unknown'])
    def test_explicit_action_postcondition_defaults_to_ten_seconds(self):
        w=self.worker(['Ready']);budgets=[]
        def wait(request,expect,timeout_ms,*args,**kwargs):
            budgets.append(timeout_ms)
            return {'success':True}
        w.wait_capture=wait
        r=w.handle(dict(operation='action',action='command',element='Save',expected_window='Card',snapshot_id='owned',expect={'window':'Ready'}))
        self.assertTrue(r['success']);self.assertEqual(budgets,[10000]);self.assertEqual(w.effects,1)
    def test_thirty_second_postcondition_uses_explicit_budget_and_restores_defaults(self):
        w=self.worker(['Ready']);w.client.RECV_TIMEOUT=10;clock=[0.0];capture=w.capture
        def delayed(request):
            self.assertEqual(w.client._io_deadline,90.0)
            self.assertEqual(w.client.RECV_TIMEOUT,90)
            clock[0]=31.0
            return capture(request)
        w.capture=delayed
        with patch.object(module.time,'monotonic',side_effect=lambda:clock[0]):
            r=w.handle(dict(operation='action',action='command',element='Save',expected_window='Card',snapshot_id='owned',expect={'window':'Ready'},wait_timeout_ms=60000,operation_timeout_s=90))
        self.assertTrue(r['success']);self.assertTrue(r['postcondition_verified']);self.assertEqual(w.effects,1);self.assertEqual(w.client.RECV_TIMEOUT,10);self.assertFalse(w.broken)
    def test_invalid_operation_or_wait_budget_never_dispatches(self):
        for extra in [{'operation_timeout_s':0},{'operation_timeout_s':301},{'operation_timeout_s':True},{'operation_timeout_s':5.5},{'operation_timeout_s':'90'},{'operation_timeout_s':25,'wait_timeout_ms':60000}]:
            w=self.worker(['Ready']);r=w.handle(dict(operation='action',action='command',element='Save',expected_window='Card',snapshot_id='owned',**extra))
            self.assertEqual(r['state'],'rejected');self.assertEqual(w.effects,0);self.assertFalse(w.broken)
    def test_batch_wait_budget_is_preflighted_before_first_mutation(self):
        w=self.worker(['Ready']);r=w.handle(dict(operation='batch',action='batch',expected_window='Card',snapshot_id='owned',steps=[{'action':'command','element':'Save','expected_window':'Card'},{'action':'wait','expected_window':'Card','wait_timeout_ms':90000}]))
        self.assertEqual(r['state'],'rejected');self.assertEqual(w.effects,0)
    def test_clean_negative_read_is_waited_without_mutation(self):
        w=self.worker([module.ReadNotReady(), "Ready"]);r=w.handle(self.wait())
        self.assertTrue(r["success"]);self.assertTrue(r["postcondition_verified"]);self.assertFalse(r["action_dispatched"]);self.assertEqual(w.effects,0);self.assertEqual(w.reads,2)
    def test_wrong_window_waits_until_exact_target(self):
        w=self.worker(["Loading","Ready"]);self.assertTrue(w.handle(self.wait())["success"]);self.assertEqual(w.reads,2)
    def test_absent_field_is_pending_not_empty(self):
        w=self.worker([module.ConditionPending("Name"),"Ready"]);self.assertTrue(w.handle(self.wait(expect={"fields":{"Name":"Ready"}}))["success"])
    def test_ambiguous_field_is_not_retried(self):
        w=self.worker([module.Rejected("Ambiguous"),"Ready"]);r=w.handle(self.wait());self.assertFalse(r["success"]);self.assertEqual(w.reads,1)
    def test_timeout_preserves_readonly_channel(self):
        w=self.worker(["Loading"]);request=self.wait();request['wait_timeout_ms']=0;r=w.handle(request)
        self.assertEqual(r['state'],'condition_timeout');self.assertFalse(r['outcome_unknown']);self.assertFalse(w.broken);self.assertEqual(w.reads,1)
    def test_wait_checks_form_name(self):
        w=self.worker(["Ready"]);r=w.handle(dict(self.wait(expected_form="Wrong"),wait_timeout_ms=0));self.assertFalse(r['success'])
    def test_transport_loss_during_wait_is_not_retried(self):
        w=self.worker([OSError('lost'),"Ready"]);r=w.handle(self.wait());self.assertEqual(r['state'],'transport_failed');self.assertTrue(w.broken);self.assertEqual(w.reads,1)
    def test_post_action_wait_never_repeats_mutation(self):
        w=self.worker(["Loading","Ready"]);r=w.handle(dict(operation='action',action='command',element='Save',expected_window='Card',snapshot_id='owned',expect={'window':'Ready'}))
        self.assertTrue(r['success']);self.assertEqual(w.effects,1);self.assertEqual(w.reads,2)
    def test_transport_loss_after_action_remains_unknown(self):
        w=self.worker([OSError('lost')]);r=w.handle(dict(operation='action',action='command',element='Save',expected_window='Card',snapshot_id='owned',expect={'window':'Ready'}))
        self.assertTrue(r['outcome_unknown']);self.assertEqual(w.effects,1);self.assertTrue(w.broken)
    def test_known_postcondition_timeout_stops_batch(self):
        w=self.worker(['Loading']);step=dict(action='command',element='Save',expected_window='Card',expect={'window':'Ready'},wait_timeout_ms=0)
        r=w.handle(dict(operation='batch',snapshot_id='owned',expected_window='Card',steps=[step,step]));self.assertFalse(r['success']);self.assertEqual(len(r['results']),1);self.assertEqual(w.effects,1);self.assertFalse(w.broken)
    def test_ready_honors_bounded_window_wait_without_breaking_pipe(self):
        w=self.worker(['']);r=w.handle({'operation':'ready','wait_timeout_ms':0})
        self.assertFalse(r['success']);self.assertEqual(r['state'],'condition_timeout');self.assertFalse(r['outcome_unknown']);self.assertFalse(r['action_dispatched']);self.assertFalse(w.broken)
    def test_ready_does_not_accept_unbounded_wait(self):
        w=self.worker(['']);r=w.handle({'operation':'ready','wait_timeout_ms':10001})
        self.assertFalse(r['success']);self.assertEqual(r['state'],'rejected');self.assertEqual(w.reads,0)
    def test_startup_budget_reaches_wire_and_is_restored(self):
        w=self.worker(['Ready']);w.client.RECV_TIMEOUT=10; original=w.capture
        def capture(request):
            self.assertEqual(w.client.RECV_TIMEOUT,90)
            self.assertGreater(w.client._io_deadline-module.time.monotonic(),80)
            return original(request)
        w.capture=capture
        r=w.handle({'operation':'ready','startup_timeout_ms':90000})
        self.assertTrue(r['success']);self.assertEqual(w.client.RECV_TIMEOUT,10);self.assertEqual(w.reads,1)
    def test_startup_transport_failure_never_replays_read(self):
        w=self.worker([TimeoutError('partial response'),'Ready']);w.client.RECV_TIMEOUT=10
        r=w.handle({'operation':'ready','startup_timeout_ms':90000})
        self.assertEqual(r['state'],'transport_failed');self.assertEqual(w.reads,1);self.assertTrue(w.broken);self.assertEqual(w.client.RECV_TIMEOUT,10)
    def test_startup_budget_is_strict_and_not_an_observe_override(self):
        for value in [-1,0,180001,True,1.5,'90000']:
            w=self.worker(['Ready']);r=w.handle({'operation':'ready','startup_timeout_ms':value})
            self.assertEqual(r['state'],'rejected');self.assertEqual(w.reads,0)
        w=self.worker(['Ready']);r=w.handle({'operation':'observe','wait_timeout_ms':90000,'startup_timeout_ms':90000})
        self.assertEqual(r['state'],'rejected');self.assertEqual(w.reads,0)
    def test_ready_requires_nonempty_readable_window(self):
        w=self.worker(['','Ready']);r=w.handle({'operation':'ready'});self.assertTrue(r['success']);self.assertEqual(w.reads,2)
    def states(self, w):
        w.guids.CURRENT_VISIBLE='visible';w.guids.CURRENT_ENABLE='enabled';w.guids.CURRENT_READONLY='readonly'
        w.wire.decode_bool=lambda x:True;w.send=lambda *a,**kw:b''
    def test_same_title_replaced_form_cannot_mix_values_and_conditions(self):
        w=self.worker(['Ready']);self.states(w)
        w.form=lambda:({'key':w.key},{'handle':'REPLACEMENT'},{'title':'Ready','form_name':'Catalog.Items.Object'})
        r=w.handle(dict(self.wait(expect={'fields':{'Name':'Ready'},'elements':{'Save':{'enabled':True}}}),wait_timeout_ms=0))
        self.assertFalse(r['success']);self.assertFalse(r['postcondition_verified']);self.assertFalse(w.broken)
    def test_form_replacement_during_property_read_is_not_verified(self):
        w=self.worker(['Ready']);self.states(w);w.current_handle='form-handle'
        w.form=lambda:({'key':w.key},{'handle':w.current_handle},{'title':'Ready','form_name':'Catalog.Items.Object'})
        def send(*a,**kw):w.current_handle='REPLACEMENT';return b''
        w.send=send;r=w.handle(dict(self.wait(expect={'elements':{'Save':{'enabled':True}}}),wait_timeout_ms=0))
        self.assertFalse(r['success']);self.assertFalse(w.broken)
    def test_local_read_deadline_preserves_channel(self):
        class NotSent(TimeoutError):pass
        w=self.worker([NotSent('deadline before send')]);w.wire.CommandNotSentTimeout=NotSent
        r=w.handle(self.wait());self.assertEqual(r['state'],'condition_timeout');self.assertFalse(r['outcome_unknown']);self.assertFalse(w.broken)
    def test_local_read_deadline_after_confirmed_mutation_is_known(self):
        class NotSent(TimeoutError):pass
        w=self.worker([NotSent('deadline before read')]);w.wire.CommandNotSentTimeout=NotSent
        r=w.handle(dict(operation='action',action='command',element='Save',expected_window='Card',snapshot_id='owned',expect={'window':'Ready'}))
        self.assertEqual(r['state'],'condition_timeout');self.assertFalse(r['outcome_unknown']);self.assertTrue(r['action_dispatched']);self.assertEqual(w.effects,1);self.assertFalse(w.broken)
    def test_receive_timeout_still_breaks_channel(self):
        class NotSent(TimeoutError):pass
        w=self.worker([TimeoutError('partial response')]);w.wire.CommandNotSentTimeout=NotSent
        r=w.handle(self.wait());self.assertEqual(r['state'],'transport_failed');self.assertTrue(w.broken)
    def test_exhausted_overall_budget_does_not_start_another_observation(self):
        w=self.worker(['Ready']);w.deadline=0;r=w.wait_capture({'view':'windows'},timeout_ms=5000)
        self.assertEqual(r['state'],'condition_timeout');self.assertEqual(w.reads,0)
    def test_ambiguous_condition_element_refuses_without_retry(self):
        w=self.worker(['Ready']);self.states(w);w.elements=lambda f:[{'name':'Save'},{'name':'Save'}]
        r=w.handle(self.wait(expect={'elements':{'Save':{'enabled':True}}}));self.assertEqual(r['state'],'rejected');self.assertEqual(w.reads,1)
    def test_json_integral_number_timeout_is_supported(self):
        w=self.worker(['Ready']);self.assertTrue(w.handle(dict(self.wait(),wait_timeout_ms=500.0))['success'])
    def test_invalid_timeout_does_not_read_or_write(self):
        for value in [-1,300001,61000,True,1.5,'500']:
            w=self.worker(['Ready']);r=w.handle(dict(self.wait(),wait_timeout_ms=value));self.assertFalse(r['success']);self.assertEqual(w.effects,0);self.assertEqual(w.reads,0)
    def test_pending_read_does_not_forge_failed_snapshot(self):
        w=self.worker([module.ReadNotReady()]);r=w.handle(dict(self.wait(),wait_timeout_ms=0));self.assertIsNone(r['window']);self.assertNotIn('snapshot_id',r);self.assertFalse(r['complete'])
    def test_visibility_unknown_never_satisfies_false(self):
        w=self.worker(['Ready']);w.guids.CURRENT_VISIBLE='visible';w.guids.CURRENT_ENABLE='enabled';w.guids.CURRENT_READONLY='readonly';w.wire.decode_bool=lambda x:None;w.send=lambda *a,**kw:b''
        r=w.handle(dict(self.wait(expect={'elements':{'Save':{'enabled':False}}}),wait_timeout_ms=0));self.assertFalse(r['success'])
    def test_element_conditions_exact_true_is_verified(self):
        w=self.worker(['Ready']);w.guids.CURRENT_VISIBLE='visible';w.guids.CURRENT_ENABLE='enabled';w.guids.CURRENT_READONLY='readonly';w.wire.decode_bool=lambda x:True;w.send=lambda *a,**kw:b''
        w.form=lambda:({'key':w.key},{'handle':'form-handle'},{'title':'Ready','form_name':'Catalog.Items.Object'})
        r=w.handle(self.wait(expect={'elements':{'Save':{'enabled':True}}}));self.assertTrue(r['success'])


class NotSent(TimeoutError): pass
class CompleteNegative(RuntimeError): pass
class WireProbe(module.Worker):
    def __init__(self, replies):
        super().__init__(); self.replies=list(replies); self.calls=[]
        self.wire=types.SimpleNamespace(CommandNotSentTimeout=NotSent, OperationError=CompleteNegative)
        self.client=types.SimpleNamespace(RECV_TIMEOUT=10, _buf=b"", _pending=0,
            s=types.SimpleNamespace(received=0), close=self.close, send_cmd=self.command)
    def close(self): self.client.s=None
    def identity(self):
        if self.broken: raise module.Rejected("Already broken")
    def command(self,guid,key,kind="read",**kwargs):
        self.calls.append(kind); item=self.replies.pop(0)
        if isinstance(item,Exception): raise item
        return {"ok":True,"raw":item}
    def dispatch(self,r):
        self.identity()
        if r.get("action")=="mutate":
            self.stage="dispatch"; self.send("opaque",kind="action"); self.send("opaque",kind="commit")
        elif r.get("action")=="earlier_step":
            self.partial=[{"success":True,"action_dispatched":True}]; self.send("opaque")
        else: self.send("opaque")
        return {"success":True,"action_dispatched":self.dispatched}
class Stability(unittest.TestCase):
    def test_vendor_target_state_decoder_retains_its_constants_and_error_export(self):
        import importlib,protocol_diagnostics
        with patch.dict(sys.modules,{name:types.SimpleNamespace() for name in ['sspi','sspicon','win32security']}):
            wire=importlib.import_module('tc1c')
        self.assertIs(wire.OperationError,protocol_diagnostics.OperationError)
        for marker,expected in [(0x81,'present'),(0x88,'absent'),(0x89,'unknown')]:
            frame=bytes([marker,0,0,0])+b'\x20\xa1\xa3'+wire.TR
            self.assertEqual(wire.decode_target_state(frame),expected)
    def test_real_protocol_rejection_preserves_safe_status_and_keeps_unknown_barrier(self):
        import protocol_diagnostics as tc1c
        for status,code in [(9,'target_not_interactive'),(13,'client_operation_rejected'),(0xffffffff,'client_operation_rejected')]:
            error=tc1c.OperationError(status,'private-target-and-content')
            w=WireProbe([error,b'observation']);w.wire.OperationError=tc1c.OperationError
            r=w.handle({'action':'mutate'})
            self.assertEqual(r['diagnostics']['protocol_rejection']['status_code'],status)
            self.assertEqual(r['diagnostics']['protocol_rejection']['code'],code)
            self.assertNotIn('private-target',str(r));self.assertTrue(r['outcome_unknown']);self.assertTrue(r['mutation_blocked']);self.assertEqual(r['transport_state'],'usable')
            self.assertTrue(w.handle({'operation':'observe'})['success'])
    def test_read_kind_navigation_not_sent_restores_dispatch_history(self):
        class Navigation(WireProbe):
            def dispatch(self,r):
                self.send('opaque',ui_effect=True)
                return {'success':True}
        w=Navigation([NotSent()]);r=w.handle({'operation':'action'})
        self.assertFalse(r['outcome_unknown']);self.assertFalse(r['action_dispatched']);self.assertFalse(w.broken)
        w=Navigation([TimeoutError()]);r=w.handle({'operation':'action'})
        self.assertTrue(r['outcome_unknown']);self.assertTrue(r['mutation_blocked']);self.assertEqual(r['transport_state'],'broken')
    def test_invalid_or_missing_protocol_status_cannot_expose_exception_text(self):
        import protocol_diagnostics as tc1c
        for status in [None,True,-1,2**32,'private-value']:
            self.assertEqual(tc1c.public_operation_error(status),{})

    def test_default_receive_budget_allows_thirty_two_second_complete_reply(self):
        w=WireProbe([b"complete"]);clock=[0.0];original=w.client.send_cmd
        def delayed(*a,**kw):
            self.assertEqual(w.client.RECV_TIMEOUT,60);self.assertEqual(w.client._io_deadline,60);clock[0]=32.0;return original(*a,**kw)
        w.client.send_cmd=delayed
        with patch.object(module.time,"monotonic",side_effect=lambda:clock[0]):r=w.handle({"operation":"observe"})
        self.assertTrue(r["success"]);self.assertFalse(w.broken);self.assertEqual(w.client.RECV_TIMEOUT,10)
    def test_first_mutation_proven_not_sent_keeps_channel_and_no_unknown(self):
        w=WireProbe([NotSent(),b"complete"]);r=w.handle({"action":"mutate"})
        self.assertEqual(r["state"],"operation_timeout");self.assertFalse(r["action_dispatched"]);self.assertFalse(r["outcome_unknown"]);self.assertEqual(r["transport_state"],"usable");self.assertTrue(w.handle({"operation":"observe"})["success"])
    def test_commit_not_sent_preserves_prior_mutation_and_channel(self):
        w=WireProbe([b"action_ack",NotSent(),b"observation"]);r=w.handle({"action":"mutate"})
        self.assertTrue(r["outcome_unknown"]);self.assertTrue(r["action_dispatched"]);self.assertFalse(r["retry_safe"]);self.assertEqual(r["transport_state"],"usable");self.assertTrue(w.handle({"operation":"observe"})["success"]);self.assertEqual(w.calls,["action","commit","read"])
    def test_full_negative_read_keeps_channel_for_diagnostics(self):
        w=WireProbe([CompleteNegative("private-value"),b"read"]);r=w.handle({"operation":"observe"});self.assertEqual(r["state"],"operation_rejected");self.assertEqual(r["diagnostics"]["last_rpc_phase"],"reply_rejected");self.assertFalse(w.broken);self.assertTrue(w.handle({"operation":"observe"})["success"]);self.assertNotIn("private-value",json_for(r))
    def test_full_negative_commit_is_unknown_but_not_a_lost_socket(self):
        w=WireProbe([b"ack",CompleteNegative(),b"read"]);r=w.handle({"action":"mutate"});self.assertTrue(r["outcome_unknown"]);self.assertEqual(r["transport_state"],"usable");self.assertTrue(w.handle({"operation":"observe"})["success"])
    def test_partial_receive_timeout_preserves_diagnostics_before_close(self):
        w=WireProbe([TimeoutError("private-frame")]);w.client._buf=b"abc";w.client._pending=1;w.client.s.received=3;r=w.handle({"operation":"observe"});self.assertEqual(r["state"],"transport_failed");self.assertTrue(w.broken);self.assertEqual(r["diagnostics"]["buffered_bytes"],3);self.assertEqual(r["diagnostics"]["pending_replies"],1);self.assertEqual(r["diagnostics"]["bytes_received"],3);self.assertNotIn("private-frame",json_for(r))
    def test_closed_socket_never_reported_usable_after_typed_rejection(self):
        w=WireProbe([CompleteNegative()]);w.client.s=None;r=w.handle({});self.assertEqual(r["transport_state"],"broken");self.assertIsNone(r["diagnostics"]["bytes_received"])
    def test_prior_completed_batch_step_is_not_erased_by_unsent_read(self):
        w=WireProbe([NotSent()]);r=w.handle({"action":"earlier_step"});self.assertFalse(r["outcome_unknown"]);self.assertTrue(r["action_dispatched"]);self.assertFalse(r["retry_safe"]);self.assertEqual(len(r["results"]),1)
    def test_explicit_short_timeout_is_preserved_and_partial_wire_is_not_replayed(self):
        w=WireProbe([TimeoutError()]);r=w.handle({"operation_timeout_s":5});self.assertEqual(r["diagnostics"]["receive_timeout_s"],5);self.assertTrue(w.broken);self.assertEqual(w.calls,["read"])
    def test_pid_drift_rejection_cannot_restore_channel(self):
        w=WireProbe([])
        def drift(): w.broken=True;w.close();raise module.Rejected("Identity changed")
        w.identity=drift;r=w.handle({});self.assertEqual(r["transport_state"],"broken");self.assertFalse(r["retry_safe"])
    def test_unknown_usable_channel_blocks_new_mutation_but_allows_observation(self):
        w=WireProbe([b"ack",CompleteNegative(),b"read"]);r=w.handle({"operation":"action","action":"mutate"});self.assertTrue(r["outcome_unknown"]);self.assertTrue(r["mutation_blocked"])
        blocked=w.handle({"operation":"action","action":"mutate"});self.assertEqual(blocked["state"],"rejected");self.assertTrue(blocked["mutation_blocked"]);self.assertFalse(blocked["retry_safe"]);self.assertEqual(w.calls,["action","commit"]);self.assertTrue(w.handle({"operation":"observe"})["success"])
    def test_exact_max_payload_with_newline_is_handled_and_next_request_survives(self):
        import json,subprocess
        base=json.dumps({"operation":"unsupported","padding":""},separators=(",",":"));payload=base.replace('"padding":""','"padding":"'+('x'*(module.MAX_LINE-len(base.encode())))+'"').encode();self.assertEqual(len(payload),module.MAX_LINE)
        run=subprocess.run([sys.executable,"-I","-B",str(path)],input=payload+b"\n{}\n",capture_output=True,timeout=10);self.assertEqual(run.returncode,0);lines=run.stdout.splitlines();self.assertEqual(len(lines),2);self.assertEqual(json.loads(lines[0])["state"],"rejected")
    def test_payload_one_byte_over_limit_closes_stdio_without_dispatch(self):
        import json,subprocess
        base=json.dumps({"operation":"unsupported","padding":""},separators=(",",":"));payload=base.replace('"padding":""','"padding":"'+('x'*(module.MAX_LINE+1-len(base.encode())))+'"').encode();run=subprocess.run([sys.executable,"-I","-B",str(path)],input=payload+b"\n",capture_output=True,timeout=10);self.assertEqual(run.returncode,0);self.assertEqual(run.stdout,b"")
    def test_main_oversized_observation_does_not_invent_unknown_or_close_pipe(self):
        import io,json
        worker=types.SimpleNamespace(client=None,broken=False,mutations_blocked=False,handle=lambda r:{"success":True,"transport_state":"usable","action_dispatched":False,"values":{"secret":"x"*300000}})
        output=io.BytesIO()
        with patch.object(module,"Worker",return_value=worker),patch.object(module.sys,"stdin",types.SimpleNamespace(buffer=io.BytesIO(b'{"operation":"observe"}\n{"operation":"observe"}\n'))),patch.object(module.sys,"stdout",types.SimpleNamespace(buffer=output)):module.main()
        lines=output.getvalue().splitlines();self.assertEqual(len(lines),2);r=json.loads(lines[0]);self.assertEqual(r["state"],"result_too_large");self.assertFalse(r["outcome_unknown"]);self.assertEqual(r["transport_state"],"usable");self.assertNotIn("secret",json.dumps(r));self.assertFalse(worker.broken)
    def test_oversized_known_mutation_result_keeps_execution_evidence_without_replay(self):
        import json
        w=WireProbe([]);r=json.loads(module.bounded_reply({"success":True,"action_dispatched":True,"postcondition_verified":True,"transport_state":"usable","snapshot_id":"owned","values":"x"*300000},{"operation":"action"},w));self.assertFalse(r["outcome_unknown"]);self.assertTrue(r["action_dispatched"]);self.assertTrue(r["execution_success"]);self.assertTrue(r["postcondition_verified"]);self.assertFalse(r["retry_safe"]);self.assertEqual(r["snapshot_id"],"owned");self.assertEqual(w.calls,[])
    def test_oversized_unknown_result_preserves_mutation_barrier_and_health(self):
        import json
        w=WireProbe([]);r=json.loads(module.bounded_reply({"success":False,"outcome_unknown":True,"action_dispatched":True,"transport_state":"usable","results":[{"value":"x"*300000}]},{"operation":"batch"},w));self.assertTrue(r["outcome_unknown"]);self.assertTrue(r["mutation_blocked"]);self.assertTrue(w.mutations_blocked);self.assertEqual(r["transport_state"],"usable");self.assertLess(len(json.dumps(r)),4096)
    def test_oversized_broken_result_never_restores_transport(self):
        import json
        w=WireProbe([]);w.broken=True;r=json.loads(module.bounded_reply({"success":False,"outcome_unknown":True,"transport_state":"broken","message":"x"*300000},{"operation":"action"},w));self.assertEqual(r["transport_state"],"broken");self.assertTrue(r["outcome_unknown"])
def json_for(value):
    import json
    return json.dumps(value)

if __name__ == "__main__": unittest.main()
