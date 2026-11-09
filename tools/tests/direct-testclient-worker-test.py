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

if __name__ == "__main__": unittest.main()
