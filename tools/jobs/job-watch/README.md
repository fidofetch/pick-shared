# job-watch: Claude runs, inside Claude

A Claude Code mod that shows the long jobs on Pick's machine in a pane beside the chat (titled **Claude runs**,
a small widget when minimized), and wakes the session when a job it handed over ends. An agent starts a long
job, ends its turn, and a prompt arrives with the exit code when it's done: no `sleep` loops, no
`ScheduleWakeup`, no timers.

## Install

At the prompt of a terminal session (`claude`):

```
/plugin install job-watch --marketplace fidofetch/pick-shared
```

Answer `y` to add the marketplace, then pick the user scope. Installed at the user scope it also loads in the
sessions the Claude desktop app starts on this machine.

Working on the mod itself: `claude --plugin-dir ~/IdeaProjects/pick-shared/tools/jobs/job-watch` loads it
from this folder and reloads it on every save.

## What shows

| Source | Shown | Wakes the session |
|---|---|---|
| a run of `tools/jobs/job` (any thread's), read from `~/.cache/claude-jobs` (`CLAUDE_JOBS`) | running, `waits` in the queue, done / exit code, its stage (the log's last line), its class | when this session started it detached (`&`, `nohup`) or named it to `watch` |
| a Bash call of this session running over 5 s | while it runs | no (the call returns by itself) |
| a background Bash call (`run_in_background`, Ctrl+B, auto-backgrounded) | until its notification | no: the harness already wakes the session for these |
| a process handed to `watch` by `pid`, or by `match` (a `pgrep -f` pattern) for one in another terminal | its stage from `log`, its exit code from `exitFile` | yes |

A job that ends shows a toast and a desktop notification; another thread's run only changes its row. Finished
`job` runs stay 15 minutes, as in the window. `/runs` opens the pane.

## For agents

The mod gives the model one tool, `watch` (`mcp__job-watch__watch`):

```
watch({ name: "forest-boreal", job: "forest-boreal" })          a tools/jobs/job run, by its NAME
watch({ name: "erosion", pid: 48213, log: "/tmp/erosion.log" })   a process started detached
watch({ name: "sim", match: "forest-sim/run.py boreal" })          one already running in another terminal
```

then end the turn. A `job` started detached from the agent's own Bash call is claimed without `watch`:

```
nohup ~/IdeaProjects/pick-shared/tools/jobs/job forest-boreal -- python3 forest-sim/run.py boreal >/dev/null 2>&1 &
```

The wake-up names the job, how long it ran, the exit code, the log and its last line (for a `job` run, its
`ran alone` / `overlapped with` line, to quote with any timing).

## Notes

- Linux and macOS (`kill -0`, `pgrep`). It checks every 5 s.
- A process handed over by `pid` or `match` is no child of the session: its exit code is known only from an
  `exitFile` (`cmd; echo $? > /tmp/x.exit`). A `job` run always writes one.
- State lives in the session (`$.state`): a reload of the mod keeps the list; a new session starts from what is
  on disk for `job` runs.
- `claude plugin test tools/jobs/job-watch` runs `hooks/watch.test.tsx`; `claude plugin validate` the same
  folder checks the manifest and module.
