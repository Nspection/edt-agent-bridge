# SPDX-License-Identifier: AGPL-3.0-only
"""One-shot answers to a captured application confirmation; never recover/replay input."""
import hashlib
import json
import re

# MessageBox85 is a renderer, not a security classification. Fail closed for known
# privileged topics as well as requiring an explicit, exact application-only grant.
PRIVILEGED = re.compile(r"парол|password|лиценз|licen[cs]|довер|trust|безопас|security|сертифик|certificat|аутентиф|authentic|привилег|privileg|разрешени[ея] на доступ|access permission", re.I)
BUTTONS = {"Да", "Нет", "Отмена", "Yes", "No", "Cancel"}

class DialogEngine:
    def __init__(self, worker, rejected):
        self.w, self.reject = worker, rejected

    def read(self):
        first = self._read()
        second = self._read()
        if first != second:
            raise self.reject("Confirmation changed during bounded double inspection")
        return second

    def _read(self):
        w = self.w
        if getattr(w.client, "_pending", None) != 0:
            raise self.reject("Dialog protocol boundary is unproven; no resync is allowed")
        window, form, public = w.form()
        if not form or public.get("form_name") != "MessageBox85":
            raise self.reject("Only the captured MessageBox85 confirmation is supported")
        elements = w.elements(form)
        message = w.one(elements, "Message", {"Decoration"})
        text = message.get("title")
        if not isinstance(text, str) or not text or len(text) > 4096:
            raise self.reject("Confirmation text is unavailable or exceeds limit")
        if PRIVILEGED.search(text + " " + public.get("title", "")):
            raise self.reject("Security, trust, password and license prompts require manual handling")
        buttons = []
        for item in elements:
            if item.get("class") != "Button":
                continue
            visible = w.wire.decode_bool(w.send(w.guids.CURRENT_VISIBLE, item["key"], middle=b"\xe1", handle=item["handle"]))
            if visible is False:
                continue
            if visible is not True:
                raise self.reject("Button visibility is unknown")
            enabled = w.wire.decode_bool(w.send(w.guids.CURRENT_ENABLE, item["key"], middle=b"\xe1", handle=item["handle"]))
            if enabled is not True or item.get("title") not in BUTTONS:
                raise self.reject("Unsupported or unavailable confirmation button")
            buttons.append({k: item.get(k) for k in ("key", "handle", "name", "title")})
        if not 2 <= len(buttons) <= 3 or len({b["title"] for b in buttons}) != len(buttons):
            raise self.reject("Confirmation buttons are absent or ambiguous")
        # Descriptor text and button names can change without replacing the form.
        after = w.elements(form)
        descriptors = lambda rows: [{k: item.get(k) for k in ("key", "handle", "name", "title", "class", "type")} for item in rows]
        if descriptors(elements) != descriptors(after):
            raise self.reject("Confirmation content changed while reading button state")
        again_window, again_form, _ = w.form()
        identity = lambda win, frm: [win.get("key"), win.get("handle"), frm.get("key"), frm.get("handle")]
        signature = identity(window, form)
        targets = [message] + buttons
        if any(not isinstance(v,str) or not v for v in signature) or any(not item.get("key") or not item.get("handle") for item in targets):
            raise self.reject("Confirmation identity or target handle is unproven")
        if signature != identity(again_window, again_form or {}):
            raise self.reject("Confirmation changed during inspection")
        data = dict(identity=signature, text=text, title=public.get("title"), message={k: message.get(k) for k in ("key", "handle")}, buttons=buttons)
        data["dialog_hash"] = hashlib.sha256(json.dumps(data, sort_keys=True, ensure_ascii=False).encode()).hexdigest()
        return data

    def inspect(self, operation):
        w = self.w
        bound = getattr(w, "blocked_dialog", None)
        if not w.mutations_blocked or not bound or operation != bound["operation_id"]:
            raise self.reject("No confirmation captured at the original rejected operation")
        fresh = self.read()
        if fresh["dialog_hash"] != bound["dialog_hash"]:
            raise self.reject("Original confirmation changed; no answer is allowed")
        return dict(success=True, dialog_hash=fresh["dialog_hash"], text=fresh["text"],
                    buttons=[dict(name=b["name"], title=b["title"]) for b in fresh["buttons"]],
                    blocking_operation_id=operation, mutation_blocked=True,
                    original_outcome_unknown=True, application_classification="requires_explicit_authorization",
                    answer_available=not bound.get("answer_attempted", False))

    def answer(self, request):
        w = self.w
        result = self.inspect(request.get("blocking_operation_id"))
        bound = w.blocked_dialog
        if bound.get("answer_attempted") or request.get("dialog_hash") != bound["dialog_hash"]:
            raise self.reject("Confirmation is stale or already answered")
        grant = request.get("authorization")
        if not isinstance(grant, dict) or grant != {"application_confirmation": True, "dialog_hash": bound["dialog_hash"],
                "base_uuid": request.get("base_uuid"), "button": request.get("button"),
                "blocking_operation_id": bound["operation_id"]}:
            raise self.reject("An exact one-shot application confirmation authorization is required")
        button = w.one(bound["buttons"], request.get("button"))
        # Consumed before either CLICK frame. Unknown outcomes never receive another attempt.
        bound["answer_attempted"] = True
        w.stage = "dialog_answer"
        w.mutate(w.guids.CLICK, button["key"], handle=button["handle"])
        return dict(success=True, answer_acknowledged=True, dialog_hash=bound["dialog_hash"],
                    blocking_operation_id=bound["operation_id"], original_outcome_unknown=True,
                    mutation_blocked=True, postcondition_verified=False, action_dispatched=w.dispatched,
                    message="Answer acknowledged; observe the business result. Original operation remains unknown; no replay or barrier release.")
