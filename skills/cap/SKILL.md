---
name: cap
description: Use when the user writes "/cap", asks to "commit and push", says "cap", "review and commit", "commita och pusha", or "granska och commit". Runs the code-review skill first, asks whether identified issues should be fixed, then commits and pushes. Does not create a PR.
---

# cap — review, commit, push

A combined workflow: code review → confirmation → commit → push. No PR.

## Prerequisites

- Inspect the current branch, working tree, remotes, and upstream. Treat uncommitted changes and commits awaiting push as separate states.
- A clean working tree does not end the workflow: review and push existing local commits when needed, without creating an empty commit.
- If neither a commit nor a push is needed, report that the repository is up to date.
- A missing upstream does not prevent pushing. Use Step 4 to establish it on the intended remote. If no remote exists, complete any requested local commit and report that push requires a destination; do not invent one.
- Use existing session authorization and repository conventions to resolve the target branch and remote. Ask only when the destination remains ambiguous.

## Step 0 — Run tests

Before reviewing and committing, run the project's test suite.

Detect the test framework in this order:

1. If `AGENTS.md` or `README.md` specifies a test command, use it.
2. `pyproject.toml` / `setup.py` with `.venv/`: use `.venv/bin/pytest` (or `pytest` if there is no virtual environment).
3. `package.json` with a `"test"` script: use `npm test`.
4. `Cargo.toml`: use `cargo test`.
5. `go.mod`: use `go test ./...`.
6. If none applies, skip this step and tell the user.

If tests **fail**:

- Briefly list the failing tests.
- Treat this as a **Critical** finding in the review, with a strong warning in Step 2.
- Do **not** stop automatically: let the user decide whether to fix the failures or commit anyway.

If tests **pass**, mention it briefly and continue.

## Step 1 — Run the code review

Load and follow the `code-review` skill using the runtime's available skill-loading mechanism, or read its `SKILL.md` directly. If it cannot be found, report the missing dependency.

Use its branch-review scope: the diff from the merge base against the relevant `main`/`master`, plus all staged and unstaged tracked-file changes. Exclude untracked files unless explicitly requested. A feature branch's upstream tracks publication state; it is not automatically the review base. Already-pushed feature commits remain in scope.

On `main`/`master`, review local changes and use the intended remote branch as an explicit comparison for commits awaiting push. If that remote branch does not exist, establish the initial publication range from history and disclose it. Keep the review scope separate from the push range.

Use the report format defined by `code-review`: Critical/Major/Minor and a Suggestions section, with the report language and labels specified by that skill.

## Step 2 — Ask the user

Show the review. If fixes are not already authorized, ask whether the user wants recommended fixes, a commit as is, or cancellation. Use a suitable user-input tool available in the runtime, or ask in plain text if no such tool is available. Honor the runtime's input-tool restrictions.

- **Fix recommended issues** — fix the issues recommended for immediate action in Suggestions. Rerun relevant tests and review the final changes before continuing.
- **Commit as is** — skip fixes and proceed directly to commit.
- **Cancel** — take no action and leave the working tree as it is.

If there are no findings, skip the question and proceed directly to commit.

If the review contains **Critical** issues, add an explicit warning and strongly recommend fixing them before committing.

## Step 3 — Commit

Follow the repository's existing commit style. Read the last 5–10 commit subjects with `git log --format='%s' -10` to match tone, prefixes (`feat:`, `fix:`, `docs:`, etc.), and language.

Keep exactly one commit containing the branch's changes before every push. Count only commits since the branch's merge base with the relevant base branch, never the inherited base history. On `main`/`master`, apply this rule only to local commits awaiting publication relative to the intended remote branch; do not rewrite published default-branch history. Establish the boundary before rewriting history; ask if it cannot be determined safely.

- If there are no commits in that range, create one commit for the in-scope changes.
- If there is already one commit, amend it with `git commit --amend` to include new in-scope changes instead of creating another commit. Update the message to describe the complete result.
- If there are multiple commits, squash all commits in that range into one before pushing, including when the working tree is clean. Include any new in-scope changes in that same commit.
- Skip committing only when there are no new in-scope changes and the range already contains at most one commit. Do not create an empty commit.
- Preserve the combined content of the reviewed changes and leave unrelated working-tree and staged changes untouched when amending or squashing.

- Use `git status` and `git diff --staged`, or `git diff` if nothing is staged, to understand the change.
- Write a message explaining why the change was made, not just what changed.
- Stage only files belonging to the change. Avoid `git add -A` when changes are mixed; ask the user which files to include if needed.
- Do not skip hooks. If a pre-commit hook fails, fix the cause and retry the intended create or amend operation. A failed commit attempt does not create a commit; do not accidentally amend the base commit.

Use a heredoc for a multiline message (add `--amend` when updating the existing branch commit):

```bash
git commit -m "$(cat <<'EOF'
<commit message>

EOF
)"
```

Do not invent co-author identities. Add an attribution trailer only when explicitly requested or required by repository conventions, using accurate supplied identity information.

## Step 4 — Push

- Resolve the intended remote and branch, inspect publication state, and refresh remote information when available. Do not declare the repository up to date solely from stale tracking refs.
- Verify that the range defined in Step 3 contains exactly one commit when publishing changes. If it contains more, return to Step 3 and squash before pushing.
- Push explicitly to that destination, such as `git push <remote> HEAD:refs/heads/<branch>`.
- If the branch has no upstream, use `git push -u <remote> HEAD:refs/heads/<branch>`. Use `origin` only when it is the intended remote. If no remote is configured, report the missing destination.
- **Never** use `--force` or `--force-with-lease` in this workflow. Amending or squashing already-pushed commits can require a history-rewriting push: explain this and ask for direction when detected, or if a normal push is rejected as non-fast-forward. Do not create an extra commit to bypass the one-commit rule or force-push on your own.
- Never push directly to `main`/`master` without verifying that this is intended, using the repository's conventions as context.
- After success, verify that the destination branch matches the pushed commit and report any remaining local changes. If push fails, preserve the local commit and describe the actual failure.

## Step 5 — Confirm

Give a short, one- or two-line summary of what was committed, what was pushed, and where it landed (branch and remote). For a GitHub repository, link the commit hash.

## What this skill does not do

- Create a PR. That is a separate workflow if requested.
- Override hooks or signing.
- Force-push.
- Create a branch: commit on the current branch.
