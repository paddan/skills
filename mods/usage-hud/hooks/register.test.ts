import { expect, mock, test } from 'claude-code/testing'

test('band shows tokens, limits and running agents', async ($, on) => {
  on('session.usage', () => ({
    value: {
      startedAt: 0,
      context: { tokens: 84_000, window: 200_000, percent: 42 },
      rateLimits: [
        { kind: 'five_hour', percentUsed: 23.5 },
        { kind: 'seven_day', percentUsed: 7 },
      ],
      cost: { usd: 1.234 },
    },
  }))
  on('agent.list', () => ({
    value: [
      { id: 'a1', description: 'review', type: 'Explore', status: 'running' },
      { id: 'a2', description: 'old', type: 'Plan', status: 'completed' },
    ],
  }))
  on('command.register', () => ({ value: {} }) as never)
  on('session.start', () => ({ cwd: '/tmp' }) as never)
  mock.clock(on)

  await $.session.start({ source: 'startup', cwd: '/tmp' } as never)
  const ui = await $.ui.mount({
    plugin: 'usage-hud',
    surface: 'terminal',
    component: 'AbovePrompt',
    props: { hasSurvey: false } as never,
  })

  expect(await ui.find({ type: 'Text', text: 'ctx 42%' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: '5h 23.5%' })).toBeDefined()
  expect(await ui.find({ type: 'Text', text: 'agents: 1 (Explore)' })).toBeDefined()
})
