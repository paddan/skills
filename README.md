# skills

Personal agent skills: `cap`, `code-review`, and `jfr-analyzer`.

## Verification

Run `python3 -m unittest discover -s tests -v` for the JFR script regression tests.
They use isolated temporary files and mock JDK commands; they require Python 3
and Bash, but do not require a JDK. Real-recording integration checks require a
JDK separately.

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
