#!/usr/bin/env python3
"""Spike s2 — a minimal MCP STDIO client that drives the server like a real client:
send a request, WAIT for its response, then send the next. This avoids the transport
teardown race you get from piping all frames + immediate EOF.

Captures the server's stdout (the JSON-RPC channel) and stderr (logs) to files so
validate_stdout.py can assert stdout purity. Usage: stdio_client.py <jar> <out> <err>
"""
import json, subprocess, sys, threading, time

target, out_path, err_path = sys.argv[1], sys.argv[2], sys.argv[3]
# Accept a runnable jar (java -jar) OR a native binary (run directly) — same STDIO test.
cmd = ["java", "-jar", target] if target.endswith(".jar") else [target]
proc = subprocess.Popen(cmd,
                        stdin=subprocess.PIPE, stdout=subprocess.PIPE, stderr=subprocess.PIPE,
                        text=True, bufsize=1)

stdout_lines, stderr_lines = [], []
def drain(stream, sink):
    for line in stream:
        sink.append(line.rstrip("\n"))
threading.Thread(target=drain, args=(proc.stdout, stdout_lines), daemon=True).start()
threading.Thread(target=drain, args=(proc.stderr, stderr_lines), daemon=True).start()

def send(obj):
    proc.stdin.write(json.dumps(obj) + "\n")
    proc.stdin.flush()

def wait_for_id(want_id, timeout=8.0):
    """Block until a JSON-RPC response carrying want_id appears on stdout."""
    deadline = time.time() + timeout
    while time.time() < deadline:
        for l in list(stdout_lines):
            try:
                m = json.loads(l)
            except json.JSONDecodeError:
                continue
            if isinstance(m, dict) and m.get("id") == want_id and ("result" in m or "error" in m):
                return m
        time.sleep(0.05)
    return None

try:
    send({"jsonrpc": "2.0", "id": 1, "method": "initialize",
          "params": {"protocolVersion": "2024-11-05", "capabilities": {},
                     "clientInfo": {"name": "spike-client", "version": "0"}}})
    wait_for_id(1)
    send({"jsonrpc": "2.0", "method": "notifications/initialized"})
    time.sleep(0.2)
    send({"jsonrpc": "2.0", "id": 2, "method": "tools/list", "params": {}})
    wait_for_id(2)
    send({"jsonrpc": "2.0", "id": 3, "method": "tools/call",
          "params": {"name": "ping", "arguments": {"message": "hi"}}})
    wait_for_id(3)
    time.sleep(0.3)
finally:
    try:
        proc.stdin.close()
    except Exception:
        pass
    try:
        proc.wait(timeout=5)
    except subprocess.TimeoutExpired:
        proc.terminate()
        try:
            proc.wait(timeout=3)
        except subprocess.TimeoutExpired:
            proc.kill()

open(out_path, "w", encoding="utf-8").write("\n".join(stdout_lines) + ("\n" if stdout_lines else ""))
open(err_path, "w", encoding="utf-8").write("\n".join(stderr_lines) + ("\n" if stderr_lines else ""))
print(f"captured {len(stdout_lines)} stdout line(s), {len(stderr_lines)} stderr line(s)")
