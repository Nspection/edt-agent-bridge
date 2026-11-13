# Agent Host Native API transport

Windows x64, C++17, static CRT. Only `127.0.0.1` is used; redirects and proxies are disabled. Eleven Native API methods configure the session, poll one command, report its result/identity, stop, capture its own windows, advertise capabilities and arm/read/cancel a transient popup capture. BSL is never called on a network thread. The BSL form polls once per second in the 1C event loop and invokes its own server methods for server context.

Build with a local MSVC x64 compiler and Windows SDK:

```powershell
python tools/build-native-host.py --output .runs/native-host/release
```

The script builds and runs `NativeContractTest.exe` (platform-owned method-name memory is allocated with IMemoryManager and may be freed by the caller), then creates `agent-host-native-windows-x64.zip` plus SHA256 metadata. Build output stays outside source/Git. `--diagnostics` is for private troubleshooting only: it writes static Native API lifecycle markers to `%TEMP%/EDT-Agent-Host-native.log`, without tokens, BSL or data. Never publish a diagnostic build.

SDK header source and MIT notice: `include/LICENSE`, repository `THIRD_PARTY_NOTICES.md`. AgentHost.cpp is original Bridge code under AGPL-3.0-only.

## Screenshots (protocol 2)

`Capabilities()` returns `screenshot_png`, `own_window_composite_v1` and `armed_popup_capture_v1`. Existing method IDs 0–6 are preserved; CaptureState/ArmScreenshot/CancelScreenshot/TakeArmedScreenshot extend the ABI. `Screenshot()` always captures current surfaces; it never consumes an armed frame.

The main surface is the largest visible non-minimized ownerless window of the current process. Up to 16 own top-level/owned/popup surfaces are composited in Z order and actual coordinates; gaps are black. No desktop pixels, arbitrary HWNDs, foreign top-level windows or visible foreign embedded child windows are captured. Rendering uses WM_PRINT; cross-thread timeout is a failure with no unbounded PrintWindow retry. A same-thread renderer is synchronous and cannot be preempted. PNG limits remain 16 MiB, 8192 per dimension, 24 million pixels; host storage is bounded to 64 images / 128 MiB.

`ArmScreenshot("popup")` installs current-UI-thread own-process show/menu hooks before the client BSL operation. For 5 seconds it accepts a new visible captionless WS_POPUP that is not a child or baseline window. It immediately renders the root and own surfaces into memory. A newly opened captioned card does not consume the observer. `TakeArmedScreenshot()` uploads the cached frame even after dismissal. An absent/disappeared candidate or expired/disarmed observer fails explicitly; business BSL is never replayed for another capture. Cancellation/stop/terminal batch completion release hooks and cached data. Optional arming falls back to a current snapshot. Other GUI threads' popup events are not observed by the immediate hook.

Batch `screenshot_expect="popup"` requires `screenshot=true`, one `kind=execute` in `context=client`, and both Native/host capabilities. Failure evidence captures the current form rather than an earlier cached popup. Returned receipts preserve actual `captured_at`, separate upload time, scope, surface counts and arming provenance through compact/recovered views. `popup_count` counts additional own surfaces; it is not a semantic menu-item count. `pixel_content_verified=false` is intentional: callers must inspect the image. Rendering success or a PNG never proves expected text.

New Native hosts default to `presentation=isolated`: the child starts on its own unselected Windows desktop, before its first window. No SwitchDesktop or visible fallback occurs. Existing hosts retain visible mode; a running host cannot silently change it. Interactive startup input is unavailable on isolated desktops; explicitly choose visible at prepare/start for any required one-time trust setup, then stop and restart isolated. Security settings are never weakened. Child stdio is not captured in isolated mode; 1C's configured client log and startup stage remain available. User desktops are not captured or restored heuristically.

Validation includes an actual-DLL probe launched with `--desktop-probe DLL PORT` on a desktop whose name starts with EDTBridge-. It creates no windows otherwise. A three-band popup is destroyed before upload and its PNG pixels are checked; ordinary ABI tests never show windows. Own 1C 8.3.27.2325 and 8.5.1.1522 menu images are separately inspected. These checks do not establish support for every 1C renderer or every popup class.
