---
name: kodgranskning
description: Use when the user requests a code review, says "granska", "kodgranska", "review", "kolla koden", or asks for feedback on changes, a branch, a pull request, or specific files.
---

# Code Review

A thorough but pragmatic code review. Find real problems that help the developer, rather than producing a long list of cosmetic issues.

## When to use this skill

- The user asks to review code or look through changes.
- The user requests feedback on a PR, a diff, or specific files.
- If an ambiguous question such as "what do you think of this?" refers to code, briefly clarify whether they want a formal review before starting the full process.

## Step 1 — Establish the scope

Determine exactly what to review before reading code. Ask only when the scope is unclear; start immediately for an explicit branch, PR, or file request.

### Default: current branch plus all uncommitted changes

Review the current branch's diff from its branch point against the relevant `main` or `master`, together with **all staged and unstaged changes to Git-tracked files**, including newly added files already staged with `git add`. **Exclude untracked files**. Reviewing only the last commit, only `git diff`, or only the committed branch diff is incomplete.

1. Inspect the current branch and local state with `git branch --show-current`, `git status --short --untracked-files=no`, and `git branch -avv`.
2. Identify the base branch: the `main` or `master` from which the current branch diverged. Use available branch history, reflogs, and PR base metadata as evidence. `git symbolic-ref --quiet refs/remotes/origin/HEAD` can identify the remote default branch, but that default alone does not prove the branch's origin. The current branch's upstream may track the feature branch itself and is not automatically the review base.
3. Resolve an existing local or remote-tracking ref for that base, such as `main`, `master`, `origin/main`, or `origin/master`. If both names exist, choose using the evidence above; if the choice materially changes the review and remains ambiguous, ask. State the chosen ref and any uncertainty or stale remote information. Git does not permanently record which branch a branch was created from; do not claim certainty without evidence.
4. Compute the common ancestor with `git merge-base <base-ref> HEAD` and record it as `<base-commit>`. Use this branch point instead of comparing directly against the base branch's latest tip, which could include unrelated later changes. Inspect `git log <base-commit>..HEAD --oneline` and `git diff <base-commit> HEAD` for committed changes.
5. Inspect `git diff --cached` for staged changes and `git diff` for unstaged changes. Use `git diff <base-commit>` as the combined tracked-file diff from the branch point to the current working tree. Read the final file contents as context, while also inspecting both local layers so staged changes that are reversed in the working tree are not silently missed.
6. Exclude untracked files from the default review. New files already added to the index with `git add` are tracked and must be reviewed, including any subsequent unstaged edits to them. Use NUL-delimited output (`-z`) when processing file names programmatically.
7. Include tracked deletions and renames, staged additions, and all local changes even when their files were not touched by the branch's commits. For conflicts or dirty submodules, report the state and inspect the relevant available changes; disclose anything that could not be reviewed.

Do not stage, stash, reset, or commit files to construct the review. If already on `main` or `master`, review all local uncommitted changes; when committed work also needs review, establish an explicit commit range rather than inventing a feature-branch origin. If no usable base/common ancestor exists, review the available local changes and report the missing comparison.

### Explicit scope variants

An explicit user-specified PR, commit range, or file selection takes precedence over the default scope.

- **GitHub PR:** Use `gh pr view <number> --json title,body,baseRefName,headRefName,files,additions,deletions` and `gh pr diff <number>`. Extract the number from a supplied URL. For a PR-only request, review that PR's diff; if the user also includes the local branch/worktree, include its staged and unstaged tracked-file changes as above.
- **Files or directory:** Read the specified files directly. For a directory, list its contents first and prioritize files containing logic over generated code, lockfiles, and assets. Account for the remaining files as relevant to behavior, dependencies, or security.

For a large diff (>500 lines of changed logic), mention its size and invite an optional focus preference; otherwise review the full scope, prioritizing severity.

## Step 2 — Build context before judging

A comment without context is often wrong. Before listing problems:

- **Read surrounding code and callers.** A suspicious-looking function may be correct given how it is called.
- **Understand project conventions.** Inspect nearby files, `AGENTS.md`, `README`, and lint configuration. If the project consistently uses a pattern you dislike, treat it as an observation rather than an error.
- **Identify the intent.** Read the PR description or commit messages. A bug fix and a refactor require different expectations.

## Step 3 — What to look for

Check every category below, even for small diffs.

### Bugs and correctness

- Logic errors, off-by-one errors, and incorrect operators.
- Edge cases: empty input, null/undefined, negative numbers, Unicode, and very large values.
- Race conditions, unprotected shared state, and async bugs such as missing `await` or unhandled promises.
- Error handling: silent catches, swallowed errors, and exceptions not caught where they should be.
- Resource leaks: files/sockets left open and subscriptions not cleaned up.

### Security

- Injection: SQL, commands, XSS, path traversal, and SSRF.
- Authentication/authorization: verify that the right user may perform the operation; do not trust the client.
- Secrets in code, logs, or error messages.
- Unsafe defaults such as unrestricted CORS, weak cryptography, or obsolete TLS versions.
- External input used without appropriate validation.

### Style and readability

- Names that misrepresent what something does.
- Functions or files with too many responsibilities.
- Dead code, commented-out code, and unused imports.
- Comments that repeat the code instead of explaining *why*.
- Departures from project conventions.

### Documentation

- **README.md must remain correct and current.** If changes affect documented installation, usage, flags, examples, APIs, dependencies, environment variables, or project structure, verify that the README reflects the new behavior. A stale README misleads users and maintainers.
- Explicitly open and check the README during every review. Compare it with the diff and flag discrepancies. If a project clearly needs a README but has none, mention it.
- Other documentation, including CHANGELOG, `docs/`, and configuration comments, should also remain consistent; prioritize the README.
- Classify a stale README as **Major** when it causes users to take incorrect actions, or **Minor** when it only omits a detail.

### Performance and architecture

- N+1 queries and unnecessary nested loops.
- Unnecessary allocations in hot paths.
- Abstractions that add no value, such as an interface with one implementation and no expected alternatives.
- Tight coupling that makes future changes difficult.

### Test coverage

Check test coverage explicitly in every review.

1. **Locate the tests.** Typical locations include `tests/`, `test/`, `__tests__/`, `*_test.go`, `*.test.ts`, and `*_spec.rb`. Identify the framework and its conventions.
2. **For each changed or new logic file:** check for corresponding tests and whether relevant tests were updated or extended.
3. **For each bug fix:** look for a regression test that captures the failure.
4. **For new features:** check the happy path and important edge cases, including empty lists, null values, boundaries, and error paths.
5. **For changed public signatures:** verify that callers and tests were updated.

Identify concrete gaps, such as:

- A new `parse_redaction_block()` function with no tests.
- A logic fix without a corresponding regression test.
- New edge-case handling, such as `if not items: return []`, without coverage for that case.
- Tests covering only the happy path.
- Snapshots or fixtures that need updating.

When flagging a missing test:

- State **where** it belongs, following project conventions, for example `tests/test_<module>.py`.
- State **what** it should verify, such as returning an empty list for input without redactions.
- Suggest concrete test data when obvious, such as one page with two black blocks and one without any.

Be pragmatic; do not require 100% coverage:

- Trivial getters/setters, formatting-only changes, and pass-through code without logic normally do not need dedicated tests.
- Prototypes and experiments may justify lower expectations; clarify scope if needed.
- If the project has no tests at all, report that structural gap as an observation rather than escalating every file separately.

Severity guidance:

- **Major:** a bug fix without a regression test; an untested new public function; security-sensitive code without tests of its security behavior.
- **Minor:** an untested internal function, uncovered edge-case branches, or happy-path-only coverage.
- Optional low-risk coverage improvements belong in the prioritization discussion, not a new severity tier.

**Verify when feasible.** If coverage tooling is configured, such as `pytest --cov`, `cargo tarpaulin`, `jest --coverage`, or `go test -cover`, run it where practical and cite actual uncovered lines. Distinguish checks performed from assumptions and checks not run.

## Step 4 — Classify severity

Be honest about impact. Calling everything critical makes the label useless; finding cosmetic issues while missing injection is a failed review.

| Severity | Meaning |
|----------|---------|
| **Critical** | Must be fixed before merge: security vulnerabilities, data corruption, or crashes during normal use. |
| **Major** | Should be fixed before merge: clear edge-case bugs, user-visible performance problems, or architectural decisions that are expensive to reverse. |
| **Minor** | Worth fixing but can wait: style, small improvements, refactoring, and cosmetic issues. |

If there are no critical or major findings, say so directly. A short, accurate review is more useful than an inflated list.

**Suggestions is not a severity level.** It is a separate section recommending which numbered findings are worth fixing.

## Step 5 — Report format

Write the review report in **Swedish**, retaining the Swedish labels shown below. The skill instructions are in English; the report language remains Swedish unless the user requests another language.

Optimize for scanning: short clauses, sub-points, and tables where useful.

### Overall structure

```text
# Kodgranskning

**Scope:** <PR number and URL, commit range, or exact paths; include size. For a branch review, record the base ref, merge-base hash, HEAD hash, and staged/unstaged coverage and the exclusion of untracked files. Example: base origin/main, abc123..def456 plus staged and unstaged changes, 8 tracked files; untracked files excluded.>
**Sammanfattning:** <2–3 sentences explaining the change, overall assessment, and anything that must be fixed before merge.>

## Översikt

<Severity-count table>

## Kritisk

<Findings, or "Inget">

## Allvarlig

<Findings, or "Inget">

## Mindre

<Findings, or "Inget">

## Förslag

<Recommendations referring to finding numbers>
```

State any scope or verification limitations explicitly. Do not imply that untracked files or unavailable comparisons were reviewed when they were not.

### Overview table

Place a compact table immediately after the summary:

```text
| Severity  | Antal | Nummer | Område                         |
|-----------|-------|--------|--------------------------------|
| Kritisk   | 0     | —      | —                              |
| Allvarlig | 2     | 1–2    | Dokumentation, felhantering     |
| Mindre    | 7     | 3–9    | Bash, output-format, edge cases |
```

### Individual findings

**Number all findings consecutively across severity sections.** Critical starts at 1; Major and Minor continue the sequence. The user must be able to say "fix 1, 4, and 8" unambiguously. Suggestions refer to those numbers and do not introduce their own numbering.

Use this format, with report labels in Swedish:

```text
1. **<Short title>** — `path/file.ts:42`
   - **Problem:** <One sentence describing what is wrong.>
   - **Konsekvens:** <One sentence describing the effect on users, maintainers, or the system.>
   - **Fix:** <A concrete fix, if apparent; otherwise omit this line.>
   - **Bevis:** <Verified evidence, when available.>
```

Add evidence when a command, specification, or project rule substantiates the finding.

Trivial findings may fit on one line, for example a spelling correction from "recieve" to "receive". Do not force sub-points where they add no value.

Use code blocks sparingly, only when the code itself is central to understanding the finding, and keep them to 1–3 lines. Refer to longer context by file and line.

### Explain the consequence

Every nontrivial finding must explain its consequence. "This is wrong" is insufficient; "The process crashes after roughly 1,000 malformed requests because file handles leak" explains the impact and forces verification. Never omit this explanation for Major or Critical findings.

### File references

Include the file path and line number, preferably as a clickable link supported by the client. For several relevant lines, identify them individually, such as `file.py:42,58,71`.

## Step 6 — Suggestions and prioritization

This section recommends which numbered findings to fix based on:

- **Severity:** how important the problem is.
- **Complexity:** how expensive it is to fix, from a one-line change to an architectural redesign.

All Critical findings must be fixed. For Major and Minor findings, use judgment: some minor fixes take two minutes and are worth doing immediately; others can wait until the file is touched again. Complex Major findings may need their own PR.

Use a table listing the finding number, short rationale, and recommendation. Group into immediate fixes and deferred work where useful. Keep report labels in Swedish:

```text
### Fixa innan merge

| # | Problem (kort) | Varför nu | Insats |
|---|----------------|-----------|--------|
| 1 | <Issue>        | <Reason>  | ~10 min |
| 2 | <Issue>        | <Reason>  | ~5 min  |

### Kan vänta

| # | Problem (kort) | Varför vänta | Notering |
|---|----------------|--------------|----------|
| 3 | <Issue>        | <Reason>     | <Note>   |
```

When useful, end with a concise recommendation identifying what to fix now and what can remain follow-up work. Give concrete effort estimates when justified; "~5 min" is more useful than "small".

## Tone

Write to a fellow developer, not a student being graded. Be direct without condescension. Explain why an issue matters: a leaked file handle when `parse` throws on invalid JSON is a concrete finding.

State uncertainty honestly. If thread safety depends on whether a cache is shared between threads, investigate that usage and distinguish what is known from what still needs clarification.

## Common pitfalls

- **Reviewing code you do not understand.** Read more context before commenting. A false finding is worse than no finding.
- **Recommending unnecessary abstractions.** Extracting a helper is useful when reuse or readability justifies it.
- **Focusing on style while missing bugs.** Prioritize correctness and security.
- **Failing to test assumptions.** Substantiate suspected bugs through callers, tests, or a reproduction.
- **Reviewing an incomplete diff.** A branch review includes the branch-point diff and all staged and unstaged tracked-file changes, excluding untracked files, unless the user explicitly narrows the scope.
