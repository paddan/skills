export type Tally = {
  input: number
  output: number
  cacheRead: number
  cacheWrite: number
}

export type Limit = { kind: string; percentUsed: number; resetsAt?: string }

export type Agent = { id: string; type: string; description: string; status: string }

export type Snapshot = {
  contextPercent?: number
  contextTokens?: number
  contextWindow: number
  costUsd?: number
  limits: Limit[]
  agents: Agent[]
}

declare module 'claude-code' {
  interface PluginState {
    'usage-hud': { tally: Tally; snap: Snapshot | null; isHidden: boolean }
  }
}
