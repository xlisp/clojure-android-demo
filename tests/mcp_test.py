import socket, json, sys

PORT = int(sys.argv[1]) if len(sys.argv) > 1 else 6689
s = socket.create_connection(("localhost", PORT), timeout=60)
s.settimeout(60)
buf = b""

def send(obj):
    s.sendall((json.dumps(obj) + "\n").encode())

def recv_line():
    global buf
    while b"\n" not in buf:
        chunk = s.recv(65536)
        if not chunk:
            return "<connection closed by server>"
        buf += chunk
    line, _, buf = buf.partition(b"\n")
    return line.decode()

send({"jsonrpc":"2.0","id":1,"method":"initialize","params":{
    "protocolVersion":"2024-11-05","capabilities":{},
    "clientInfo":{"name":"test-client","version":"0.0.1"}}})
print("INIT  :", recv_line())
send({"jsonrpc":"2.0","method":"notifications/initialized"})
send({"jsonrpc":"2.0","id":2,"method":"tools/list","params":{}})
print("TOOLS :", recv_line())
send({"jsonrpc":"2.0","id":3,"method":"tools/call","params":{
    "name":"clojure_eval","arguments":{"code":"(+ 1 2)"}}})
print("(+ 1 2)      :", recv_line())
send({"jsonrpc":"2.0","id":4,"method":"tools/call","params":{
    "name":"clojure_eval","arguments":{"code":"(clojure.core/vm-type)"}}})
print("(vm-type)    :", recv_line())
send({"jsonrpc":"2.0","id":5,"method":"tools/call","params":{
    "name":"clojure_eval","arguments":{"code":"(def answer 42)"}}})
print("(def answer) :", recv_line())
send({"jsonrpc":"2.0","id":6,"method":"tools/call","params":{
    "name":"clojure_eval","arguments":{"code":"(* answer 2)"}}})
print("(* answer 2) :", recv_line())
s.close()
print("DONE")
