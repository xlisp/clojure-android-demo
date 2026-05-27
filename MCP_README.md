# `mcp_server.py` — host-side MCP bridge to the on-device nREPL

A single-file Python MCP server that lets any MCP client (Claude Code,
Claude Desktop, Codex, etc.) drive the running Android app by sending Clojure
to the on-device nREPL on **port 6688** (`MyApp.NREPL_PORT`).

```
MCP client  ──stdio JSON-RPC──▶  mcp_server.py  ──bencode TCP──▶  device nREPL :6688
```

Why a *host-side* MCP server (vs. the in-app `demo.mcp` on 6689)? Zero extra
on-device compile cost, no MCP Java SDK packaged in the APK, works on every
API level the nREPL itself works on (≥ 26).

## Requirements

- Python 3.10+ (stdlib only, no `pip install`)
- A running device/emulator with the demo app launched (nREPL announces itself
  in `logcat -s ClojureDemo`)
- `localhost:6688` reaches the device — either via `adb forward tcp:6688 tcp:6688`
  or, if you're running on the Android device itself (e.g. Termux), nothing to
  do.

## Wire into Claude Code

`~/.claude/settings.json` (or repo-local `.mcp.json`):

```json
{
  "mcpServers": {
    "clojure-android": {
      "command": "python3",
      "args": ["/data/data/com.termux/files/home/cljpro/clojure-android-demo/mcp_server.py"]
    }
  }
}
```

Restart Claude Code; the `clojure-android` server should show up in `/mcp`.

### Custom host/port

```json
"args": ["/path/to/mcp_server.py", "--nrepl-host", "192.168.1.42", "--nrepl-port", "6688"]
```

Or via env vars: `NREPL_HOST`, `NREPL_PORT`.

## TCP mode (for the existing `tests/mcp_test.py`)

```bash
python3 mcp_server.py --tcp 6690
python3 tests/mcp_test.py 6690
```

Same JSON-RPC, newline-delimited, on a TCP socket — same surface, just a
different transport.

## Tools

| Tool | Args | What it does |
|---|---|---|
| `clojure_eval` | `code` (str), `ns` (default `"user"`) | Evaluate Clojure forms on-device. State persists across calls. |
| `clojure_require` | `ns` (str), `reload` (bool) | `(require 'ns)` — first load triggers d8/DEX compile, may be slow. |
| `clojure_load_file` | `path` (str) | Read a `.clj` from the host and `load-string` it on-device. |
| `android_ui_show` | — | `(require 'demo.ui :reload)` + `(demo.ui/show!)`. Swaps the Clojure-built View onto the foreground Activity. |
| `android_toast` | `message` (str), `long` (bool) | Post a Toast on the main looper using the app `Context` stashed in `DalvikDynamicClassLoader/applicationContext`. |
| `android_vm_info` | — | Sanity probe: VM name/version, Android SDK + release, Clojure version, PID. |
| `nrepl_interrupt` | — | Send an `:interrupt` op on the current nREPL session. |

Tool results are returned as MCP `text` content with the captured stdout, then
the printed values prefixed by `=> `. On failure (eval exception, stderr,
`error`/`eval-error`/`namespace-not-found` status) the result is marked
`isError: true` and includes the exception class.

## Verified on a real device (Android 12 / SDK 31 / Dalvik / Clojure 1.13.0)

```text
clojure_eval     (+ 1 2 3)                        =>  6
clojure_eval     (def my-answer 42) (* my-answer 10)
                                                  =>  #'user/my-answer
                                                  =>  420
clojure_eval     (println "hi") my-answer        out: hi
                                                  =>  nil
                                                  =>  42        ;; state survived prior call
android_vm_info  —                               =>  {:vm "Dalvik", :vm-version "2.1.0",
                                                     :android-sdk 31, :android-release "12",
                                                     :clojure "1.13.0-master-SNAPSHOT", :pid 6768}
android_toast    {message:"hello 🎉", long:true}  =>  :toast-posted
android_ui_show  —                               =>  :shown
nrepl_interrupt  —                               status=["done","session-idle"]
```

Exceptions land cleanly:

```text
clojure_eval (throw (ex-info "boom" {:why :test}))
;; stderr:
Execution error (ExceptionInfo) at user/eval2235 (REPL:1).
boom
;; {"ex": "class clojure.lang.ExceptionInfo", "root-ex": "class clojure.lang.ExceptionInfo"}
isError: true
```

## How it works (load-bearing details)

- **Bencode + nREPL.** Minimal in-file bencode encoder/decoder; one persistent
  session (`clone` once, reuse `session` id for every `eval`); a single lock
  serializes ops so concurrent MCP `tools/call` requests don't interleave
  frames on the wire.
- **No Jackson, no SDK.** Stdlib `json` for MCP, hand-rolled bencode for nREPL.
- **Toast Context lookup.** `android_toast` reads the app `Context` from the
  static `clojure.lang.DalvikDynamicClassLoader/applicationContext` field
  (set by `MyApp.onCreate`), then posts to `Looper.getMainLooper()` — same
  pattern as `tests/user.clj`.
- **`android_ui_show` needs a foreground Activity.** `demo.ui/show!` reads
  `MyApp/currentActivity` (set by an `ActivityLifecycleCallbacks`). If the app
  is backgrounded it returns `:no-activity`.

## Troubleshooting

- **`nREPL I/O error: [Errno 111] Connection refused`** — the app isn't running
  or `adb forward` isn't set. Confirm with `logcat -s ClojureDemo` (look for
  "nREPL server listening on 0.0.0.0:6688").
- **First call hangs ~30s.** Expected on the very first `require` of nREPL or
  any new ns — d8 is compiling JVM bytecode to DEX on-device.
- **`:no-activity` from `android_ui_show`.** Bring the app to the foreground.
- **`ClassNotFoundException: java.lang.ProcessHandle`** — you're on a pre-Java-9
  API (Dalvik). Don't use `ProcessHandle/current`; use `android.os.Process/myPid`.

## Files

- `mcp_server.py` — the server.
- `tests/mcp_test.py` — minimal TCP client smoke test (run against `--tcp 6690`).
- `app/src/main/clojure/demo/mcp.clj` — the **on-device** sibling (port 6689,
  uses the MCP Java SDK). This Python server is the host-side equivalent.
