import { atom, read, update } from 'claude-code'
import type { EngineInterface, Register } from 'claude-code'

import type { Snapshot, Tally } from '../types'

const tally = atom(
  { plugin: 'usage-hud', key: 'tally' } as const,
  { input: 0, output: 0, cacheRead: 0, cacheWrite: 0 } as Tally,
)
const snap = atom({ plugin: 'usage-hud', key: 'snap' } as const, null as Snapshot | null)
const isHidden = atom({ plugin: 'usage-hud', key: 'isHidden' } as const, false)

const LIMIT_NAMES: Record<string, string> = {
  five_hour: '5h',
  seven_day: '7d',
  spend_limit: 'spend',
}

const compact = (n: number) =>
  n >= 1_000_000 ? `${(n / 1_000_000).toFixed(1)}M` : n >= 1000 ? `${Math.round(n / 1000)}k` : `${n}`

const untilReset = (iso: string | undefined, now: number) => {
  if (!iso) return ''
  const minutes = Math.max(0, Math.round((Date.parse(iso) - now) / 60_000))
  return minutes >= 60 ? ` (${Math.floor(minutes / 60)}h${minutes % 60}m)` : ` (${minutes}m)`
}

// Reads the live figures; writes the atom only when they changed, so the
// band redraws on movement, not on every poll.
async function refresh($: EngineInterface) {
  const [usage, agents] = await Promise.all([$.session.usage(), $.agent.list()])
  const next: Snapshot = {
    contextPercent: usage.context.percent,
    contextTokens: usage.context.tokens,
    contextWindow: usage.context.window,
    costUsd: usage.cost?.usd,
    limits: usage.rateLimits.map(l => ({
      kind: l.kind,
      percentUsed: l.percentUsed,
      resetsAt: l.resetsAt,
    })),
    agents: agents.map(a => ({
      id: a.id,
      type: a.type,
      description: a.description,
      status: a.status,
    })),
  }
  const before = await read($, snap)
  if (JSON.stringify(before) !== JSON.stringify(next)) {
    await update($, snap, () => next)
  }
}

export const register: Register = on => {
  let timer: { cancel: () => void } | undefined

  on('session.start', async ($, e, next) => {
    await $.command.register({
      name: 'usage-hud',
      description: 'Show or hide the usage band above the prompt',
    })
    await refresh($)
    // Agents start and finish between turns, which no event reports: poll.
    timer = $.clock.every(3000, () => refresh($))

    return next(e)
  })

  on('session.end', ($, e, next) => {
    timer?.cancel()

    return next(e)
  })

  on('command.run', { command: 'usage-hud' }, async $ => {
    const hidden = await read($, isHidden)
    await update($, isHidden, () => !hidden)

    return { text: hidden ? 'Usage band shown.' : 'Usage band hidden.' }
  })

  // Every model request of every loop (main and subagents) passes here.
  on('turn.step', async function* ($, e, next) {
    const result = yield* next(e)
    const u = result.usage
    if (u) {
      await update($, tally, t => ({
        input: t.input + u.input_tokens,
        output: t.output + u.output_tokens,
        cacheRead: t.cacheRead + u.cache_read_input_tokens,
        cacheWrite: t.cacheWrite + u.cache_creation_input_tokens,
      }))
    }

    return result
  })

  on('session.measure', async ($, e, next) => {
    await refresh($)

    return next(e)
  })

  on('tool.call', async ($, e, next) => {
    const ran = await next(e)
    if (e.tool === 'Agent') await refresh($)

    return ran
  })

  on('ui.render', { component: 'AbovePrompt' }, async ($, e, next) => {
    const s = await read($, snap)
    if (e.props.hasSurvey || s === null || (await read($, isHidden))) {
      return next(e)
    }

    const t = await read($, tally)
    const now = await $.clock.now()
    const { Box, Text } = $.ui.resolve(e)
    const running = s.agents.filter(a => a.status === 'running')
    const ctx =
      s.contextPercent === undefined
        ? `ctx –/${compact(s.contextWindow)}`
        : `ctx ${s.contextPercent}% (${compact(s.contextTokens ?? 0)}/${compact(s.contextWindow)})`

    return (
      <Box flexDirection="column">
        <Text dimColor>
          tokens in {compact(t.input + t.cacheWrite)} · cached {compact(t.cacheRead)} · out{' '}
          {compact(t.output)}
          {s.costUsd === undefined ? '' : ` · $${s.costUsd.toFixed(2)}`} · {ctx}
        </Text>
        <Text dimColor>
          {s.limits.length === 0
            ? 'limits: n/a'
            : 'limits: ' +
              s.limits
                .map(
                  l =>
                    `${LIMIT_NAMES[l.kind] ?? l.kind} ${l.percentUsed}%${untilReset(l.resetsAt, now)}`,
                )
                .join(' · ')}
          {' · '}
          {running.length === 0
            ? 'agents: none'
            : `agents: ${running.length} (${running.map(a => a.type).join(', ')})`}
        </Text>
      </Box>
    )
  })
}
