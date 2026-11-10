# Python environment release review — 2026-11-10

Primary review and an independent read-only review covered environment ownership/inventory cleanup, managed selection/legacy paths, process lifetimes, shared leases, readiness, dependency pins and API integration. The independent reviewer rechecked the process fix and found no remaining confirmed blockers. User EDT and databases were not operated on.

**P1 fixed: subprocess completion was not proven.** On timeout, output overflow or interruption, the runner attempted termination but previously ignored incomplete wait/child-exit results. Preparation could then snapshot files as failed, which cleanup accepted. Abnormal execution now raises PreparationUnproven regardless of best-effort termination; preparation persists interrupted, removes any provisional inventory and refuses automatic cleanup/reuse. A regression covers this state and preservation. This is deliberately conservative: arbitrary detached descendants are not certified gone and there is no unsafe force-clean path.

**P2 fixed: architecture preflight.** A64-bit check alone could accept native Windows ARM64, while the approved lock contains AMD64 wheels. The probe now also requires sysconfig platform win-amd64 before environment creation/download. A regression proves ARM64 cannot pass just by reporting64-bit.

Final checks:702 Java tests, including19 environment contracts;7 worker and16 table Python contracts; standalone product boundary; exact packaged resource bytes; private p2 verifyOnly. The runtime cycle uses the own private EDT, locked CPython3.13 environment and a disposable file base8.5.1.1522. Detailed receipts and cleanup are recorded in [validation](python-environment-validation.md).

Remaining explicit limits: standard CPython3.11–3.14 AMD64 with venv/ensurepip already installed; live acceptance only on3.13; explicit package download may fail with network/TLS errors; interrupted setups are preserved for operator investigation instead of automatically deleting unknown process resources. No base Python or global package installation is performed.
