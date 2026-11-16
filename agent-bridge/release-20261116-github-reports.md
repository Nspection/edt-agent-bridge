# EDT Agent Bridge 1.4.0.20261116reports1

GitHub bug reports now have a clear one-time browser login or protected token entry. The verified account is read-only, with a separate live connection check and last verification time. Credentials remain in the workspace-specific Windows Credential Manager namespace; they never enter MCP arguments or chat.

Agents can initiate login with auth_start, prepare any explicit title/description with prepare_async, and open the exact public preview with review_request. The user Send button publishes the approved text. Custom descriptions always require that exact approval, including under automatic technical-report policy. Technical journal collection remains allowlisted; no automatic source, log or screenshot attachments.

Durable request_status receipts and stable keys keep MCP responsive while a person signs in or reviews text. Restart, cancellation, queue saturation, policy/account changes and ambiguous delivery preserve the original operation and prevent duplicate POSTs. Legacy prepare/submit/reconcile remain available.

Validated in an owned isolated EDT: real protected SWT token/save button, GitHub /user verification, settings buttons, cancellation, exact SWT Send and one real issue (#42, closed after verification), repeated request/send deduplication. Final artifact live verification repeats saved-account authentication without entering credentials. Browser OAuth was not completed anew in this run; token login and actual issue publication were verified. Native/TestClient binaries and EPF are unchanged; no new database/client run was needed. User EDT, databases and x5 files were not changed.

Independent Astra medium and own review completed. Full regression, OSGi/product boundary/provenance and copied-profile p2 verification are required before publication. Existing reports about broad form-owner serialization and blank modal Native snapshots remain separate investigations; this release does not claim to fix them.

Both errors (report_type=bug) and improvement proposals (report_type=proposal) use the same explicit title/description and exact approval. Issues have [Bug report] or [Proposal] prefixes; no separate label permission is required. The legacy tool name edt_bug_report remains for compatibility.

Final proposal acceptance: real MCP prepare_async(report_type=proposal), persisted status, SWT exact-text preview and Send produced issue #43 with the exact title/body; it was closed after verification. Final artifact also reverified the saved GitHub account and returned original issue #42 without a new POST.
