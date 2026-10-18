# Claude runs: the job window

When Claude runs something long on Pick's machine (a bake, a forest-sim run, a lab sweep, a full build and
test), a terminal window called **Claude runs** shows each job live: its name, its stage, CPU, memory and how
long it has run, then the machine's totals. Pick asked for it on 2026-10-14 to keep an eye on usage; it was
first a throwaway script in one session and is now kept here so every repo uses the same one.

```
~/IdeaProjects/pick-shared/tools/jobs/job NAME -- command args...     run a job (opens the window if needed)
~/IdeaProjects/pick-shared/tools/jobs/job NAME --log FILE -- cmd...   the job writes its own log: stage from FILE
~/IdeaProjects/pick-shared/tools/jobs/open                            just open the window
~/IdeaProjects/pick-shared/tools/jobs/jobwatch --once                 print one readout here
```

What it looks like (an illustration, not a real run):

```
Claude runs   23:26:04

           JOB                          STAGE                                     CPU%    MEM     TIME
run        forest-boreal                boreal  412.3s  trees/ha  1830 ...         100   1.2G    06:52
run        lab-sweep                    done 120hz_4ss                             780   3.9G    21:10
done       wb-test                      BUILD SUCCESSFUL in 13s                                   00:13
           gradle (3 JVM)               daemons and test workers                   690   2.8G

these: CPU 1570% of 1600%   memory 7.9G
machine: 71.0G used of 123.0G, 51.0G available   load 9.34 8.28 5.52
```

- **Stage** is the last line of the job's log, so a job that prints a line per step reads well.
- **CPU and memory** add up the job's whole process tree; CPU is over the last 3 s, 100% is one core.
- A finished job stays for 15 minutes as `done` or `FAILED <code>`.
- **gradle**: a gradle build does its work in the daemon and its test workers, not under the job, so they get
  their own row. **other**: `com.pick` java processes nobody started through `job` (a bake by hand, the app).
- Job records live in `~/.cache/claude-jobs` (`CLAUDE_JOBS` to change it) and are pruned after a week.
- The window opens with konsole, else ptyxis, else gnome-terminal, on the host when the session runs inside a
  distrobox. With no desktop (a cloud container) `open` just prints how to watch by hand.

Linux only; needs bash and python3, nothing else.
