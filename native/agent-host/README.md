# Agent Host Native API transport

Windows x64, C++17, static CRT. Only `127.0.0.1` is used; redirects and proxies are disabled. Seven Native API methods configure the session, poll one command, report its result/identity, stop, capture its own window, and advertise capabilities. BSL is never called on a network thread. The BSL form polls once per second in the 1C event loop and invokes its own server methods for server context.

Build with a local MSVC x64 compiler and Windows SDK:

```powershell
python tools/build-native-host.py --output .runs/native-host/release
```

The script builds and runs `NativeContractTest.exe` (platform-owned method-name memory is allocated with IMemoryManager and may be freed by the caller), then creates `agent-host-native-windows-x64.zip` plus SHA256 metadata. Build output stays outside source/Git. `--diagnostics` is for private troubleshooting only: it writes static Native API lifecycle markers to `%TEMP%/EDT-Agent-Host-native.log`, without tokens, BSL or data. Never publish a diagnostic build.

SDK header source and MIT notice: `include/LICENSE`, repository `THIRD_PARTY_NOTICES.md`. AgentHost.cpp is original Bridge code under AGPL-3.0-only.

## Screenshots (protocol 2)

`Capabilities()` returns `screenshot_png`. `Screenshot()` captures the largest visible, non-minimized, ownerless window belonging to the current 1C process with PrintWindow, encodes PNG in memory, and uploads it to the authenticated localhost `/snapshot` route. It accepts no HWND, screen rectangle, destination path or foreign process. A delivered runtime request is required. The returned JSON contains snapshot_id, request_id, image_path, sha256, dimensions and captured_at. Limits: 16 MiB per PNG, 8192 per dimension, 24 million pixels, 64 images / 128 MiB per host. Storage is removed by owned-host cleanup; copy required evidence before cleanup.

`КомпонентаАгента.Screenshot()` can be called by client BSL. A continuous BSL call does not let 1C repaint its interface; use runtime `execution_mode=batch` with `screenshot=true` after a UI-changing step. The host yields between the step and capture. No arbitrary form/window capture is promised: the native method captures the current process main window, not the whole desktop, other applications, minimized windows, or guaranteed modal content. PrintWindow output depends on platform rendering; a valid PNG is not semantic proof of the expected form. Verify important visual checkpoints.
