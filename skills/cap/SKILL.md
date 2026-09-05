---
name: cap
description: Använd när användaren skriver "/cap" eller ber om att "commita och pusha", "cap", "granska och commit". Kör först kodgranskning-skillen på aktuella ändringar, frågar om upptäckta problem ska fixas, gör sedan commit och push. Skapar ingen PR.
---

# cap — granska, commit, push

Ett kombinerat flöde: kodgranskning → bekräfta → commit → push. Ingen PR.

## Förutsättningar

- Användaren är i ett git-repo med aktuella ändringar (staged eller unstaged).
- Om det inte finns något att committa: säg det rakt ut och avbryt.
- Om det inte finns någon remote eller upstream: gör commit ändå, men säg till användaren att push hoppas över.

## Steg 0 — Kör tester

Innan granskning och commit: kör projektets testsuite.

Detektera testramverk i den här prioritetsordningen:

1. `AGENTS.md` / `README.md` nämner ett specifikt testkommando → använd det.
2. `pyproject.toml` / `setup.py` + `.venv/` → `.venv/bin/pytest` (eller `pytest` om venv saknas).
3. `package.json` med `"test"`-script → `npm test`.
4. `Cargo.toml` → `cargo test`.
5. `go.mod` → `go test ./...`.
6. Hittas inget → hoppa över steget och notera det för användaren.

Kör testerna. Om de **misslyckas**:

- Lista de felaktiga testerna kortfattat.
- Behandla det som ett **Kritiskt**-fynd i granskningen (dvs. fråga med stark varning i Steg 2).
- Avbryt **inte** automatiskt — låt användaren bestämma om de vill fixa eller committa ändå.

Om de **passerar**: notera det kort ("Alla tester passerade") och gå vidare.

## Steg 1 — Kör kodgranskning

Anropa `kodgranskning`-skillen via Skill-verktyget. Scope är de **lokala ändringarna som inte är pushade ännu**:

- Om branchen har en upstream: granska `<upstream>..HEAD` plus staged och unstaged ändringar.
- Om branchen saknar upstream: granska alla commits på branchen plus staged och unstaged ändringar (eller jämför mot `main`/`master` om det är rimligt).

Granskningen ska produceras enligt det format som `kodgranskning` definierar — Kritisk/Allvarlig/Mindre + Förslag-sektion.

## Steg 2 — Fråga användaren

Visa granskningen och fråga om de vill fixa något innan commit. Använd `AskUserQuestion`-verktyget med tre alternativ:

- **Fixa rekommenderade problem** — du fixar de punkter som Förslag-sektionen rekommenderar att fixa nu. Efter fix, kör om granskningen kort (bara verifiera att de fixade punkterna är borta) och fortsätt till commit.
- **Committa som det är** — hoppa över fixar, gå direkt till commit.
- **Avbryt** — inget händer, behåll arbetsträdet som det är.

Om granskningen är tom (inget hittat) — hoppa över frågan och gå direkt till commit.

Om granskningen innehåller **Kritiska** problem — fråga med en extra varning och rekommendera starkt att fixa innan commit.

## Steg 3 — Commit

Följ projektets befintliga commit-stil. Läs senaste 5–10 commits med `git log --format='%s' -10` för att matcha tonalitet, prefix (`feat:`, `fix:`, `docs:` etc.), språk (svenska/engelska).

- Använd `git status` och `git diff --staged` (eller `git diff` om inget är staged) för att förstå ändringen.
- Skriv ett meddelande som beskriver *varför*, inte bara *vad*.
- Stagea bara filer som hör till ändringen. Undvik `git add -A` om det finns blandade ändringar — fråga då användaren vilka filer som ska med.
- Hoppa inte över hooks. Om en pre-commit-hook felar: fixa orsaken och försök igen, **amend:a inte** den misslyckade commiten — skapa en ny.

Använd HEREDOC för att passa multiline-meddelande:

```bash
git commit -m "$(cat <<'EOF'
<commit-meddelande>

Co-Authored-By: Codex Opus 4.7 <noreply@anthropic.com>
EOF
)"
```

## Steg 4 — Push

- Om branchen har upstream: `git push`.
- Om branchen saknar upstream: `git push -u origin <branch>`.
- Push:a **aldrig** med `--force` eller `--force-with-lease` i det här flödet. Om en vanlig push avvisas (icke-fast-forward): säg det till användaren och be om instruktion — kör inte en force-variant på eget bevåg.
- Push:a aldrig till `main`/`master` direkt utan att verifiera att det är önskat (jämför med konventioner i repo).

## Steg 5 — Bekräfta

Skriv en kort sammanfattning på en till två rader: vad som committades, vad som pushades, och var det landade (branch + remote). Använd markdown-länk till commit-hashen om det är ett GitHub-repo.

## Vad skillen inte gör

- Skapar ingen PR. Om användaren vill ha en PR — det är ett separat flöde.
- Skriver inte över hooks eller signering.
- Force-push:ar aldrig.
- Skapar inte en branch — committar på den nuvarande.
