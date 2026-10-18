#!/usr/bin/env python3
"""Send commands to a running app's console (the engine, the Builder or the Studio).

Start the app with its console flag first: -Dvoxel.console (port 47100) or -Dbuilder.console (port 47101).
    python3 tools/console.py "settings; wait 1; shot /tmp/settings.png"
    python3 tools/console.py --port 47101 help
    python3 tools/console.py < script.txt      one line per command line; stops at the first error
    python3 tools/console.py --for 5 "watch player.pos every 0.5; key w 3"
                                               also print pushed lines (watches, topics) for 5 s after the answers
    python3 tools/console.py -i                stay connected: type lines (or pipe them in), see everything as it comes
    python3 tools/console.py "shot send"       the picture comes back over the connection (saved in ./received-shots)
    python3 tools/console.py --for 10 "film every 2"   a picture every 2 s for 10 s
Lines the app pushes (a watch, a topic you subscribed to) start with "# ". Prints each answer; exits 1 if a line
failed, 2 if nothing is listening. Standard library only.
"""
import argparse
import base64
import os
import queue
import socket
import sys
import threading
import time

END_OK, END_ERROR, PUSH = "## ok", "## error", "# "


def main():
    ap = argparse.ArgumentParser(description=__doc__, formatter_class=argparse.RawDescriptionHelpFormatter)
    ap.add_argument("--port", type=int, default=int(os.environ.get("CONSOLE_PORT", "47100")))
    ap.add_argument("--timeout", type=float, default=310, help="seconds to wait for one answer")
    ap.add_argument("--for", dest="follow", type=float, default=0, metavar="SECONDS",
                    help="after the last answer, keep printing pushed lines this long")
    ap.add_argument("-i", "--interactive", action="store_true",
                    help="stay connected: send each line from stdin as it comes, print all that comes back")
    ap.add_argument("--save-to", default="received-shots", metavar="DIR",
                    help="where pictures sent back (shot send, film) are saved; default ./received-shots")
    ap.add_argument("--quiet-push", action="store_true", help="drop pushed lines (only answers)")
    ap.add_argument("line", nargs="*", help="a command line (several commands split by ';')")
    a = ap.parse_args()
    try:
        sock = socket.create_connection(("127.0.0.1", a.port), timeout=5)
    except OSError as e:
        print(f"nothing listening on 127.0.0.1:{a.port} ({e}); start the app with its console flag", file=sys.stderr)
        return 2
    sock.settimeout(None)
    f = sock.makefile("rw", encoding="utf-8", newline="\n")
    answers = queue.Queue()   # one entry per answer: True ok, False error, None the app hung up

    def picture(line):   # "png <name> <base64>" -> a file; prints where it went instead of the data
        try:
            _, name, data = line.split(" ", 2)
            os.makedirs(a.save_to, exist_ok=True)
            path = os.path.abspath(os.path.join(a.save_to, os.path.basename(name)))
            with open(path, "wb") as out:
                out.write(base64.b64decode(data))
            return f"picture saved: {path}"
        except (ValueError, OSError) as e:
            return f"picture not saved: {e}"

    def read():
        try:
            for raw in f:
                line = raw.rstrip("\n")
                if line.startswith("png "):
                    line = picture(line)
                elif line.startswith(PUSH + "shot png "):
                    line = PUSH + "shot " + picture(line[len(PUSH + "shot "):])
                if line == END_OK or line == END_ERROR:
                    answers.put(line == END_OK)
                elif line.startswith(PUSH):
                    if not a.quiet_push:
                        print(line, flush=True)
                else:
                    print(line, flush=True)
        except (OSError, ValueError):
            pass
        answers.put(None)

    threading.Thread(target=read, daemon=True).start()

    def send(line):
        f.write(line + "\n")
        f.flush()

    if a.interactive:
        failed = False
        if a.line:
            send(" ".join(a.line))
        try:
            for line in sys.stdin:
                if line.strip() and not line.startswith("#"):
                    send(line.strip())
        except KeyboardInterrupt:
            pass
        if not sys.stdin.isatty():   # piped in: the follow time, then wait for what is owed
            time.sleep(a.follow)
            sock.shutdown(socket.SHUT_WR)
            while True:
                r = answers.get()
                if r is None:
                    break
                failed |= not r
        return 1 if failed else 0

    lines = [" ".join(a.line)] if a.line else [l.strip() for l in sys.stdin if l.strip() and not l.startswith("#")]
    for line in lines:
        send(line)
        try:
            r = answers.get(timeout=a.timeout)
        except queue.Empty:
            print(f"no answer after {a.timeout} s", file=sys.stderr)
            return 1
        if r is None:
            print("the app closed the connection", file=sys.stderr)
            return 1
        if not r:
            return 1
    if a.follow > 0:
        time.sleep(a.follow)   # the reader keeps printing pushed lines meanwhile
    return 0


if __name__ == "__main__":
    try:
        sys.exit(main())
    except KeyboardInterrupt:
        sys.exit(130)
