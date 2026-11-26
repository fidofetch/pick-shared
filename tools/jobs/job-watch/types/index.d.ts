export type JobStatus = 'waiting' | 'running' | 'done' | 'failed' | 'gone'

export type Job = {
  id: string
  name: string
  /**
   * where it came from: a Bash call of this session (`bash`, `background`), a
   * process handed to `watch`, or a run of pick-shared's `tools/jobs/job`
   * (`job`, read from its record folder)
   */
  kind: 'bash' | 'background' | 'watch' | 'job'
  startedAt: number
  endedAt?: number
  status: JobStatus
  exitCode?: number
  /** background Bash task id, as the harness names it */
  taskId?: string
  pid?: number
  /** pgrep -f pattern for a process in another terminal */
  match?: string
  log?: string
  /** a file the job writes its exit code to */
  exitFile?: string
  /** set when the process was gone once, so an exit file gets one more poll */
  lastSeen?: boolean
  /** last line of the log, read on each poll */
  stage?: string
  /** a `job` run's record folder under $CLAUDE_JOBS */
  dir?: string
  /** a `job` run's class: queue, measure or light */
  cls?: string
  /** a `job` run this session started or claimed */
  isMine?: boolean
  /** wake the session with a prompt when it ends */
  wake: boolean
}

/** A `job` run this session started detached or handed to `watch`, waiting to be matched to its record. */
export type Claim = { name: string; at: number; wake: boolean }

declare module 'claude-code' {
  interface PluginState {
    'job-watch': { jobs: Shaped<Job[]>; claims: Shaped<Claim[]>; now: number; clearedAt: number }
  }
}
