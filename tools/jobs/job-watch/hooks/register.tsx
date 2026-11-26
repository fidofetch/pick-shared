import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Claim, Job } from '../types'

// job-watch: the long jobs of this machine and session in one pane ("Claude
// runs"), live, and a wake-up when one this session handed over ends, so an
// agent ends its turn instead of sleeping or setting a timer.
//
// Sources:
//  - runs of pick-shared's tools/jobs/job, read from their record folders in
//    $CLAUDE_JOBS (default ~/.cache/claude-jobs): every run on the machine shows;
//    the ones this session started detached, or named to `watch`, wake it
//  - a foreground Bash call that runs past SHOW_AFTER_MS (shown while it runs)
//  - a background Bash call (run_in_background, Ctrl+B, or auto-backgrounded);
//    the harness already wakes the session for those, so the pane only shows them
//  - a process handed to `watch` by pid, or a `pgrep -f` pattern for one
//    already running in another terminal, with an optional log to tail

const PANE = 'job-watch'
const TITLE = 'Claude runs'
const POLL_MS = 5_000
const TICK_MS = 1_000
const SHOW_AFTER_MS = 5_000
/** A finished `job` run stays this long, as in the Claude runs window. */
const KEEP_DONE_MS = 15 * 60_000
/** A claim matches a run that started at most this long before it. */
const CLAIM_SLACK_MS = 120_000
const KEEP = 60

/** `tools/jobs/job NAME ...` (or `job NAME` on the PATH) inside a Bash command. */
const JOB_CMD = /(?:^|[\s;&|(])(?:\S*\/)?job\s+([A-Za-z0-9._][^\s]*)\s/

// Change a tag when its type's fields change: an earlier version's value is declined.
const jobs = atom({ plugin: 'job-watch', key: 'jobs' } as const, [] as Job[], {
  shape: 'jobs-3',
})
const claims = atom({ plugin: 'job-watch', key: 'claims' } as const, [] as Claim[], {
  shape: 'claims-1',
})
const now = atom({ plugin: 'job-watch', key: 'now' } as const, 0)
/** When the person last pressed Clear finished: runs that ended before it stay cleared. */
const clearedAt = atom({ plugin: 'job-watch', key: 'clearedAt' } as const, 0)

const elapsed = (ms: number) => {
  const s = Math.max(0, Math.floor(ms / 1000))
  const h = Math.floor(s / 3600)
  const m = Math.floor((s % 3600) / 60)
  const sec = s % 60
  return h > 0 ? `${h}h${String(m).padStart(2, '0')}m` : m > 0 ? `${m}m${String(sec).padStart(2, '0')}s` : `${sec}s`
}

const shorten = (text: string, n: number) =>
  text.length > n ? `${text.slice(0, n - 1)}…` : text

const isLiveStatus = (status: Job['status']) => status === 'running' || status === 'waiting'
const isLive = (job: Job) => isLiveStatus(job.status)

function put($: EngineInterface, job: Job) {
  return update($, jobs, list => [...list.filter(one => one.id !== job.id), job].slice(-KEEP))
}

function patch($: EngineInterface, id: string, fields: Partial<Job>) {
  return update($, jobs, list => list.map(one => (one.id === id ? { ...one, ...fields } : one)))
}

// Redraw the clocks only while something runs.
async function tick($: EngineInterface) {
  const list = await read($, jobs)
  if (list.some(isLive)) {
    const t = await $.clock.now()
    await update($, now, () => t)
  }
}

async function pidAlive($: EngineInterface, pid: number) {
  try {
    return (await $.process.run(['kill', '-0', String(pid)], { timeoutMs: 5_000 })).exitCode === 0
  } catch {
    return true // a failed check is no proof it ended
  }
}

async function alive($: EngineInterface, job: Job) {
  if (job.pid !== undefined) return pidAlive($, job.pid)
  if (job.match !== undefined) {
    try {
      return (await $.process.run(['pgrep', '-f', job.match], { timeoutMs: 5_000 })).exitCode === 0
    } catch {
      return true
    }
  }
  return true
}

async function readText($: EngineInterface, path: string) {
  try {
    return String(await $.fs.read(path)).trim()
  } catch {
    return undefined
  }
}

async function lastLine($: EngineInterface, path: string) {
  try {
    const stat = await $.fs.stat(path)
    const offset = Math.max(0, stat.size - 4096)
    const text = await $.fs.read(path, { offset, length: stat.size - offset })
    const lines = String(text).split(/\r?\n|\r/).map(l => l.trim()).filter(Boolean)
    return lines.at(-1)
  } catch {
    return undefined
  }
}

async function jobsDir($: EngineInterface) {
  const set = await $.env.get('CLAUDE_JOBS')
  if (set) return set
  const home = await $.env.get('HOME')
  return home ? `${home}/.cache/claude-jobs` : undefined
}

async function finish($: EngineInterface, job: Job, fields: Partial<Job>) {
  const endedAt = fields.endedAt ?? (await $.clock.now())
  const ended: Job = { ...job, ...fields, endedAt }
  await patch($, job.id, ended)
  const took = elapsed(endedAt - job.startedAt)
  const how =
    ended.status === 'failed' ? `failed (exit ${ended.exitCode})` : ended.status === 'gone' ? 'ended' : 'finished'
  if (!ended.wake && job.kind === 'job' && !job.isMine) return // another thread's run: the pane is enough
  $.ui.toast(`${job.name} ${how} after ${took}`)
  void $.ui.notify(`${job.name} ${how} after ${took}`, { title: TITLE })
  if (!ended.wake) return
  const what = job.kind === 'job' ? 'job run' : 'watched job'
  const exit =
    ended.exitCode !== undefined
      ? `Exit code: ${ended.exitCode}.`
      : job.kind === 'job'
        ? 'Its process is gone with no exit code (killed, or its session ended).'
        : 'Exit code unknown (not a child of this session; pass exitFile to get it).'
  const lines = [
    `[job-watch] The ${what} "${job.name}" ${how} after ${took}.`,
    exit,
    job.log ? `Log: ${job.log}${ended.stage ? ` (last line: ${ended.stage})` : ''}` : '',
    job.kind === 'job' ? 'Quote its `ran alone` / `overlapped with` line with any timing.' : '',
    'Pick up where you left off.',
  ].filter(Boolean)
  await $.prompt.submit({ text: lines.join('\n') })
}

type JobRecord = {
  dir: string
  name: string
  cls: string
  pid?: number
  start: number
  began: boolean
  end?: number
  exit?: number
  log?: string
}

async function readRecord($: EngineInterface, root: string, folder: string): Promise<JobRecord | undefined> {
  const dir = `${root}/${folder}`
  const start = Number(await readText($, `${dir}/start`))
  if (!Number.isFinite(start) || start <= 0) return undefined
  const num = async (file: string) => {
    const text = await readText($, `${dir}/${file}`)
    return text !== undefined && /^-?\d+$/.test(text) ? Number(text) : undefined
  }
  return {
    dir,
    name: (await readText($, `${dir}/name`)) || folder,
    cls: (await readText($, `${dir}/class`)) || 'queue',
    pid: await num('pid'),
    start: start * 1000,
    began: (await readText($, `${dir}/began`)) !== undefined,
    end: ((await num('end')) ?? 0) * 1000 || undefined,
    exit: await num('exit'),
    log: (await readText($, `${dir}/logpath`)) || undefined,
  }
}

/** Takes the claim a run matches, if this session made one for it. */
async function takeClaim($: EngineInterface, record: JobRecord) {
  const list = await read($, claims)
  const claim = list.find(one => one.name === record.name && record.start >= one.at - CLAIM_SLACK_MS)
  if (claim === undefined) return undefined
  await update($, claims, l => l.filter(one => !(one.name === claim.name && one.at === claim.at)))
  return claim
}

async function scanJobRuns($: EngineInterface) {
  const root = await jobsDir($)
  if (root === undefined) return
  let entries: { name: string; kind: string }[]
  try {
    entries = await $.fs.list(root)
  } catch {
    return // no runs on this machine yet
  }
  const t = await $.clock.now()
  const known = new Map((await read($, jobs)).filter(job => job.kind === 'job').map(job => [job.dir, job]))
  const seen = new Set<string>()
  const cleared = await read($, clearedAt)

  for (const entry of entries) {
    if (entry.kind !== 'dir' || entry.name.startsWith('.')) continue
    const dir = `${root}/${entry.name}`
    const was = known.get(dir)
    // An ended run we already hold has nothing new to read.
    if (was !== undefined && !isLive(was)) {
      if (t - (was.endedAt ?? t) < KEEP_DONE_MS) seen.add(dir)
      continue
    }
    const record = await readRecord($, root, entry.name)
    if (record === undefined) continue
    if (record.end !== undefined && t - record.end >= KEEP_DONE_MS) continue
    seen.add(dir)

    const isGone = record.end === undefined && record.pid !== undefined && !(await pidAlive($, record.pid))
    const status: Job['status'] =
      record.exit !== undefined
        ? record.exit === 0 ? 'done' : 'failed'
        : isGone ? 'gone' : record.began ? 'running' : 'waiting'
    const stage = record.log ? await lastLine($, record.log) : undefined
    const label = record.cls === 'queue' ? record.name : `${record.name} (${record.cls})`

    if (was === undefined) {
      if (status === 'gone') continue // a crashed run from before this session
      if (!isLiveStatus(status) && (record.end ?? 0) <= cleared) continue
      const claim = isLiveStatus(status) ? await takeClaim($, record) : undefined
      await put($, {
        id: `job-${entry.name}`,
        name: label,
        kind: 'job',
        dir,
        cls: record.cls,
        startedAt: record.start,
        endedAt: record.end,
        status,
        exitCode: record.exit,
        pid: record.pid,
        log: record.log,
        stage,
        isMine: claim !== undefined,
        wake: claim?.wake ?? false,
      })
    } else if (isLiveStatus(status)) {
      const claim = was.isMine ? undefined : await takeClaim($, record)
      await patch($, was.id, {
        status,
        stage: stage ?? was.stage,
        ...(claim !== undefined ? { isMine: true, wake: claim.wake } : {}),
      })
    } else {
      await finish($, { ...was, stage: stage ?? was.stage }, {
        status,
        exitCode: record.exit,
        endedAt: record.end ?? t,
      })
    }
  }
  // Runs the window has pruned, or past KEEP_DONE_MS, leave the pane too.
  await update($, jobs, list => list.filter(job => job.kind !== 'job' || seen.has(job.dir ?? '')))
  // A claim no run matched in ten minutes is stale.
  await update($, claims, list => list.filter(one => t - one.at < 10 * 60_000))
}

async function pollWatched($: EngineInterface) {
  const list = await read($, jobs)
  for (const job of list) {
    if (job.status !== 'running' || job.kind !== 'watch') continue
    const exitFile = job.exitFile
    const stage = job.log ? await lastLine($, job.log) : undefined
    if (stage !== undefined && stage !== job.stage) await patch($, job.id, { stage })
    let code: number | undefined
    if (exitFile !== undefined) {
      const text = await lastLine($, exitFile)
      if (text !== undefined && /^-?\d+$/.test(text)) code = Number(text)
    }
    if (code !== undefined) {
      await finish($, { ...job, stage: stage ?? job.stage }, { status: code === 0 ? 'done' : 'failed', exitCode: code })
    } else if (!(await alive($, job))) {
      // Give an exit file one more poll to land before calling it.
      if (exitFile !== undefined && !job.lastSeen) {
        await patch($, job.id, { lastSeen: true })
        continue
      }
      await finish($, { ...job, stage: stage ?? job.stage }, { status: 'gone' })
    }
  }
}

/** When the poll in flight started; 0 when none is. */
let pollingSince = 0

async function poll($: EngineInterface) {
  const t = await $.clock.now()
  // A slow poll is not overlapped by the next, but one stuck a minute is let go.
  if (pollingSince !== 0 && t - pollingSince < 60_000) return
  pollingSince = t
  try {
    await pollWatched($)
    await scanJobRuns($)
  } finally {
    pollingSince = 0
  }
}

async function claim($: EngineInterface, name: string, wake: boolean) {
  const at = await $.clock.now()
  await update($, claims, list => [...list.filter(one => one.name !== name), { name, at, wake }])
  // A run already in the pane takes it now.
  const list = await read($, jobs)
  const run = list
    .filter(job => job.kind === 'job' && isLive(job) && job.name.replace(/ \((measure|light)\)$/, '') === name)
    .sort((a, b) => b.startedAt - a.startedAt)[0]
  if (run !== undefined) {
    await patch($, run.id, { isMine: true, wake })
    await update($, claims, l => l.filter(one => one.name !== name))
    return true
  }
  return false
}

export const register: Register = on => {
  on('session.start', async ($, e, next) => {
    await $.command.register({
      name: 'runs',
      description: 'Show the Claude runs pane (long jobs of this machine and session)',
    })
    await $.tool.register({
      name: 'watch',
      isDeferred: false,
      description: [
        'Hand a long-running job to the Claude runs pane and get woken when it ends: use this instead of sleep loops, ScheduleWakeup or timers.',
        'Give `job` for a run of tools/jobs/job (its NAME), `pid` for a process you started detached (nohup ... & echo $!), or `match` (a pgrep -f pattern) for one already running in another terminal.',
        'Optional `log`: a file whose last line shows as the stage; `exitFile`: a file the job writes its exit code to (e.g. cmd; echo $? > /tmp/x.exit). A `job` run has both already.',
        'After calling it, end your turn: a prompt arrives when the job finishes.',
        'Not needed for Bash run_in_background (the harness wakes you) or for `job` started detached from your own Bash call (claimed by itself).',
      ].join(' '),
      inputSchema: {
        type: 'object',
        properties: {
          name: { type: 'string', description: 'Short name shown in the pane' },
          job: { type: 'string', description: 'NAME of a tools/jobs/job run, running or waiting in the queue' },
          pid: { type: 'integer', description: 'Process id to wait on' },
          match: { type: 'string', description: 'pgrep -f pattern, for a process in another terminal' },
          log: { type: 'string', description: 'Absolute path of a log to tail' },
          exitFile: { type: 'string', description: 'Absolute path the job writes its exit code to' },
          wake: { type: 'boolean', description: 'Wake the session when it ends (default true)' },
        },
        required: ['name'],
      },
    })

    // Timers stop at a reload and register runs again, so they start here.
    $.clock.every(POLL_MS, () => void poll($))
    $.clock.every(TICK_MS, () => void tick($))
    $.clock.after(0, () => void poll($))

    if ((await read($, jobs)).some(isLive)) {
      void $.ui.open({ id: PANE, title: TITLE, placement: 'widget' })
    }

    return next(e)
  })

  on('command.run', { command: 'runs' }, async $ => {
    await $.ui.open({ id: PANE, title: TITLE })
    return { text: 'Claude runs pane opened.' }
  })

  on('tool.run', { tool: 'mcp__job-watch__watch' }, async ($, e) => {
    const input = e as unknown as {
      name: string; job?: string; pid?: number; match?: string; log?: string; exitFile?: string; wake?: boolean
      tool_use_id: string
    }
    const wake = input.wake !== false
    if (input.job !== undefined) {
      await poll($)
      const isFound = await claim($, input.job, wake)
      void $.ui.open({ id: PANE, title: TITLE, placement: 'widget' })
      return {
        result: isFound
          ? `Watching the job run "${input.job}". End your turn now; a prompt will wake this session when it ends.`
          : `No run named "${input.job}" is waiting or running yet; the next one to start within 10 minutes is watched. End your turn now; a prompt will wake this session when it ends.`,
      }
    }
    if (input.pid === undefined && input.match === undefined) {
      return { deny: 'watch needs `job`, `pid` or `match`.' }
    }
    const job: Job = {
      id: `watch-${input.tool_use_id}`,
      name: input.name,
      kind: 'watch',
      startedAt: await $.clock.now(),
      status: 'running',
      pid: input.pid,
      match: input.match,
      log: input.log,
      exitFile: input.exitFile,
      wake,
    }
    if (!(await alive($, job))) {
      return { deny: `No running process for ${input.pid !== undefined ? `pid ${input.pid}` : `pattern "${input.match}"`}.` }
    }
    await put($, job)
    void $.ui.open({ id: PANE, title: TITLE, placement: 'widget' })
    return {
      result: `Watching "${input.name}" in the Claude runs pane. End your turn now; a prompt will wake this session when it finishes (checked every ${POLL_MS / 1000}s).`,
    }
  }).catch(() => ({ deny: 'job-watch: the watch tool failed.' }))

  // Every Bash call: a foreground one shows once it passes SHOW_AFTER_MS; a
  // background one stays until its task notification arrives. A call that runs
  // tools/jobs/job shows as that run's record instead, and if it came back while
  // the run goes on (started with & or nohup), the run wakes this session.
  on('tool.call', { tool: 'Bash' }, async ($, e, next) => {
    const jobName = JOB_CMD.exec(`${e.command} `)?.[1]
    const id = `bash-${e.tool_use_id}`
    const startedAt = await $.clock.now()
    if (jobName !== undefined) {
      await update($, claims, list => [...list.filter(one => one.name !== jobName), { name: jobName, at: startedAt, wake: false }])
    } else {
      const name = shorten(e.description?.trim() || e.command.trim().split('\n')[0] || 'Bash', 60)
      await put($, { id, name, kind: 'bash', startedAt, status: 'running', wake: false })
    }

    const ran = await next(e)

    const record = (ran as { result?: { backgroundTaskId?: string } }).result
    const taskId = ran.deny === undefined ? record?.backgroundTaskId : undefined
    if (jobName !== undefined) {
      // Backgrounded by the harness: it wakes the session itself. Came back
      // with the run still going: this mod wakes it.
      const isDetached = taskId === undefined && ran.deny === undefined && ran.isError !== true
      if (isDetached) {
        await poll($)
        const run = (await read($, jobs)).find(
          job => job.kind === 'job' && isLive(job) && job.startedAt >= startedAt - CLAIM_SLACK_MS &&
            job.name.replace(/ \((measure|light)\)$/, '') === jobName,
        )
        if (run !== undefined) await patch($, run.id, { isMine: true, wake: true })
        else await update($, claims, list => list.map(one => (one.name === jobName ? { ...one, wake: true } : one)))
      }
      void $.ui.open({ id: PANE, title: TITLE, placement: 'widget' })
    } else if (taskId !== undefined) {
      await patch($, id, { kind: 'background', taskId })
      void $.ui.open({ id: PANE, title: TITLE, placement: 'widget' })
    } else {
      const endedAt = await $.clock.now()
      if (endedAt - startedAt < SHOW_AFTER_MS) {
        await update($, jobs, list => list.filter(one => one.id !== id))
      } else {
        const failed = ran.deny !== undefined || ran.isError === true
        await patch($, id, { status: failed ? 'failed' : 'done', endedAt })
      }
    }
    return ran
  }).catch(($, e, next) => next(e))

  // A background task's notification ends its job. The harness itself wakes
  // the session for these; the pane only records it.
  on('prompt.submit', async ($, e, next) => {
    if (e.origin?.kind === 'task-notification') {
      const list = await read($, jobs)
      for (const job of list) {
        if (job.status === 'running' && job.taskId !== undefined && e.text.includes(job.taskId)) {
          const status = /<status>\s*(\w+)/.exec(e.text)?.[1]
          const code = /exit code[^\d-]*(-?\d+)/i.exec(e.text)?.[1]
          const failed = status === 'failed' || status === 'killed' || (code !== undefined && code !== '0')
          const endedAt = await $.clock.now()
          await patch($, job.id, {
            status: failed ? 'failed' : 'done',
            exitCode: code !== undefined ? Number(code) : undefined,
            endedAt,
          })
          $.ui.toast(`${job.name} ${failed ? 'failed' : 'finished'} after ${elapsed(endedAt - job.startedAt)}`)
        }
      }
    }
    return next(e)
  }).catch(($, e, next) => next(e))

  on('ui.render', { component: 'Pane', requestId: PANE }, async ($, e) => {
    const { Box, Text, Button } = $.ui.resolve(e)
    const list = await read($, jobs)
    const t = Math.max(await read($, now), await $.clock.now())
    const shown = list.filter(
      job => job.kind !== 'bash' || job.status !== 'running' || t - job.startedAt >= SHOW_AFTER_MS,
    )
    const live = shown.filter(isLive)
    const ended = shown.filter(job => !isLive(job)).sort((a, b) => (b.endedAt ?? 0) - (a.endedAt ?? 0))
    const order = (job: Job) => (job.status === 'running' ? 0 : 1)
    const ordered = [...live].sort((a, b) => order(a) - order(b) || a.startedAt - b.startedAt)

    if (e.props.placement === 'widget') {
      return (
        <Box flexDirection="column">
          {live.length === 0 && <Text dimColor>No jobs running</Text>}
          {ordered.slice(0, 3).map(job => (
            <Text>
              {shorten(job.name, 28)}{' '}
              <Text dimColor>{job.status === 'waiting' ? 'waits ' : ''}{elapsed(t - job.startedAt)}</Text>
            </Text>
          ))}
          {live.length > 3 && <Text dimColor>+{live.length - 3} more</Text>}
        </Box>
      )
    }

    const room = Math.max(1, (e.viewport?.rows ?? 24) - 6)
    const width = Math.max(20, (e.props.bodyColumns ?? 60) - 22)
    const mark = (job: Job) =>
      ({ running: '●', waiting: '…', done: '✓', failed: '✗', gone: '○' } as const)[job.status]
    const color = (job: Job) =>
      job.status === 'running' ? 'warning' : job.status === 'done' ? 'success' : job.status === 'failed' ? 'error' : undefined
    const source = (job: Job) =>
      job.kind === 'job' ? (job.isMine ? ' · this session' : ' · job') : job.kind === 'bash' ? '' : ` · ${job.kind}`

    return (
      <Box flexDirection="column">
        <Text dimColor>
          {live.filter(job => job.status === 'running').length} running · {live.filter(job => job.status === 'waiting').length} waiting · {ended.length} finished
        </Text>
        {shown.length === 0 && (
          <Text dimColor>
            No long jobs yet. tools/jobs/job runs, Bash calls over {SHOW_AFTER_MS / 1000}s, background tasks and watched processes show here.
          </Text>
        )}
        {[...ordered, ...ended].slice(0, room).map(job => (
          <Box flexDirection="column">
            <Text>
              <Text color={color(job)}>{mark(job)}</Text> {shorten(job.name, width)}{' '}
              <Text dimColor>
                {job.status === 'waiting' ? 'waits ' : ''}
                {elapsed((job.endedAt ?? t) - job.startedAt)}
                {source(job)}
                {job.exitCode !== undefined ? ` · exit ${job.exitCode}` : ''}
                {job.wake && isLive(job) ? ' · wakes me' : ''}
              </Text>
            </Text>
            {job.stage !== undefined && isLive(job) && (
              <Text dimColor>  {shorten(job.stage, width + 16)}</Text>
            )}
          </Box>
        ))}
        {ended.length > 0 && (
          <Button
            key="clear"
            onPress={async () => {
              const t = await $.clock.now()
              await update($, clearedAt, () => t)
              await update($, jobs, l => l.filter(isLive))
            }}
          >
            Clear finished
          </Button>
        )}
      </Box>
    )
  })
}
