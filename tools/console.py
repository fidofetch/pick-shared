#!/usr/bin/env python3
"""Send commands to a running app's console (the engine, the Builder or the Studio).

Start the app with its console flag first: -Dvoxel.console (port 47100) or -Dbuilder.console (port 47101).
    python3 tools/console.py "settings; wait 1; shot /tmp/settings.png"
    python3 tools/console.py --port 47101 help
    python3 tools/console.py < script.txt      one line per command line; stops at the first error
Prints each answer; exits 1 if a line failed, 2 if nothing is listening. Standard library only.
"""
import argparse
import os
import socket
import sys

END_OK, END_ERROR = "## ok", "## error"


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=int(os.environ.get("CONSOLE_PORT", "47100")))
    ap.add_argument("--timeout", type=float, default=310, help="seconds to wait for one answer")
    ap.add_argument("line", nargs="*", help="a command line (several commands split by ';')")
    a = ap.parse_args()
    lines = [" ".join(a.line)] if a.line else [l.strip() for l in sys.stdin if l.strip() and not l.startswith("#")]
    try:
        sock = socket.create_connection(("127.0.0.1", a.port), timeout=5)
    except OSError as e:
        print(f"nothing listening on 127.0.0.1:{a.port} ({e}); start the app with its console flag", file=sys.stderr)
        return 2
    sock.settimeout(a.timeout)
    f = sock.makefile("rw", encoding="utf-8", newline="\n")
    for line in lines:
        f.write(line + "\n")
        f.flush()
        while True:
            reply = f.readline()
            if not reply:
                print("the app closed the connection", file=sys.stderr)
                return 1
            reply = reply.rstrip("\n")
            if reply == END_OK:
                break
            if reply == END_ERROR:
                return 1
            print(reply)
    return 0


if __name__ == "__main__":
    sys.exit(main())
