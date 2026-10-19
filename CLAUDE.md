# pick-shared: notes for Claude

Shared Java modules (`ui`, `console`) for voxel and world-builder; see README.md. Checks: `./gradlew test`.

## Long jobs on Pick's machine: the Claude runs window

On Pick's machine (a local / Remote Control session), start every long job (anything over about a minute: builds
with tests, bakes, sims, sweeps) through `tools/jobs/job NAME -- command...` here, so the **Claude runs** window
shows it live; the first job opens the window. Details: [docs/jobs.md](docs/jobs.md). Not needed in the cloud.
Jobs queue so overlapping runs don't skew results: one at a time by default; `--measure` for timings or
the GPU (the machine to itself), `--light` for seconds of small work. Run the app or game through `job` too,
and close it when done. Quote the job's last line (`ran alone` / `overlapped with`) with any timing.
