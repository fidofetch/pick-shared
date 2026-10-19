# Claude runs: the job window

When Claude runs something long on Pick's machine (a bake, a forest-sim run, a lab sweep, a full build and
test), a terminal window called **Claude runs** shows each job live: its name, its stage, CPU, memory and how
long it has run, then the machine's totals. Pick asked for it on 2026-10-14 to keep an eye on usage; it was
first a throwaway script in one session and is now kept here so every repo uses the same one.

```
~/IdeaProjects/pick-shared/tools/jobs/job NAME -- command args...     run a job (opens the window if needed)
~/IdeaProjects/pick-shared/tools/jobs/job NAME --log FILE -- cmd...   the job writes its own log: stage from FILE
~/IdeaProjects/pick-shared/tools/jobs/job NAME --measure -- cmd...    timings or GPU: the machine to itself
~/IdeaProjects/pick-shared/tools/jobs/job NAME --light -- cmd...      seconds of small work: runs beside the queue
~/IdeaProjects/pick-shared/tools/jobs/jobqueue list                   what runs and what waits, and on what
~/IdeaProjects/pick-shared/tools/jobs/open                            just open the window
~/IdeaProjects/pick-shared/tools/jobs/jobwatch --once                 print one readout here
```

## The queue

Several Claude threads run jobs on the same machine, and two runs at once skew each other: a game left open
hung the GPU and spoiled another thread's frame timings. So `job` queues (Pick asked for it on 2026-10-19):

| kind | flag | runs | use it for |
|---|---|---|---|
| queue | (none) | one at a time, first come first served | builds with tests, bakes, sims, sweeps |
| measure | `--measure` | one at a time, and light jobs wait too | benchmarks, frame timings, anything on the GPU |
| light | `--light` | at once, beside a queue job; waits only for a measure job | a compile check, one quick test |

- A waiting job prints `job: NAME waits for ...` and shows as `wait N` in the window. It waits as long as it
  has to, so run it in the background if the session has other things to do.
- **Run the app or the game through `job` too** (`job voxel-game --measure -- ./gradlew run`): it holds the
  queue until its window is closed, so close it when you are done with it.
- The job's last line says `ran alone` or `overlapped with: ...`. Quote it with any timing you report. A
  measure job also warns at start about `com.pick` java apps that were started without `job`.
- A job whose process is gone (killed, session ended) drops out of the queue by itself. To cancel a waiting
  job, kill it.

## The window

What it looks like (an illustration, not a real run):

```
Claude runs   23:26:04

           JOB                          STAGE                                     CPU%    MEM     TIME
run        forest-boreal                boreal  412.3s  trees/ha  1830 ...         100   1.2G    06:52
wait 1     lab-sweep (measure)          waits for forest-boreal                                   00:40
wait 2     wb-test                      waits for forest-boreal, lab-sweep                        00:12
done       fmt-check (light)            job: fmt-check overlapped with: forest-bo                 00:04
           gradle (3 JVM)               daemons and test workers                   690   2.8G

these: CPU 790% of 1600%   memory 4.0G
machine: 71.0G used of 123.0G, 51.0G available   load 9.34 8.28 5.52
```

- **Stage** is the last line of the job's log, so a job that prints a line per step reads well.
- **CPU and memory** add up the job's whole process tree; CPU is over the last 3 s, 100% is one core.
- A finished job stays for 15 minutes as `done` or `FAILED <code>`. TIME is the run time, or for a waiting
  job how long it has waited.
- **gradle**: a gradle build does its work in the daemon and its test workers, not under the job, so they get
  their own row. **other**: `com.pick` java processes nobody started through `job` (a bake by hand, the app).
- Job records live in `~/.cache/claude-jobs` (`CLAUDE_JOBS` to change it) and are pruned after a week.
- The window opens with konsole, else ptyxis, else gnome-terminal, on the host when the session runs inside a
  distrobox. With no desktop (a cloud container) `open` just prints how to watch by hand.

Linux only; needs bash and python3, nothing else.
