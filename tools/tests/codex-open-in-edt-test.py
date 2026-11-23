"""Regression tests for the Codex Open in handler."""
import runpy
from pathlib import Path
from tempfile import TemporaryDirectory
import unittest

handler = runpy.run_path(str(Path(__file__).resolve().parents[1] / "codex-open-in-edt.py"))


class Bridge:
    def __init__(self):
        self.calls = []

    def contract(self, op):
        return {"op": op, "contract": {"inputSchema": {
            "properties": {"expected_project_path": {}, "activate_window": {}}
        }}}

    def operation(self, contract, args):
        op = contract["op"]
        self.calls.append((op, args))
        if op == "edt_editor_open":
            return {"ready": True, "editor_id": "fixture", "selected_line": args.get("line"),
                    "tabs": [{"id": handler["FORM_PAGE"], "active": True}]}
        if op == "edt_editor_form_preview":
            return {"ready": True, "selected": [{"name": "Кнопка", "id": 27}]}
        raise AssertionError(op)


class OpenInEdtTest(unittest.TestCase):
    def setUp(self):
        folder = TemporaryDirectory()
        self.addCleanup(folder.cleanup)
        self.root = Path(folder.name).resolve()
        (self.root / ".project").write_text("<projectDescription><name>Fixture</name></projectDescription>")
        (self.root / "src").mkdir()
        self.bridge = Bridge()

    def open(self, name, line, content=""):
        path = self.root / "src" / name
        path.write_text(content, encoding="utf-8")
        return handler["open_source"](self.bridge, {
            "path": str(path), "location": {"line": line},
        })

    def test_bsl_keeps_line(self):
        self.assertEqual(self.open("Module.bsl", 8)["selected_line"], 8)
        self.assertEqual(self.bridge.calls, [("edt_editor_open", {
            "project": "Fixture", "file_path": "src/Module.bsl",
            "expected_project_path": str(self.root), "line": 8,
        })])

    def test_form_resolves_nested_item_and_activates_edt(self):
        result = self.open("Form.form", 6, """<form:Form xmlns:form="http://g5.1c.ru/v8/dt/form">
  <items>
    <name>Группа</name>
    <id>1</id>
    <items>
      <name>Кнопка</name>
      <id>27</id>
    </items>
  </items>
</form:Form>""")
        self.assertEqual(result["selected_element"], "Кнопка")
        self.assertNotIn("line", self.bridge.calls[0][1])
        self.assertTrue(self.bridge.calls[0][1]["activate_window"])
        self.assertEqual(self.bridge.calls[1][1]["element_id"], 27)

    def test_ambiguous_line_keeps_form_open(self):
        item = "<items><name>Кнопка</name><id>27</id></items>"
        result = self.open("Form.form", 2, "<Form>\n" + item * 2 + "\n</Form>")
        self.assertEqual(result["form_element_state"], "no_unique_element")
        self.assertEqual(len(self.bridge.calls), 1)

    def test_metadata_does_not_send_a_bsl_line(self):
        self.assertTrue(self.open("Catalog.mdo", 8)["ready"])
        self.assertEqual(len(self.bridge.calls), 1)
        self.assertNotIn("line", self.bridge.calls[0][1])

    def test_unsupported_file_never_reaches_the_bridge(self):
        with self.assertRaises(handler["NavigationError"]):
            self.open("README.md", 1)
        self.assertEqual(self.bridge.calls, [])


if __name__ == "__main__":
    unittest.main()
