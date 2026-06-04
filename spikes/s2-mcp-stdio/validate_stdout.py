#!/usr/bin/env python3
"""Spike s2 — assert STDIO stdout hygiene + a working tool round-trip.

The MCP STDIO contract: stdout is the JSON-RPC channel and the server MUST NOT write
anything else there (banner, logs, stack traces). This validator FAILS if any stdout
line is not a JSON-RPC message — that is the falsifiable G15 assertion.

Usage: validate_stdout.py <stdout-file> <stderr-file>
Exit 0 = clean + tool round-trip OK; exit 1 = a hard assertion failed.
"""
import json, sys

failures = 0
def check(label, cond):
    global failures
    print(("  [PASS] " if cond else "  [FAIL] ") + label)
    if not cond:
        failures += 1

out_path, err_path = sys.argv[1], sys.argv[2]
raw = open(out_path, encoding="utf-8").read()
err = open(err_path, encoding="utf-8").read()

lines = [l for l in raw.splitlines() if l.strip()]
msgs, bad = [], []
for l in lines:
    try:
        m = json.loads(l)
        if isinstance(m, dict) and m.get("jsonrpc") == "2.0":
            msgs.append(m)
        else:
            bad.append(l)
    except json.JSONDecodeError:
        bad.append(l)

print(f"  stdout: {len(lines)} non-empty line(s), {len(msgs)} JSON-RPC, {len(bad)} non-JSON-RPC")
for b in bad[:5]:
    print("    NON-JSON-RPC stdout line >>> " + b[:160])

# THE keystone assertion: stdout carries ONLY JSON-RPC (no banner/logs leaked).
check("stdout is PURE JSON-RPC (no banner / no logs leaked to the protocol channel)", len(bad) == 0 and len(msgs) > 0)

by_id = {m.get("id"): m for m in msgs if "id" in m}
init = by_id.get(1)
check("initialize (id=1) returned a result with serverInfo + protocolVersion",
      bool(init) and "result" in init
      and "serverInfo" in init["result"] and "protocolVersion" in init["result"])

tools_list = by_id.get(2)
tool_names = []
if tools_list and "result" in tools_list:
    tool_names = [t.get("name") for t in tools_list["result"].get("tools", [])]
check("tools/list (id=2) advertises the 'ping' tool", "ping" in tool_names)

call = by_id.get(3)
call_text = json.dumps(call) if call else ""
check("tools/call ping (id=3) returned a result containing 'pong'",
      bool(call) and "result" in call and "pong" in call_text)

# stderr is allowed to carry logs; we just report what landed there (banner/log routing).
print(f"  stderr: {len(err)} bytes ({len(err.splitlines())} lines) — logs/banner belong HERE, not stdout")
if err.strip():
    print("    stderr first line: " + err.splitlines()[0][:160])

print()
if failures:
    print(f"{failures} HARD ASSERTION(S) FAILED")
    sys.exit(1)
print("ALL HARD ASSERTIONS PASSED")
