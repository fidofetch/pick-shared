import { expect, mock, test } from 'claude-code/testing'
import type { On } from 'claude-code'

const PANE = {
  plugin: 'job-watch',
  component: 'Pane',
  requestId: 'job-watch',
  props: {
    title: 'Claude runs',
    isFocused: false,
    bodyColumns: 70,
    placement: 'dock',
    scroll: { offset: 0, bodyRows: 20 },
    view: {},
  },
} as const

type World = {
  /** pids that are running */
  alive: Set<number>
  prompts: string[]
  /** the files on disk: path → text */
  files: Map<string, string>
}

function engine(on: On, world: World) {
  on('command.register', () => ({ value: { command: 'runs' } }))
  on('tool.register', (_, e) => ({ value: { tool: `mcp__job-watch__${e.name}` } }))
  on('ui.open', () => ({ value: { isPlaced: true } }))
  on('ui.toast', () => ({ value: undefined }))
  on('ui.notify', () => ({ value: { isSent: true, channel: 'client' } as const }))
  on('session.start', (_, e) => ({ cwd: e.cwd }))
  on('env.get', (_, e) => ({ value: e.name === 'HOME' ? '/home/pick' : undefined }))
  on('process.run', (_, e) => {
    const pid = Number(e.argv[2])
    const isUp = e.argv[0] === 'kill' ? world.alive.has(pid) : world.alive.size > 0
    return { value: { exitCode: isUp ? 0 : 1, stdout: '', stderr: '', isStdoutTruncated: false, isStderrTruncated: false } }
  })
  on('fs.list', (_, e) => {
    const dir = `${e.path}/`
    const names = new Set<string>()
    for (const path of world.files.keys()) {
      if (path.startsWith(dir)) names.add(path.slice(dir.length).split('/')[0]!)
    }
    if (names.size === 0) throw new Error('ENOENT')
    return { value: [...names].map(name => ({ name, kind: 'dir' as const, size: 0, mtimeMs: 0, isLink: false })) }
  })
  on('fs.stat', (_, e) => {
    const text = world.files.get(e.path)
    if (text === undefined) throw new Error('ENOENT')
    return { value: { kind: 'file' as const, size: text.length, mtimeMs: 0, isLink: false } }
  })
  on('fs.read', (_, e) => {
    const text = world.files.get(e.path)
    if (text === undefined) throw new Error('ENOENT')
    return { value: e.offset !== undefined ? text.slice(e.offset, e.offset + (e.length ?? text.length)) : text }
  })
  on('prompt.submit', (_, e) => {
    world.prompts.push(e.text)
    return { text: e.text }
  })
}

const newWorld = (): World => ({ alive: new Set(), prompts: [], files: new Map() })

/** A run as tools/jobs/job writes it. */
function jobRun(world: World, folder: string, fields: Record<string, string>) {
  const dir = `/home/pick/.cache/claude-jobs/${folder}`
  for (const [file, text] of Object.entries(fields)) world.files.set(`${dir}/${file}`, `${text}\n`)
  return dir
}

test('a watched process shows in the pane and wakes the session when it ends', async ($, on) => {
  const clock = mock.clock(on)
  const world = newWorld()
  world.alive.add(4242)
  engine(on, world)

  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  const ran = await $.tool.call({ tool: 'mcp__job-watch__watch', name: 'erosion sim', pid: 4242 } as never)
  expect(String(ran.result)).toContain('Watching "erosion sim"')

  await clock.advance(65_000)
  for (const surface of ['terminal', 'desktop'] as const) {
    const ui = await $.ui.mount({ ...PANE, surface })
    expect((await ui.find({ type: 'Text', text: /erosion sim/ }))?.text).toContain('1m05s')
    await ui.unmount()
  }
  expect(world.prompts).toEqual([])

  world.alive.delete(4242)
  await clock.advance(5_000)
  expect(world.prompts.length).toBe(1)
  expect(world.prompts[0]).toContain('"erosion sim" ended after 1m10s')
})

test('watch refuses a pid that is not running', async ($, on) => {
  mock.clock(on)
  engine(on, newWorld())
  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  const ran = await $.tool.call({ tool: 'mcp__job-watch__watch', name: 'x', pid: 1 } as never)
  expect(ran.deny ?? ran.text ?? '').toContain('No running process')
})

test('an exit file gives the wake-up its exit code', async ($, on) => {
  const clock = mock.clock(on)
  const world = newWorld()
  world.alive.add(7)
  engine(on, world)
  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  await $.tool.call({ tool: 'mcp__job-watch__watch', name: 'bake', pid: 7, exitFile: '/tmp/bake.exit' } as never)
  world.files.set('/tmp/bake.exit', '3\n')
  await clock.advance(5_000)
  expect(world.prompts.length).toBe(1)
  expect(world.prompts[0]).toContain('failed (exit 3)')
  expect(world.prompts[0]).toContain('Exit code: 3.')
})

test('a background Bash call shows as background', async ($, on) => {
  const clock = mock.clock(on)
  engine(on, newWorld())
  on('tool.call', () => ({ result: { stdout: '', stderr: '', interrupted: false, backgroundTaskId: 'bg77' } }))

  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  await $.tool.call({ tool: 'Bash', command: './gradlew bake', description: 'Bake the atlas', run_in_background: true })
  await clock.advance(10_000)

  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  expect((await ui.find({ type: 'Text', text: /Bake the atlas/ }))?.text).toContain('background')
  await ui.unmount()
})

test('every job run on the machine shows; only another thread\'s never wakes this one', async ($, on) => {
  const clock = mock.clock(on)
  const world = newWorld()
  await clock.set(1_795_000_000_000)
  const start = Math.floor(clock.now() / 1000)
  world.alive.add(500).add(501)
  const dir = jobRun(world, '20261126-120000-500-forest-boreal', {
    name: 'forest-boreal', class: 'queue', pid: '500', start: String(start), began: String(start),
  })
  world.files.set(`${dir}/log`, 'boreal 12.0s trees/ha 1830\n')
  world.files.set(`${dir}/logpath`, `${dir}/log\n`)
  jobRun(world, '20261126-120001-501-lab-sweep', {
    name: 'lab-sweep', class: 'measure', pid: '501', start: String(start),
  })
  engine(on, world)

  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  await clock.advance(5_000)

  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  expect((await ui.find({ type: 'Text', text: /forest-boreal/ }))?.text).toContain('· job')
  expect((await ui.find({ type: 'Text', text: /trees\/ha/ }))?.text).toContain('boreal 12.0s')
  expect((await ui.find({ type: 'Text', text: /lab-sweep \(measure\)/ }))?.text).toContain('waits')
  await ui.unmount()

  world.files.set(`${dir}/exit`, '0\n')
  world.files.set(`${dir}/end`, `${start + 60}\n`)
  world.alive.delete(500)
  await clock.advance(5_000)
  expect(world.prompts).toEqual([])
})

test('a job run this session started detached wakes it with the exit code', async ($, on) => {
  const clock = mock.clock(on)
  const world = newWorld()
  engine(on, world)
  // The Bash call starts the run in the background with & and comes back at once.
  await clock.set(1_795_000_000_000)
  const start = Math.floor(clock.now() / 1000)
  let dir = ''
  on('tool.call', () => {
    world.alive.add(900)
    dir = jobRun(world, '20261126-130000-900-wb-test', {
      name: 'wb-test', class: 'queue', pid: '900', start: String(start), began: String(start),
    })
    world.files.set(`${dir}/log`, 'job: wb-test ran alone\n')
    world.files.set(`${dir}/logpath`, `${dir}/log\n`)
    return { result: { stdout: '', stderr: '', interrupted: false } }
  })

  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  await $.tool.call({
    tool: 'Bash',
    command: 'nohup ~/IdeaProjects/pick-shared/tools/jobs/job wb-test -- ./gradlew test > /dev/null 2>&1 &',
  })
  await clock.advance(5_000)
  const ui = await $.ui.mount({ ...PANE, surface: 'desktop' })
  expect((await ui.find({ type: 'Text', text: /wb-test/ }))?.text).toContain('this session')
  await ui.unmount()

  world.files.set(`${dir}/exit`, '1\n')
  world.files.set(`${dir}/end`, `${start + 300}\n`)
  world.alive.delete(900)
  await clock.advance(5_000)
  expect(world.prompts.length).toBe(1)
  expect(world.prompts[0]).toContain('"wb-test" failed (exit 1) after 5m00s')
  expect(world.prompts[0]).toContain('ran alone')
})

test('watch takes a job run by its name', async ($, on) => {
  const clock = mock.clock(on)
  const world = newWorld()
  await clock.set(1_795_000_000_000)
  const start = Math.floor(clock.now() / 1000)
  world.alive.add(77)
  const dir = jobRun(world, '20261126-140000-77-bake', {
    name: 'bake', class: 'queue', pid: '77', start: String(start), began: String(start),
  })
  engine(on, world)
  await $.session.start({ cwd: '/', surface: 'desktop', isInteractive: true })
  const ran = await $.tool.call({ tool: 'mcp__job-watch__watch', name: 'bake', job: 'bake' } as never)
  expect(String(ran.result)).toContain('Watching the job run "bake"')

  world.files.set(`${dir}/exit`, '0\n')
  world.files.set(`${dir}/end`, `${start + 90}\n`)
  await clock.advance(5_000)
  expect(world.prompts.length).toBe(1)
  expect(world.prompts[0]).toContain('"bake" finished after 1m30s')
})
