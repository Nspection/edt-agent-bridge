# Table stage release review — 2026-11-10

Primary review and an independent read-only agent review found and reproduced three P1 issues and one P2. All were corrected and independently rechecked.

- **P1 empty-table false green:** no selected rows plus no current row did not prove table emptiness. Empty/unavailable bulk now returns a known failure with incomplete/null count; zero-count assertions do not pass.
- **P1 unproven AddRow:** native acknowledgement can hide a cancelled add. Cell input now requires a verified nonempty before set, finalized row, preserved before multiset, exactly one additional unique descriptor and current==that descriptor. The proven current snapshot is retained through entry to editing, including a separate regression for redirection after proof. Ambiguous descriptors and blank-row validation cause refusal, not guessing.
- **P1 EndEdit redirection:** the old code silently accepted another current row as the next anchor. Each requested cell now updates only its expected title in an immutable descriptor; current must equal that expected descriptor after EndEdit. A redirected row cannot receive the next cell.
- **P2 mixed postconditions:** window and field expectations were evaluated against different captures. Both now use one final capture.

Regression fakes now copy row descriptors like the native decoder. 16 table contracts pass, including all reproductions and the add-proof follow-up. Existing worker contracts and the full Java suite remain required for the exact release artifact. The reviewer did not change files or operate EDT/databases.

Release limits are intentional: flat multiple-selection tables with a provable nonempty baseline; nonempty ordinary InputField text; blank-row finalization allowed by the form; unknown outcomes never replay. The reviewed live fixture uses a small server initializer to create the initial synthetic row; it does not bypass the production proof guard.
