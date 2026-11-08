# Agent Host Native API transport

Windows x64, C++17, static CRT. Only `127.0.0.1` is used; redirects and proxies are disabled. Five Native API methods configure the session, poll one command, report its result/identity, and stop. BSL is never called on a network thread. The BSL form polls once per second in the 1C event loop and invokes its own server methods for server context.

Build with a local MSVC x64 compiler and Windows SDK:

```powershell
python tools/build-native-host.py --output .runs/native-host/release
```

The script builds and runs `NativeContractTest.exe` (platform-owned method-name memory is allocated with IMemoryManager and may be freed by the caller), then creates `agent-host-native-windows-x64.zip` plus SHA256 metadata. Build output stays outside source/Git. `--diagnostics` is for private troubleshooting only: it writes static Native API lifecycle markers to `%TEMP%/EDT-Agent-Host-native.log`, without tokens, BSL or data. Never publish a diagnostic build.

SDK header source and MIT notice: `include/LICENSE`, [contribution notices](../../docs/native-tools/THIRD_PARTY_NOTICES.md). AgentHost.cpp is original Bridge code under AGPL-3.0-only.
