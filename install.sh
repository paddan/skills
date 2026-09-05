#!/usr/bin/env bash
set -euo pipefail

usage() {
  printf '%s\n' 'Usage: ./install.sh [--agents|--codex|--claude|--opencode|--pi|--all] [--copy] [--skill NAME] [--dest DIR]' \
    'Default: --agents, using symlinks. Target flags can be combined.' \
    'Existing installations are preserved in a backup directory beside the skills.'
}

repo_dir=$(cd "$(dirname "${BASH_SOURCE[0]}")" && pwd)
mode=link
selected_skill=
destinations=()
while [[ $# -gt 0 ]]; do
  case "$1" in
    --agents) destinations+=("$HOME/.agents/skills"); shift ;;
    --codex) destinations+=("$HOME/.codex/skills"); shift ;;
    --claude) destinations+=("$HOME/.claude/skills"); shift ;;
    --opencode) destinations+=("$HOME/.config/opencode/skills"); shift ;;
    --pi) destinations+=("$HOME/.pi/agent/skills"); shift ;;
    --all)
      destinations+=("$HOME/.agents/skills" "$HOME/.codex/skills" "$HOME/.claude/skills" "$HOME/.config/opencode/skills" "$HOME/.pi/agent/skills")
      shift ;;
    --copy) mode=copy; shift ;;
    --skill|--dest)
      [[ $# -ge 2 && -n "$2" && "$2" != --* ]] || { usage >&2; exit 2; }
      if [[ "$1" == --skill ]]; then selected_skill=$2; else destinations+=("$2"); fi
      shift 2 ;;
    --help|-h) usage; exit 0 ;;
    *) usage >&2; exit 2 ;;
  esac
done
if [[ ${#destinations[@]} -eq 0 ]]; then destinations+=("$HOME/.agents/skills"); fi
if [[ -n "$selected_skill" && ! "$selected_skill" =~ ^[a-z0-9][a-z0-9-]*$ ]]; then
  printf 'Invalid skill name: %s\n' "$selected_skill" >&2
  exit 2
fi

sources=()
for source_dir in "$repo_dir"/skills/*; do
  [[ -d "$source_dir" && -f "$source_dir/SKILL.md" ]] || continue
  [[ -z "$selected_skill" || "${source_dir##*/}" == "$selected_skill" ]] || continue
  sources+=("$source_dir")
done
[[ ${#sources[@]} -gt 0 ]] || { printf 'No matching skills found.\n' >&2; exit 2; }

# Validate every destination before installing any skills.
for base in "${destinations[@]}"; do
  mkdir -p "$base"
  resolved_base=$(cd "$base" && pwd -P)
  case "$resolved_base/" in
    "$repo_dir/"*) printf 'Destination must be outside the repository: %s\n' "$base" >&2; exit 2 ;;
  esac
done

for base in "${destinations[@]}"; do
  for source_dir in "${sources[@]}"; do
    name=${source_dir##*/}
    dest="$base/$name"
    if [[ "$mode" == link && -L "$dest" && "$(readlink "$dest")" == "$source_dir" ]]; then
      printf 'Already installed: %s\n' "$dest"
      continue
    fi
    if [[ -e "$dest" || -L "$dest" ]]; then
      backup_dir=$(mktemp -d "$base/.skill-backup-$name.XXXXXX")
      mv "$dest" "$backup_dir/$name"
      printf 'Backup: %s\n' "$backup_dir/$name"
    fi
    if [[ "$mode" == copy ]]; then
      cp -R "$source_dir" "$dest"
    else
      ln -s "$source_dir" "$dest"
    fi
    printf 'Installed: %s\n' "$dest"
  done
done
