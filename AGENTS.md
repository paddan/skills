# AGENTS.md

Steering rules for this repo. It is a central collection of personal agent
skills; the skills themselves are the product, not this file.

## Map

- `skills/<name>/SKILL.md` — one skill per directory; frontmatter `name:` must equal the directory name.
- `skills/<name>/agents/openai.yaml` — interface metadata (`interface:`, `display_name:`, `short_description:`).
- `skills/jfr-analyzer/` — the only skill with code (`scripts/`) and extra docs (`references/`, `README.md`).
- `mods/<name>/` — Claude Code mods (plugins of hooks, not skills); `install.sh` ignores them. Each has `.claude-plugin/plugin.json`, `hooks/hooks.json` and `hooks/register.tsx`.
- `install.sh` — installs skills into the runtime skill dirs (default `~/.agents/skills`); symlinks unless `--copy`.
- `tests/` — unittest suite: skill-layout checks plus JFR script regression tests.

## Rules

- Adding a skill means `skills/<name>/SKILL.md` (frontmatter `name: <name>`) **and** `agents/openai.yaml` with the interface fields; the layout test fails otherwise.
- Keep each skill self-contained. A skill may call another skill by name (e.g. `cap` runs `code-review`).
- Verify a mod with `claude plugin validate mods/<name>` and `claude plugin test mods/<name>`.
- Keep the skill table in `README.md` in sync when skills are added or removed.

## Commands

- Verify: `python3 -m unittest discover -s tests -v` (needs Python 3 + Bash, not a JDK).
- Install: `./install.sh --agents` (default) — also `--codex`, `--claude`, `--opencode`, `--pi`, `--all`, `--skill NAME`, `--copy`.
