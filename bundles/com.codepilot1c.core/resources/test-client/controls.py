# SPDX-License-Identifier: AGPL-3.0-only
"""Typed TestClient controls. Wire contracts: pinned ROCTUP/1c-testpilot (SOURCE.json).
Desired state is observed, never guessed; no mutation is replayed after an unknown reply.
"""
import datetime
import json
from decimal import Decimal
import re
import time

ACTIONS = frozenset({"check", "uncheck", "tab", "activate", "choose_value", "expand", "collapse", "increase", "decrease", "set_position", "set_date", "open_choices"})


def potential_actions(item):
    cls, kind = item.get("class"), item.get("type")
    if cls == "Button": return ["command", "activate"]
    if cls == "Decoration" and kind in {"Label", "Picture"}: return ["command"]
    if cls == "Group":
        return ["tab", "activate"] if kind == "Page" else ["expand", "collapse"] if kind == "UsualGroup" else []
    if cls != "EditField": return []
    return {"InputField": ["input", "open_choices", "choose_value", "activate", "increase", "decrease"],
            "CheckBoxField": ["check", "uncheck", "activate"],
            "RadioButtonField": ["open_choices", "choose_value", "activate"],
            "CalendarField": ["set_date", "activate"],
            "TrackBarField": ["set_position", "activate"],
            "LabelField": ["command"], "PictureField": ["command"]}.get(kind, [])


def checked(text):
    if not isinstance(text, str) or text.strip().casefold() not in {"\u0434\u0430", "\u043d\u0435\u0442", "yes", "no"}:
        raise ValueError("Checkbox state is not a known binary Yes/No value")
    return text.strip().casefold() in {"\u0434\u0430", "yes"}


def decimal(text):
    if not isinstance(text, str) or not re.fullmatch(r"[-+]?\d+(?:[.,]\d+)?", text.strip()):
        raise ValueError("Numeric value is unavailable or ambiguously formatted")
    return Decimal(text.strip().replace(",", "."))




class ControlEngine:
    def __init__(self, worker, rejected):
        self.w, self.Rejected = worker, rejected

    def guard(self, signature):
        window, form, public = self.w.form()
        if (window["key"], (form or {}).get("handle")) != signature:
            raise self.Rejected("Active form changed during control operation")

    def flag(self, guid, item):
        return self.w.wire.decode_bool(self.w.send(guid, item["key"], middle=b"\xe1", handle=item["handle"]))

    def objects(self, guid, item):
        raw = self.w.send(guid, item["key"], middle=b"\xe0\x4b\x55", handle=item["handle"])
        return self.w.wire.decode_collection(raw, item["key"])

    def page_selected(self, item, by_key):
        parent = by_key.get(item.get("parent"))
        if not parent or parent.get("type") != "Pages":
            raise self.Rejected("Page owner is absent or is not a Pages group")
        current = self.objects(self.w.guids.GET_CURRENT_PAGE, parent)
        if len(current) != 1: raise self.Rejected("Current page is absent or ambiguous")
        return (current[0].get("key"), current[0].get("handle")) == (item["key"], item["handle"])

    def ready(self, item, by_key, signature, editable=False):
        for guid in (self.w.guids.CURRENT_VISIBLE, self.w.guids.CURRENT_ENABLE):
            if self.flag(guid, item) is not True: raise self.Rejected("Control is hidden, disabled or its state is unknown")
        if editable and self.flag(self.w.guids.CURRENT_READONLY, item) is not False:
            raise self.Rejected("Control is read-only or its state is unknown")
        seen = set()
        parent = by_key.get(item.get("parent"))
        while parent:
            if parent["key"] in seen: raise self.Rejected("Cyclic control parent chain")
            seen.add(parent["key"])
            if parent.get("class") == "Group" and parent.get("type") == "Page" and not self.page_selected(parent, by_key):
                raise self.Rejected("Control belongs to an inactive page; activate its page first")
            parent = by_key.get(parent.get("parent"))
        self.guard(signature)

    def choices(self, item):
        # This is an observational wire variant, not an action or commit.
        raw = self.w.send(self.w.guids.GET_CHOICE_LIST, item["key"], kind="read818", middle=b"\xe2\xcb\x55", handle=item["handle"])
        values, status = self.w.wire.decode_choice_items(raw)
        if status != "ok" or not values or len(values) > 256 or len(json.dumps(values, ensure_ascii=False).encode("utf8")) > 65536:
            raise self.Rejected("Choice list is absent, unknown or exceeds 256 items")
        return values

    def wait_value(self, probe, predicate, request, signature):
        seconds = request.get("wait_timeout_ms", 10000) / 1000
        deadline = min(time.monotonic() + seconds, getattr(self.w, "deadline", time.monotonic() + seconds))
        while True:
            self.guard(signature)
            actual = probe()
            self.guard(signature)
            now = time.monotonic()
            if predicate(actual) and (seconds == 0 or now <= deadline): return actual, True
            if now >= deadline: return actual, False
            time.sleep(min(.1, deadline - now))

    def run(self, request, form, elements, snapshot=None):
        action, name = request.get("action"), request.get("element")
        if not isinstance(name, str) or not name or len(name) > 128:
            raise self.Rejected("Exact bounded element name is required")
        item = self.w.one(elements, name)
        if action not in potential_actions(item): raise self.Rejected("Action is unsupported for this exact control type")
        if snapshot is not None:
            prior = self.w.one(snapshot["elements"], name)
            if (prior["key"], prior["handle"]) != (item["key"], item["handle"]):
                raise self.Rejected("Control changed since observation")
        signature = (self.w.form()[0]["key"], form["handle"])
        def mutate(*args, **kwargs):
            self.guard(signature)
            return self.w.mutate(*args, **kwargs)
        by_key = {x["key"]: x for x in elements}
        kind = item.get("type")
        value = request.get("value")
        if action == "choose_value" and (not isinstance(value, str) or not value or len(value) > 4096):
            raise self.Rejected("choose_value requires exact nonempty choice text")
        if action == "set_position" and (type(request.get("position")) not in (int, float) or not 0 <= request["position"] <= 100 or request["position"] % 1):
            raise self.Rejected("position must be an integer percentage 0..100")
        target_date = None
        if action == "set_date":
            try:
                if not isinstance(request.get("date"), str) or not re.fullmatch(r"\d{4}-\d{2}-\d{2}", request["date"]): raise ValueError()
                target_date = datetime.date.fromisoformat(request["date"])
            except ValueError: raise self.Rejected("date must be a valid ISO calendar date")
        if action == "set_date" and name in request.get("expect", {}).get("fields", {}):
            raise self.Rejected("Calendar verification requires a different bound scalar field, not the unsupported calendar getter")
        self.ready(item, by_key, signature, editable=kind in {"InputField", "CheckBoxField", "RadioButtonField", "CalendarField", "TrackBarField"})
        result = {"name": name, "type": kind, "changed": False, "state_verified": False}
        self.w.stage = "control." + str(action)
        if action in {"check", "uncheck"}:
            try: before = checked(self.w.value(item))
            except ValueError as e: raise self.Rejected(str(e))
            wanted = action == "check"
            if before != wanted: mutate(self.w.guids.SET_CHECK, item["key"], handle=item["handle"])
            def known_state():
                try: return checked(self.w.value(item))
                except ValueError: return None
            actual, verified = self.wait_value(known_state, lambda v: v is wanted, request, signature)
            result.update(changed=before != actual, value_before=before, value_after=actual, state_verified=verified)
        elif action in {"tab", "activate"}:
            is_page = kind == "Page"
            probe = (lambda: self.page_selected(item, by_key)) if is_page else (lambda: any((x.get("key"), x.get("handle")) == (item["key"], item["handle"]) for x in self.objects(self.w.guids.FORM_GET_CURRENT_ITEM, form)))
            before = probe()
            if not before: mutate(self.w.guids.ACTIVATE, item["key"], handle=item["handle"])
            actual, verified = self.wait_value(probe, lambda v: v is True, request, signature)
            result.update(changed=not before and actual, state_verified=verified)
        elif action in {"expand", "collapse", "command"}:
            guid = (self.w.guids.EXPAND_GROUP if action == "expand" else self.w.guids.COLLAPSE_GROUP if action == "collapse" else self.w.guids.CLICK_DECORATION if item["class"] == "Decoration" else self.w.guids.CLICK_FIELD)
            mutate(guid, item["key"], handle=item["handle"])
            result.update(changed=None, verification="ack_only_requires_explicit_expect")
        elif action == "open_choices":
            if kind == "InputField" and self.flag(self.w.guids.DROP_LIST_IS_OPEN, item) is not True:
                mutate(self.w.guids.OPEN_DROP_LIST, item["key"], handle=item["handle"])
                if self.flag(self.w.guids.DROP_LIST_IS_OPEN, item) is not True:
                    raise self.Rejected("Drop-down did not open")
            result.update(choices=self.choices(item), state_verified=True, verification="choice_list_only_no_value_selected")
        elif action == "set_date":
            mutate(self.w.guids.GOTO_DATE, item["key"], self.w.wire.mk_date(target_date.year, target_date.month, target_date.day), item["handle"])
            result.update(value_before=None, value_after=None, changed=None, date=request["date"], verification="date_command_ack_only_requires_explicit_expect")
        else:
            try: before = self.w.value(item)
            except self.Rejected:
                if action == "choose_value" and kind == "RadioButtonField": before = None
                else: raise
            if action == "choose_value":
                if before == value:
                    result.update(value_before=before, value_after=before, state_verified=True, verification="exact_display_text_not_reference_identity")
                    self.guard(signature)
                    return result
                if kind == "InputField":
                    mutate(self.w.guids.OPEN_DROP_LIST, item["key"], handle=item["handle"])
                    if self.flag(self.w.guids.DROP_LIST_IS_OPEN, item) is not True: raise self.Rejected("Drop-down did not open")
                choices = self.choices(item)
                matching = [x for x in choices if x.get("text") == value]
                if len(matching) != 1: raise self.Rejected("Exact choice text is absent or ambiguous")
                if self.choices(item) != choices: raise self.Rejected("Choices changed; selection was not sent")
                self.guard(signature)
                mutate(self.w.guids.SELECT_OPTION if kind == "RadioButtonField" else self.w.guids.CHOOSE_FROM_DROP_LIST,
                              item["key"], self.w.wire.mk_choice(value), item["handle"])
                expected = {value, matching[0].get("presentation")}; expected.discard(None)
                actual, verified = self.wait_value(lambda: self.w.value(item), lambda v: v in expected, request, signature)
            elif action in {"increase", "decrease"}:
                try: prior = decimal(before)
                except ValueError as e: raise self.Rejected(str(e))
                mutate(self.w.guids.INCREASE_VALUE if action == "increase" else self.w.guids.DECREASE_VALUE, item["key"], handle=item["handle"])
                def changed(v):
                    try: return decimal(v) > prior if action == "increase" else decimal(v) < prior
                    except ValueError: return False
                actual, verified = self.wait_value(lambda: self.w.value(item), changed, request, signature)
            else:
                mutate(self.w.guids.GOTO_VALUE, item["key"], self.w.wire.mk_goto_value(int(request["position"])), item["handle"])
                actual = self.w.value(item)
                verified = isinstance(value, str) and actual == value
                result["verification"] = "exact_display_value" if value is not None else "percentage_command_ack_only_supply_value_or_expect"
            result.update(value_before=before, value_after=actual, changed=None if before is None else before != actual, state_verified=verified)
        if action != "command": self.guard(signature)
        return result
