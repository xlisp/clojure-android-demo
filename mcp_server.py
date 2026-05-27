#!/usr/bin/env python3
"""Host-side MCP server that bridges to the on-device Clojure nREPL.

Talks MCP (JSON-RPC over stdio by default; --tcp PORT for socket mode) to the
client, and bencode/nREPL to the device on port 6688 (see MyApp.NREPL_PORT).

Prereq: `adb forward tcp:6688 tcp:6688` so localhost:6688 reaches the device.

Wire into Claude Code (~/.claude/settings.json or .mcp.json):
    {"mcpServers": {"clojure-android":
        {"command": "python3",
         "args": ["/abs/path/to/mcp_server.py"]}}}
"""
from __future__ import annotations

import argparse
import json
import os
import socket
import sys
import threading
import uuid

NREPL_HOST_DEFAULT = "127.0.0.1"
NREPL_PORT_DEFAULT = 6688

# ---------------------------------------------------------------- bencode ----

def bencode(obj) -> bytes:
    if isinstance(obj, str):
        b = obj.encode("utf-8")
        return f"{len(b)}:".encode() + b
    if isinstance(obj, (bytes, bytearray)):
        return f"{len(obj)}:".encode() + bytes(obj)
    if isinstance(obj, bool):
        return bencode(1 if obj else 0)
    if isinstance(obj, int):
        return f"i{obj}e".encode()
    if isinstance(obj, list):
        return b"l" + b"".join(bencode(x) for x in obj) + b"e"
    if isinstance(obj, dict):
        return b"d" + b"".join(
            bencode(k) + bencode(v) for k, v in sorted(obj.items())
        ) + b"e"
    raise TypeError(f"unbencodable: {type(obj).__name__}")


class BencodeReader:
    def __init__(self, sock: socket.socket):
        self.sock = sock
        self.buf = b""

    def _need(self, n: int) -> None:
        while len(self.buf) < n:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("nREPL connection closed")
            self.buf += chunk

    def _read(self, n: int) -> bytes:
        self._need(n)
        out, self.buf = self.buf[:n], self.buf[n:]
        return out

    def _read_until(self, ch: bytes) -> bytes:
        while ch not in self.buf:
            chunk = self.sock.recv(65536)
            if not chunk:
                raise EOFError("nREPL connection closed")
            self.buf += chunk
        i = self.buf.index(ch)
        out, self.buf = self.buf[:i], self.buf[i + 1:]
        return out

    def decode(self):
        self._need(1)
        t = self.buf[:1]
        if t == b"i":
            self._read(1)
            return int(self._read_until(b"e"))
        if t == b"l":
            self._read(1)
            out = []
            while self.buf[:1] != b"e" or (self._need(1) or False):
                if self.buf[:1] == b"e":
                    break
                out.append(self.decode())
            self._read(1)
            return out
        if t == b"d":
            self._read(1)
            out = {}
            while True:
                self._need(1)
                if self.buf[:1] == b"e":
                    break
                k = self.decode()
                if isinstance(k, bytes):
                    k = k.decode("utf-8", "replace")
                out[k] = self.decode()
            self._read(1)
            return out
        if t.isdigit():
            n = int(self._read_until(b":"))
            data = self._read(n)
            try:
                return data.decode("utf-8")
            except UnicodeDecodeError:
                return data
        raise ValueError(f"bad bencode token {t!r}")


# ------------------------------------------------------------ nREPL client ---

class NreplClient:
    """Minimal synchronous nREPL client. One persistent session, one lock."""

    def __init__(self, host: str, port: int, timeout: float = 180.0):
        self.host, self.port, self.timeout = host, port, timeout
        self.sock: socket.socket | None = None
        self.reader: BencodeReader | None = None
        self.session: str | None = None
        self.lock = threading.Lock()

    def _connect(self) -> None:
        if self.sock is not None:
            return
        sock = socket.create_connection((self.host, self.port), timeout=self.timeout)
        sock.settimeout(self.timeout)
        self.sock = sock
        self.reader = BencodeReader(sock)
        self.session = self._clone_session()

    def _send(self, msg: dict) -> None:
        assert self.sock is not None
        self.sock.sendall(bencode(msg))

    def _recv(self) -> dict:
        assert self.reader is not None
        m = self.reader.decode()
        if not isinstance(m, dict):
            raise ValueError(f"unexpected nREPL frame: {m!r}")
        return m

    def _drop(self) -> None:
        try:
            if self.sock is not None:
                self.sock.close()
        finally:
            self.sock = self.reader = self.session = None

    def _clone_session(self) -> str:
        mid = str(uuid.uuid4())
        self._send({"op": "clone", "id": mid})
        while True:
            r = self._recv()
            if r.get("id") == mid and "new-session" in r:
                return r["new-session"]
            if r.get("id") == mid and "done" in r.get("status", []):
                raise RuntimeError(f"clone failed: {r}")

    def eval(self, code: str, ns: str = "user") -> dict:
        with self.lock:
            try:
                self._connect()
                mid = str(uuid.uuid4())
                self._send({"op": "eval", "code": code, "ns": ns,
                            "session": self.session, "id": mid})
                outs, errs, values, status = [], [], [], []
                ex_info = {}
                while True:
                    r = self._recv()
                    if r.get("id") != mid:
                        continue
                    if "out" in r: outs.append(r["out"])
                    if "err" in r: errs.append(r["err"])
                    if "value" in r: values.append(r["value"])
                    if "ex" in r: ex_info["ex"] = r["ex"]
                    if "root-ex" in r: ex_info["root-ex"] = r["root-ex"]
                    if "status" in r:
                        status = r["status"]
                        if "done" in status:
                            break
                return {"value": values, "out": "".join(outs),
                        "err": "".join(errs), "status": status, "ex": ex_info}
            except (EOFError, OSError) as e:
                self._drop()
                raise RuntimeError(f"nREPL I/O error: {e}") from e

    def interrupt(self) -> list:
        with self.lock:
            self._connect()
            mid = str(uuid.uuid4())
            self._send({"op": "interrupt", "session": self.session, "id": mid})
            while True:
                r = self._recv()
                if r.get("id") == mid and "done" in r.get("status", []):
                    return r.get("status", [])

    def describe(self) -> dict:
        with self.lock:
            self._connect()
            mid = str(uuid.uuid4())
            self._send({"op": "describe", "id": mid})
            info = {}
            while True:
                r = self._recv()
                if r.get("id") != mid:
                    continue
                info.update({k: v for k, v in r.items()
                             if k not in ("id", "status")})
                if "done" in r.get("status", []):
                    return info


# ----------------------------------------------------------------- tools -----

def _clj_str(s: str) -> str:
    """Escape a Python string for embedding in a Clojure double-quoted string."""
    return '"' + s.replace("\\", "\\\\").replace('"', '\\"') + '"'


def _format_eval(r: dict) -> tuple[str, bool]:
    err = bool(r["err"]) or any(s in ("error", "eval-error", "namespace-not-found")
                                for s in r["status"])
    parts: list[str] = []
    if r["out"]:
        parts.append(r["out"].rstrip("\n"))
    if r["value"]:
        parts.append("=> " + "\n=> ".join(r["value"]))
    if r["err"]:
        parts.append(";; stderr:\n" + r["err"].rstrip("\n"))
    if r["ex"]:
        parts.append(";; " + json.dumps(r["ex"]))
    if not parts:
        parts.append(f";; (no output) status={r['status']}")
    return "\n".join(parts), err


TOOLS: list[dict] = [
    {
        "name": "clojure_eval",
        "description":
            "Evaluate Clojure code on the running Android device via nREPL (port 6688). "
            "State persists across calls (defs, requires, etc). Returns captured stdout "
            "and the printed values after `=>`. Examples: (+ 1 2), (System/getProperty \"java.vm.name\").",
        "inputSchema": {
            "type": "object",
            "properties": {
                "code": {"type": "string", "description": "Clojure forms to evaluate."},
                "ns":   {"type": "string", "description": "Namespace (default 'user').",
                          "default": "user"},
            },
            "required": ["code"],
        },
    },
    {
        "name": "clojure_require",
        "description":
            "Require (or :reload) a Clojure namespace on-device, e.g. demo.ui. "
            "First load of an unseen ns triggers d8/DEX compilation and can take a while.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "ns":     {"type": "string", "description": "Namespace symbol, e.g. demo.ui."},
                "reload": {"type": "boolean", "default": False,
                           "description": "Pass :reload to force re-evaluation."},
            },
            "required": ["ns"],
        },
    },
    {
        "name": "clojure_load_file",
        "description":
            "Read a .clj file from the host filesystem and load-string it on-device. "
            "Useful when iterating on code that isn't yet packaged in the APK.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "path": {"type": "string", "description": "Absolute path to a .clj file."},
            },
            "required": ["path"],
        },
    },
    {
        "name": "android_ui_show",
        "description":
            "Reload demo.ui on-device and call (demo.ui/show!) to swap its View onto "
            "the foreground Activity. Mirrors the in-app drawer item.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "android_toast",
        "description":
            "Show an Android Toast on the foreground Activity context. "
            "Returns :toast-posted on success.",
        "inputSchema": {
            "type": "object",
            "properties": {
                "message": {"type": "string"},
                "long":    {"type": "boolean", "default": True,
                            "description": "LENGTH_LONG when true, else LENGTH_SHORT."},
            },
            "required": ["message"],
        },
    },
    {
        "name": "android_vm_info",
        "description":
            "Sanity check: returns the device's java.vm.name / java.vm.version, the "
            "current Clojure version, and the nREPL session id.",
        "inputSchema": {"type": "object", "properties": {}},
    },
    {
        "name": "nrepl_interrupt",
        "description": "Send an :interrupt op to the current nREPL session.",
        "inputSchema": {"type": "object", "properties": {}},
    },
]


def _toast_code(msg: str, long_: bool) -> str:
    length = "LENGTH_LONG" if long_ else "LENGTH_SHORT"
    return (
        "(let [f (.getDeclaredField clojure.lang.DalvikDynamicClassLoader "
        "\"applicationContext\")"
        " _ (.setAccessible f true)"
        " ctx (.get f nil)"
        " h (android.os.Handler. (android.os.Looper/getMainLooper))]"
        f"  (.post h #(.show (android.widget.Toast/makeText ctx {_clj_str(msg)} "
        f"android.widget.Toast/{length})))"
        "  :toast-posted)"
    )


def run_tool(client: NreplClient, name: str, args: dict) -> tuple[str, bool]:
    if name == "clojure_eval":
        code = args.get("code")
        if not isinstance(code, str) or not code.strip():
            return "ERROR: missing 'code'", True
        return _format_eval(client.eval(code, args.get("ns") or "user"))

    if name == "clojure_require":
        ns = args.get("ns")
        if not isinstance(ns, str) or not ns:
            return "ERROR: missing 'ns'", True
        suffix = " :reload" if args.get("reload") else ""
        return _format_eval(client.eval(f"(require '{ns}{suffix})"))

    if name == "clojure_load_file":
        path = args.get("path")
        if not isinstance(path, str) or not os.path.isfile(path):
            return f"ERROR: file not found: {path}", True
        with open(path, "r", encoding="utf-8") as f:
            code = f.read()
        return _format_eval(client.eval(code))

    if name == "android_ui_show":
        return _format_eval(client.eval(
            "(do (require 'demo.ui :reload) (demo.ui/show!))"))

    if name == "android_toast":
        msg = args.get("message")
        if not isinstance(msg, str):
            return "ERROR: missing 'message'", True
        return _format_eval(client.eval(_toast_code(msg, bool(args.get("long", True)))))

    if name == "android_vm_info":
        code = ("{:vm (System/getProperty \"java.vm.name\")"
                " :vm-version (System/getProperty \"java.vm.version\")"
                " :clojure (clojure-version)"
                " :pid (.pid (java.lang.ProcessHandle/current))}")
        return _format_eval(client.eval(code))

    if name == "nrepl_interrupt":
        return f"status={client.interrupt()}", False

    return f"ERROR: unknown tool '{name}'", True


# ------------------------------------------------------------------ MCP -----

PROTOCOL_VERSION = "2024-11-05"
SERVER_INFO = {"name": "clojure-android-nrepl", "version": "0.1.0"}


def _reply(write, msg_id, result=None, error=None) -> None:
    out = {"jsonrpc": "2.0", "id": msg_id}
    if error is not None:
        out["error"] = error
    else:
        out["result"] = result
    write(json.dumps(out, ensure_ascii=False) + "\n")


def handle_message(client: NreplClient, msg: dict, write) -> None:
    method = msg.get("method")
    msg_id = msg.get("id")

    if method == "initialize":
        _reply(write, msg_id, {
            "protocolVersion": PROTOCOL_VERSION,
            "capabilities": {"tools": {"listChanged": False}},
            "serverInfo": SERVER_INFO,
        })
        return
    if method in ("notifications/initialized", "notifications/cancelled"):
        return
    if method == "ping":
        _reply(write, msg_id, {})
        return
    if method == "tools/list":
        _reply(write, msg_id, {"tools": TOOLS})
        return
    if method == "tools/call":
        params = msg.get("params") or {}
        name = params.get("name")
        args = params.get("arguments") or {}
        try:
            text, is_error = run_tool(client, name, args)
        except Exception as e:
            text, is_error = f"ERROR: {type(e).__name__}: {e}", True
        _reply(write, msg_id, {
            "content": [{"type": "text", "text": text}],
            "isError": is_error,
        })
        return
    if msg_id is not None:
        _reply(write, msg_id, error={"code": -32601,
                                     "message": f"method not found: {method}"})


def serve_stdio(client: NreplClient) -> None:
    def write(s: str) -> None:
        sys.stdout.write(s)
        sys.stdout.flush()
    for line in sys.stdin:
        line = line.strip()
        if not line:
            continue
        try:
            msg = json.loads(line)
        except json.JSONDecodeError as e:
            sys.stderr.write(f"[mcp] bad json: {e}\n")
            continue
        handle_message(client, msg, write)


def serve_tcp(client: NreplClient, host: str, port: int) -> None:
    srv = socket.create_server((host, port), reuse_port=False)
    sys.stderr.write(f"[mcp] listening on {host}:{port}\n")
    while True:
        conn, addr = srv.accept()
        sys.stderr.write(f"[mcp] client {addr}\n")
        threading.Thread(
            target=_serve_tcp_conn, args=(client, conn), daemon=True
        ).start()


def _serve_tcp_conn(client: NreplClient, conn: socket.socket) -> None:
    try:
        f_in = conn.makefile("r", encoding="utf-8", newline="\n")
        f_out = conn.makefile("w", encoding="utf-8", newline="\n")
        def write(s: str) -> None:
            f_out.write(s); f_out.flush()
        for line in f_in:
            line = line.strip()
            if not line:
                continue
            try:
                msg = json.loads(line)
            except json.JSONDecodeError:
                continue
            handle_message(client, msg, write)
    finally:
        try: conn.close()
        except Exception: pass


# ----------------------------------------------------------------- main ------

def main() -> None:
    ap = argparse.ArgumentParser(description=__doc__.splitlines()[0])
    ap.add_argument("--nrepl-host", default=os.environ.get("NREPL_HOST", NREPL_HOST_DEFAULT))
    ap.add_argument("--nrepl-port", type=int,
                    default=int(os.environ.get("NREPL_PORT", NREPL_PORT_DEFAULT)))
    ap.add_argument("--tcp", type=int, default=None,
                    help="Serve MCP over TCP on this port instead of stdio.")
    ap.add_argument("--tcp-host", default="127.0.0.1")
    args = ap.parse_args()

    client = NreplClient(args.nrepl_host, args.nrepl_port)
    if args.tcp is not None:
        serve_tcp(client, args.tcp_host, args.tcp)
    else:
        serve_stdio(client)


if __name__ == "__main__":
    main()
