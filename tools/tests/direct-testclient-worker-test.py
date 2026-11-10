# SPDX-License-Identifier: AGPL-3.0-only
"""Transport boundaries with a fake wire, no 1C and no input on the desktop."""
import importlib.util
from pathlib import Path
import sys
import types
import unittest
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
    def request(self, **kw):
        return dict(operation="action", action="command", element="Save", expected_window="Card", snapshot_id="owned", **kw)
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
        for value in [-1,10001,True,1.5,'500']:
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

if __name__ == "__main__": unittest.main()
