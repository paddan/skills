# skills

Personal agent skills. Each skill lives in its own directory under `skills/`, and
`install.sh` links them into the skill directories the agents read.

## Skills

| Skill | Description |
|-------|-------------|
| `grilling` | Frågar ut en plan, ett beslut eller en idé tills de svaga delarna syns. |
| `grill-me` | Samma intervju för en enskild plan eller design, i en följd. |
| `grill-with-docs` | Samma intervju, men skriver ADR:er och ordlista medan besluten tas. |
| `to-spec` | Omvandlar samtalet till en spec i `docs/specs/` — utan intervju. |
| `to-tickets` | Delar en plan eller spec i tracer-bullet-tickets i `docs/tickets/<feature-slug>/`, var och en med sina blockerande kanter. |
| `implement` | Implementerar en spec eller ticket-uppsättning. |
| `tdd` | Testdriven red-green-refactor vid överenskomna sömmar. |
| `code-review` | Granskar branchen plus ocommittade ändringar och rapporterar fynd per allvarlighetsgrad. |
| `cap` | Granska, bekräfta, committa, pusha — en commit, ingen PR. |
| `release` | Bumpar versionen, uppdaterar CHANGELOG/README, kör tester, committar, taggar och pushar. |
| `rules-check-drift` | Kontrollerar att AGENTS.md/CLAUDE.md fortfarande stämmer med koden. |
| `handoff` | Komprimerar samtalet till ett handoff-dokument för nästa agent. |
| `improve-codebase-architecture` | Letar förenklingsmöjligheter, rapporterar dem som HTML och grillar sedan den du väljer. |
| `find-skills` | Hittar och installerar skills från agent-skills-ekosystemet. |
| `jfr-analyzer` | Analyserar Java Flight Recorder-inspelningar för JVM- och applikationsproblem. |

## Mods

Claude Code mods live under `mods/`, apart from the skills; `install.sh` does not
touch them.

| Mod | Description |
|-----|-------------|
| `usage-hud` | Band above the prompt with tokens used, cost, context fill, usage limits (5h/7d) and running agents. `/usage-hud` hides or shows it. |

Load one with `claude --plugin-dir mods/usage-hud`, or run `./install.sh --mods`,
which installs nothing but prints the `CLAUDE_CODE_PLUGIN_DIRS` value covering
every mod. `--mods` can be combined with the skill flags. Check it with
`claude plugin validate mods/usage-hud` and `claude plugin test mods/usage-hud`.

## Verification

Run `python3 -m unittest discover -s tests -v` for the JFR script regression tests.
They use isolated temporary files and mock JDK commands; they require Python 3
and Bash, but do not require a JDK. Real-recording integration checks require a
JDK separately.

The same command also checks the skill layout: every `skills/<name>/` needs a
`SKILL.md` whose `name:` matches the directory name, plus `agents/openai.yaml`
with interface text.

## Installation

Run the shared installer from this repository:

```bash
./install.sh --agents
```

All directories under `skills/` containing a `SKILL.md` are installed. By default,
the installer creates symlinks in `~/.agents/skills`, so pulling repository updates
also updates the installed skills.

| Option | Installation directory |
|--------|------------------------|
| `--agents` (default) | `~/.agents/skills` |
| `--codex` | `~/.codex/skills` |
| `--claude` | `~/.claude/skills` |
| `--opencode` | `~/.config/opencode/skills` |
| `--pi` | `~/.pi/agent/skills` |
| `--all` | All five directories above |

Target flags can be combined. Use `--skill NAME` to install only one skill,
`--copy` for physical copies, or `--dest DIR` for a custom destination outside
the repository. Copies must be reinstalled to receive updates.

```bash
./install.sh --agents --claude
./install.sh --codex --skill jfr-analyzer --copy
```

Matching symlinks are left in place. Other existing installations are moved to
`.skill-backup-NAME.*` inside the destination directory before replacement.
To restore one, move the replacement aside and move the backed-up skill back
to its original location.
