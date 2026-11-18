# SPDX-License-Identifier: AGPL-3.0-only
"""Bounded flat-table operations. Bulk reads explicitly change owned UI selection."""
import json
import re
from collections import Counter
from controls import checked


class RowsUnproven(ValueError):
    pass


class TableEngine:
    def __init__(self, worker, rejected):
        self.w, self.Rejected = worker, rejected

    def flag(self, guid, item):
        return self.w.wire.decode_bool(self.w.send(guid, item["key"], middle=b"\xe1", handle=item["handle"]))

    def guard(self, signature):
        window, form, public = self.w.form()
        if (window["key"], (form or {}).get("handle")) != signature:
            raise self.Rejected("Active form changed during table operation")

    def resolve(self, name, snapshot=None):
        window, form, public = self.w.form()
        table = self.w.one(self.w.elements(form), name, {"Table"})
        if snapshot is not None:
            prior = self.w.one(snapshot["elements"], name, {"Table"})
            if (prior["key"], prior["handle"]) != (table["key"], table["handle"]):
                raise self.Rejected("Table changed since observation")
        if self.flag(self.w.guids.CURRENT_VISIBLE, table) is not True:
            raise self.Rejected("Table is hidden or visibility is unknown")
        signature = (window["key"], (form or {}).get("handle"))
        columns, pending, visited = [], [(table["key"], 0)], set()
        while pending:
            parent, depth = pending.pop(0)
            if parent in visited or depth > 5 or len(visited) >= 100 or len(columns) > 100:
                raise self.Rejected("Table column tree exceeds limits")
            visited.add(parent)
            for item in self.w.collection(self.w.guids.GET_CHILD_OBJECTS, parent):
                if item["class"] == "EditField":
                    columns.append(item)
                elif item["class"] == "Group":
                    pending.append((item["key"], depth + 1))
        self.guard(signature)
        return table, columns, signature

    def columns(self, columns, names):
        result = {}
        for name in names:
            col = self.w.one(columns, name, {"EditField"})
            title = col.get("title")
            if not title or sum(c.get("title") == title for c in columns) != 1:
                raise self.Rejected("Table column title is absent or ambiguous: " + name)
            result[name] = col
        return result

    def modern_rows(self):
        version = getattr(self.w.client, "platform_version", "")
        if not isinstance(version, str) or not re.fullmatch(r"[0-9]+(?:\.[0-9]+){3}", version):
            raise self.Rejected("Verified four-part client platform version required")
        return tuple(map(int, version.split("."))) >= (8, 5, 1, 0)

    def navigate(self, table, guid, middle=b"\xe1", pad=None):
        return self.w.send(guid, table["key"], middle=middle, handle=table["handle"], pad=pad, ui_effect=True)

    def selected_current(self, table):
        # Legacy-compatible: select the actual cursor, then verify ONE selected descriptor.
        self.navigate(table,self.w.guids.GOTO_ROW,self.w.wire.mk_gotorow())
        rows=self.w.wire.decode_rows(self.w.send(self.w.guids.GET_SELECTED_ROWS,table["key"],middle=b"\xe0\x4b\x55",handle=table["handle"]))
        if len(rows)!=1:raise RowsUnproven("Cannot prove exactly one selected current row")
        return rows[0]

    def required_current(self, table):
        row = self.current(table)
        if row is None:
            raise RowsUnproven("No current row was returned")
        return row

    def native_select(self, table, columns, match, signature, *, open_row=False):
        if not match or any("*" in value or "?" in value for value in match.values()):
            raise self.Rejected("Exact native selection requires nonempty literal criteria without wildcard characters")
        if self.flag(self.w.guids.CURRENT_MODE_IS_EDIT,table) is not False:
            raise self.Rejected("Finish existing row editing before selection")
        modern = self.modern_rows()  # validate executable version before navigation
        observe_row = self.required_current if open_row and modern else self.selected_current
        criteria={name:columns[name] for name in match}
        def matches(row):return self.project(row,criteria)==match
        def search(direction):
            self.w.stage="table.search_"+direction
            try:
                found=self.w.wire.decode_goto_row(self.navigate(table,self.w.guids.GOTO_ROW,self.w.wire.mk_gotorow(fields=[(columns[n]["title"],v) for n,v in match.items()],direction=direction),self.w.wire.GOTOROW_PAD))
            except getattr(self.w.wire, "OperationError", ()) as error:
                # A fully decoded GOTO_ROW status12 is a completed native search miss.
                # It can occur while proving there is no second match. Never mask
                # another method/status, an incomplete reply or a lost mutation.
                if getattr(error, "status", None) != 12:
                    raise
                found = False
            if type(found) is not bool:raise self.Rejected("Native row search reply is undecodable")
            self.guard(signature)
            return found
        self.w.stage="table.first_row"
        self.navigate(table,self.w.guids.GOTO_FIRST_ROW)
        if not search("down"):return None,"row_not_found"
        candidate=observe_row(table)
        if not matches(candidate):raise self.Rejected("Native search did not select the exact literal criteria")
        self.w.stage="table.next_row"
        self.navigate(table,self.w.guids.GOTO_NEXT_ROW)
        neighbour=observe_row(table)
        if neighbour!=candidate:
            duplicate=matches(neighbour) or search("down")
        else:
            # A non-moving endpoint and adjacent identical rows look alike. Inspect the
            # opposite boundary; identical descriptors alone never prove singleton/end.
            self.w.stage="table.last_row"
            self.navigate(table,self.w.guids.GOTO_LAST_ROW)
            if not search("up"):raise self.Rejected("Table changed during uniqueness check")
            last=observe_row(table)
            if not matches(last):raise self.Rejected("Reverse search selected different criteria")
            if last!=candidate:duplicate=True
            else:
                self.w.stage="table.previous_row"
                self.navigate(table,self.w.guids.GOTO_PREVIOUS_ROW)
                neighbour=observe_row(table)
                if neighbour==candidate:raise RowsUnproven("Identical rows and table endpoints cannot be distinguished")
                duplicate=matches(neighbour) or search("up")
        if duplicate:return None,"ambiguous_row"
        self.navigate(table,self.w.guids.GOTO_FIRST_ROW)
        if not search("down"):raise self.Rejected("Target disappeared during selection")
        actual=observe_row(table)
        if actual!=candidate or not matches(actual):raise self.Rejected("Target row changed during selection")
        self.guard(signature)
        return actual,None

    def current(self, table):
        self.w.stage = "table.current_row"
        rows = self.w.wire.decode_rows(self.w.send(self.w.guids.GET_CURRENT_ROW, table["key"],
                                                   middle=b"\xe0\x4b\x55", handle=table["handle"]))
        if len(rows) > 1:
            raise self.Rejected("Ambiguous current row")
        return rows[0] if rows else None

    def project(self, row, columns):
        result = {}
        for name, col in columns.items():
            if col["title"] not in row:
                raise self.Rejected("Requested cell is unavailable: " + name)
            value = row[col["title"]]
            if value is not None and (not isinstance(value, str) or len(value) > 4096):
                raise self.Rejected("Requested cell is unsupported or exceeds limit: " + name)
            # Null is explicitly unavailable, never invented empty text.
            result[name] = value
        return result

    def describe(self, request):
        table, columns, signature = self.resolve(request["table"])
        if not self.modern_rows():raise self.Rejected("table_describe current-row observation requires platform 8.5.1; use native table_select for legacy selection")
        chosen = self.columns(columns, request["columns"])
        row = self.current(table)
        self.guard(signature)
        return {"name": request["table"], "columns": [{"name": n, "title": c["title"], "type": c.get("type")} for n,c in chosen.items()],
                "current_row": self.project(row, chosen) if row is not None else None,
                "scope": "current_row_only", "selection_changed": False}

    def bulk(self, table, signature):
        if not self.modern_rows():raise self.Rejected("Complete table read/add/set requires platform 8.5.1; legacy table_select uses bounded native search")
        if self.flag(self.w.guids.CURRENT_MODE_IS_EDIT, table) is not False:
            raise self.Rejected("Finish the existing row edit before table reading or selection")
        self.guard(signature)
        # Start with known empty selection: a no-op SelectAllRows on a single-selection
        # table must not masquerade as a complete one-row table.
        self.w.mutate(self.w.guids.DESELECT_ALL_ROWS, table["key"], handle=table["handle"])
        before = self.w.wire.decode_rows(self.w.send(self.w.guids.GET_SELECTED_ROWS, table["key"],
                                                    middle=b"\xe0\x4b\x55", handle=table["handle"]))
        if before:
            raise self.Rejected("Could not establish empty table selection")
        self.w.mutate(self.w.guids.SELECT_ALL_ROWS, table["key"], handle=table["handle"])
        rows = self.w.wire.decode_rows(self.w.send(self.w.guids.GET_SELECTED_ROWS, table["key"],
                                                  middle=b"\xe0\x4b\x55", handle=table["handle"]))
        self.w.mutate(self.w.guids.DESELECT_ALL_ROWS, table["key"], handle=table["handle"])
        self.guard(signature)
        if not rows:
            raise RowsUnproven("No selected rows: empty and unavailable/single-selection tables cannot be distinguished")
        if len(rows) > 1000:
            raise self.Rejected("Table exceeds 1000-row scan limit")
        return rows

    def read_cell(self, table, col):
        raw = self.w.send(self.w.guids.GET_CELL_TEXT, table["key"], middle=self.w.wire.mk_cell(col["name"]), handle=table["handle"])
        decoded, text = self.w.wire.decode_cell_text(raw)
        if not decoded or text is None or len(text) > 4096:
            raise self.Rejected("Displayed cell text is unavailable; null does not prove empty text")
        return text

    def seek(self, table, columns, match, signature):
        self.w.dispatched = True  # Native read-kind navigation still changes UI state.
        self.w.send(self.w.guids.GOTO_FIRST_ROW, table["key"], middle=b"\xe1", handle=table["handle"])
        raw = self.w.send(self.w.guids.GOTO_ROW, table["key"],
                          middle=self.w.wire.mk_gotorow(fields=[(columns[n]["title"], v) for n,v in match.items()]),
                          handle=table["handle"], pad=self.w.wire.GOTOROW_PAD)
        if self.w.wire.decode_goto_row(raw) is not True:
            raise self.Rejected("Native row search did not confirm a match")
        row = self.current(table)
        if row is None or self.project(row, columns) != match:
            raise self.Rejected("Selected row differs from the exact criteria")
        self.guard(signature)
        return row

    def edit(self, table, columns, cells, signature, anchor):
        for name, value in cells.items():
            if anchor is None or self.current(table) != anchor:
                raise self.Rejected("Current row changed before cell input")
            col = columns[name]
            if col.get("type") not in {"InputField", "CheckBoxField"}:
                raise self.Rejected("Only ordinary InputField table cells support text input")
            for guid in (self.w.guids.CURRENT_VISIBLE, self.w.guids.CURRENT_ENABLE):
                if self.flag(guid, col) is not True:
                    raise self.Rejected("Cell is hidden or disabled")
            if self.flag(self.w.guids.CURRENT_READONLY, col) is not False:
                raise self.Rejected("Cell is read-only or its state is unknown")
            self.guard(signature)
            self.w.stage = "table.focus_cell"
            self.w.mutate(self.w.guids.ACTIVATE, col["key"], handle=col["handle"])
            self.w.stage = "table.current_cell"
            items = self.w.collection(self.w.guids.GET_CURRENT_ITEM, table["key"], middle=b"\xe0\x4b\x55")
            if not any((i["key"], i.get("handle")) == (col["key"], col["handle"]) for i in items):
                raise self.Rejected("Requested cell did not become current")
            mode = self.flag(self.w.guids.CURRENT_MODE_IS_EDIT, table)
            if mode is False:
                self.w.mutate(self.w.guids.CHANGE_ROW, table["key"], handle=table["handle"])
            if self.flag(self.w.guids.CURRENT_MODE_IS_EDIT, table) is not True:
                raise self.Rejected("Row edit was refused")
            self.guard(signature)
            if self.current(table) != anchor:
                raise self.Rejected("Current row changed while focusing or beginning cell edit")
            self.w.stage = "table.input_cell"
            if col.get("type") == "CheckBoxField":
                try: before = checked(self.read_cell(table, col))
                except ValueError as error: raise self.Rejected(str(error))
                self.guard(signature)
                items = self.w.collection(self.w.guids.GET_CURRENT_ITEM, table["key"], middle=b"\xe0\x4b\x55")
                if not any((i["key"], i.get("handle")) == (col["key"], col["handle"]) for i in items) or self.current(table) != anchor:
                    raise self.Rejected("Current row/cell changed while reading checkbox state")
                self.guard(signature)
                if before != value: self.w.mutate(self.w.guids.SET_CHECK, col["key"], handle=col["handle"])
            else:
                self.w.mutate(self.w.guids.INPUT_TEXT if value else self.w.guids.CLEAR, col["key"],
                              self.w.wire.mk_input_text(value) if value else b"", col["handle"])
            self.w.stage = "table.finish_row_edit"
            self.guard(signature)
            self.w.mutate(self.w.guids.END_EDIT_ROW, table["key"], b"\xe1", table["handle"])
            self.guard(signature)
            expected = dict(anchor)
            displayed = self.read_cell(table, col)
            if col.get("type") == "CheckBoxField":
                try: verified = checked(displayed) == value
                except ValueError: verified = False
            else: verified = displayed == value
            expected[col["title"]] = displayed
            if not verified or self.flag(self.w.guids.CURRENT_MODE_IS_EDIT, table) is not False or self.current(table) != expected:
                raise self.Rejected("Row/cell changed after EndEdit; inspect partial changes")
            anchor = expected

    def activate_row(self, table, columns, row, match, signature, result):
        # CHOOSE_ROW is native row activation, not a read-only reference lookup.
        # A choice form can select a value/close; application handlers may execute.
        self.guard(signature)
        if self.flag(self.w.guids.CURRENT_ENABLE, table) is not True:
            raise self.Rejected("Table is disabled or its state is unknown")
        if self.flag(self.w.guids.CURRENT_MODE_IS_EDIT, table) is not False:
            raise self.Rejected("Finish row editing before native row activation")
        try:
            current = self.required_current(table) if self.modern_rows() else self.selected_current(table)
        except RowsUnproven:
            self.guard(signature)
            return dict(result, success=False, postcondition_verified=False,
                        reason="current_row_unproven", data_changed=False)
        if current != row or self.project(current, {n:columns[n] for n in match}) != match:
            raise self.Rejected("Target row changed before native row activation")
        self.guard(signature)
        self.w.stage = "table.choose_row"
        self.w.mutate(self.w.guids.CHOOSE_ROW, table["key"], handle=table["handle"])
        return dict(result, success=True, postcondition_verified=False, row=self.project(row, columns),
                    row_activation_acknowledged=True, command_semantics="choose_row", data_changed=None)

    def run(self, request, snapshot=None):
        if request.get("action") == "table_open":
            for name in ("expected_form", "target_form"):
                if not isinstance(request.get(name), str) or not request[name].strip() or len(request[name]) > 1024:
                    raise self.Rejected("table_open requires exact bounded " + name)
            if request["expected_form"] == request["target_form"]:
                raise self.Rejected("table_open requires a different target form")
        table, available, signature = self.resolve(request["table"], snapshot)
        action = request["action"]
        match, cells = request.get("row_match", {}), request.get("cells", {})
        if any(value == "" for value in cells.values()):
            raise self.Rejected("Empty table-cell writes cannot be reliably verified in this stage")
        names = list(dict.fromkeys(request.get("columns", []) + list(match) + list(cells)))
        columns = self.columns(available, names)
        if action in {"table_add", "table_set"}:
            if self.flag(self.w.guids.CURRENT_ENABLE, table) is not True or self.flag(self.w.guids.CURRENT_READONLY, table) is not False:
                raise self.Rejected("Table is disabled or read-only")
            # Validate every target before any row/data change.
            for name in cells:
                col = columns[name]
                if (col.get("type") == "CheckBoxField" and type(cells[name]) is not bool or col.get("type") == "InputField" and not isinstance(cells[name], str) or col.get("type") not in {"InputField", "CheckBoxField"}) or self.flag(self.w.guids.CURRENT_VISIBLE,col) is not True or self.flag(self.w.guids.CURRENT_ENABLE,col) is not True or self.flag(self.w.guids.CURRENT_READONLY,col) is not False:
                    raise self.Rejected("Requested column is unavailable for input: " + name)
        result = {"name": request["table"], "selection_changed": True, "scope": "current_filters_selectable_rows"}
        if action == "table_open":
            result.update(row_activation_acknowledged=False, command_semantics="choose_row", data_changed=None)
        if action in {"table_select", "table_open"}:
            try:
                row,reason=self.native_select(table,columns,match,signature,open_row=action == "table_open")
                if reason:return dict(result,success=False,postcondition_verified=False,reason=reason,data_changed=False)
            except RowsUnproven:
                if not self.modern_rows():return dict(result,success=False,postcondition_verified=False,reason="row_uniqueness_unproven",complete=False,data_changed=False)
                # Modern clients can prove a singleton with an empty-selection bulk read.
                # This continuation is only after fully decoded replies, never after an RPC error.
            else:
                if action == "table_open":
                    return self.activate_row(table, columns, row, match, signature, result)
                return dict(result,success=True,postcondition_verified=True,row=self.project(row,columns),data_changed=False)
        if action != "table_add":
            try:
                rows = [self.project(row, columns) for row in self.bulk(table, signature)]
            except RowsUnproven:
                return dict(result,success=False,postcondition_verified=False,reason="table_rows_unproven",complete=False,row_count=None,data_changed=False)
            matches = [row for row in rows if all(row[n] == v for n,v in match.items())]
            if action == "table_read":
                offset, limit = int(request.get("offset",0)), int(request.get("max_rows",10))
                selected = [{n:r[n] for n in request["columns"]} for r in matches[offset:offset+limit]]
                if len(json.dumps(selected,ensure_ascii=False).encode('utf-8')) > 65536:
                    raise self.Rejected("Requested table slice exceeds 64 KiB; request fewer rows/columns")
                result.update(rows=selected, row_count=len(matches), returned_rows=len(selected), truncated=offset+limit<len(matches))
                verified = "expect_count" in request or "expect_rows" in request
                passed = request.get("expect_count",len(matches)) == len(matches)
                if "expect_rows" in request:
                    canon=lambda r:json.dumps(r,sort_keys=True,ensure_ascii=False)
                    passed = passed and Counter(map(canon,selected)) == Counter(map(canon,request["expect_rows"]))
                return dict(result,success=passed,postcondition_verified=verified and passed)
            if len(matches) != 1:
                return dict(result,success=False,postcondition_verified=False,reason="row_not_found" if not matches else "ambiguous_row",match_count=len(matches),data_changed=False)
            current = self.seek(table, {n:columns[n] for n in match}, match, signature)
            if action in {"table_select", "table_open"}:
                try:
                    selected_row = current if action == "table_open" else self.selected_current(table)
                except RowsUnproven:
                    self.guard(signature)
                    return dict(result, success=False, postcondition_verified=False,
                                reason="row_uniqueness_unproven", complete=False, data_changed=False)
                selected=self.project(selected_row,columns)
                self.guard(signature)
                if selected!=matches[0]:raise self.Rejected("Selected row changed after full-table uniqueness check")
                if action == "table_open":
                    return self.activate_row(table, columns, selected_row, match, signature, result)
                return dict(result,success=True,postcondition_verified=True,row=selected,data_changed=False)
        else:
            if self.flag(self.w.guids.CURRENT_MODE_IS_EDIT, table) is not False:
                raise self.Rejected("Finish existing row editing before adding a row")
            try:
                before = self.bulk(table, signature)
            except RowsUnproven:
                return dict(result,success=False,postcondition_verified=False,reason="table_rows_unproven",complete=False,data_changed=False)
            self.w.mutate(self.w.guids.ACTIVATE, table["key"], handle=table["handle"])
            self.w.mutate(self.w.guids.ADD_ROW, table["key"], handle=table["handle"])
            self.guard(signature)
            if self.flag(self.w.guids.CURRENT_MODE_IS_EDIT, table) is True:
                self.w.mutate(self.w.guids.END_EDIT_ROW, table["key"], b"\xe1", table["handle"])
            # Native ok only acknowledges AddRow: a handler can cancel it. Establish
            # one new descriptor and its identity before sending any cell text.
            try:
                after = self.bulk(table, signature)
            except RowsUnproven:
                raise self.Rejected("Cannot verify added row; no cell input sent")
            canon = lambda row: json.dumps(row,sort_keys=True,ensure_ascii=False)
            old, now = Counter(map(canon,before)), Counter(map(canon,after))
            delta = now-old
            current = self.current(table)
            if len(after)!=len(before)+1 or old-now or sum(delta.values())!=1 or current is None or delta.get(canon(current))!=1 or now[canon(current)]!=1:
                raise self.Rejected("AddRow did not establish one distinct new current row; no cell input sent")
        anchor = current if action == "table_add" else self.current(table)
        if action == "table_set" and (anchor is None or self.project(anchor, {n:columns[n] for n in match}) != match):
            raise self.Rejected("Target row changed before editing")
        self.edit(table, columns, cells, signature, anchor)
        # Read back all cells together after the final edit (handlers can alter earlier cells).
        actual = {n:self.read_cell(table,columns[n]) for n in cells}
        for n in cells:
            if columns[n].get("type") == "CheckBoxField":
                try: actual[n] = checked(actual[n])
                except ValueError: actual[n] = None
        return dict(result,success=actual==cells,postcondition_verified=actual==cells,cells=actual,data_changed=True)
