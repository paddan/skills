---
name: release
description: Använd när användaren skriver "/release", ber om att "släppa en ny version", "tagga release", "bump version", eller liknande. Bumpar versionsnummer, uppdaterar CHANGELOG/README, kör tester, committar, taggar och pushar (inklusive tag). Hanterar gradle, npm, cargo, python (pyproject.toml/setup.py), och rena git-tag-only-projekt. Skapar ingen GitHub-release eller PR.
---

# release — bumpa, tagga, push

Standardiserad release-pipeline. Hela poängen är att inget steg ska glömmas — taggar som inte pushas, CHANGELOG som inte uppdateras, och tester som inte körs är de typiska missarna.

## Förutsättningar

- Användaren är i ett git-repo.
- Arbetsträdet är **rent** (inga oommitade ändringar utöver det release:n själv kommer skapa). Om det finns oommitade ändringar: stoppa och fråga användaren vad som ska hända med dem (committa först? stasha?).
- Användaren är på rätt branch (default: `main`/`master`). Om de står på en feature-branch: fråga om det är avsiktligt.

## Steg 1 — Detektera projekttyp och nuvarande version

Leta efter versionsfiler i den här ordningen — flera kan finnas, hantera då alla:

| Filtyp | Var versionen står |
|--------|--------------------|
| `package.json` | `"version": "x.y.z"` |
| `Cargo.toml` | `version = "x.y.z"` under `[package]` |
| `pyproject.toml` | `version = "x.y.z"` under `[project]` eller `[tool.poetry]` |
| `setup.py` | `version="x.y.z"` |
| `build.gradle` / `build.gradle.kts` | `version = "x.y.z"` |
| `pom.xml` | `<version>x.y.z</version>` i toppnivå |
| `VERSION` / `version.txt` | hela filen är versionssträngen |
| Inget av ovan | använd senaste git-tag (`git describe --tags --abbrev=0`) |

Visa nuvarande version för användaren.

## Steg 2 — Fråga vilken version

Fråga användaren (med frågeverktyg om sådant finns i miljön, annars i klartext):

- **Patch** (`x.y.Z+1`) — buggfixar, inga API-ändringar
- **Minor** (`x.Y+1.0`) — nya features, bakåtkompatibelt
- **Major** (`X+1.0.0`) — brytande ändringar
- **Specifikt** — användaren skriver in versionen själv

Använd `vX.Y.Z` som tag-prefix om projektet redan använder det (kolla `git tag -l`), annars `X.Y.Z` utan prefix. Var konsekvent med befintliga taggar.

## Steg 3 — (Valfritt) Granska ändringar

Fråga om användaren vill köra `code-review`-skillen på `<senaste-tag>..HEAD` innan release. Default: ja, om det finns 5+ commits sedan senaste tag. För hotfix/patch-release med få commits: hoppa över förslaget.

Om granskning körs och hittar Kritiska eller Allvarliga problem: stoppa och fråga om de ska fixas innan release.

## Steg 4 — Uppdatera versionsfiler

Bumpa versionen i alla detekterade versionsfiler. Verifiera med en grep efter ändringen att versionen är korrekt i alla filer (lätt att missa en `package-lock.json` eller liknande).

## Steg 5 — Uppdatera CHANGELOG och README

- **CHANGELOG.md / HISTORY.md / NEWS.md** — om någon finns, lägg till en sektion för den nya versionen. Använd `git log --format='- %s' <senaste-tag>..HEAD` som råmaterial, men gruppera och städa: `feat:` → "Added", `fix:` → "Fixed", `docs:` → "Documentation", övriga → "Changed". Datera idag (använd `date +%Y-%m-%d`).

- **README.md** — om README nämner versionen direkt (badge, installation, kompatibilitetsmatris), uppdatera. Annars rör den inte.

Följ projektets befintliga CHANGELOG-stil om en sådan finns — vissa använder Keep a Changelog-format, andra fri form. Matcha tonalitet och språk (svenska/engelska).

## Steg 6 — Kör tester

Detektera testkommando och kör det:

| Projekttyp | Kommando |
|------------|----------|
| Gradle | `./gradlew test` |
| Maven | `./mvnw test` |
| npm | `npm test` |
| Cargo | `cargo test` |
| Python (pytest) | `.venv/bin/pytest` eller `pytest` |
| Make | `make test` |

Om testerna failar: stoppa, rapportera, fråga användaren vad som ska göras. Skippa **aldrig** tester på eget bevåg.

Om projektet uppenbarligen saknar tester: säg det till användaren och fortsätt.

## Steg 7 — Commit

Commit-meddelande följer projektets stil. Default-format:

```
chore(release): vX.Y.Z

<2–4 punkter från CHANGELOG-tillägget, om kort nog>
```

Stagea bara filer som hör till release:n — versionsfiler + CHANGELOG + ev. README. Använd HEREDOC:

```bash
git commit -m "$(cat <<'EOF'
chore(release): v1.2.3

- Added: ...
- Fixed: ...
EOF
)"
```

Lägg bara till en `Co-Authored-By`-trailer om projektets konvention kräver det, och använd då korrekt identitet — hitta inte på en egen.

## Steg 8 — Skapa tag

**Annoterad** tag, inte lättviktig — annoterade taggar lagrar metadata och syns korrekt i `git describe`:

```bash
git tag -a vX.Y.Z -m "Release vX.Y.Z"
```

Använd samma prefix-konvention som tidigare taggar.

## Steg 9 — Push

```bash
git push                # branch
git push origin vX.Y.Z  # tag (separat — taggar går inte med vanlig push)
```

Eller på en gång: `git push --follow-tags` (men bara om alla nya taggar ska med — säkrast att pusha tag:en explicit).

Aldrig `--force`. Om push avvisas: stoppa, rapportera, be om instruktion.

## Steg 10 — Bekräfta

Skriv en sammanfattning:

```
Release vX.Y.Z klar.
- Commit: <hash> (länk om remoten har webbgränssnitt)
- Tag: vX.Y.Z (samma)
- Branch: <branch> → <remote>
```

## Vad skillen inte gör

- Skapar **ingen GitHub-release**. Om användaren vill ha det: nämn att `gh release create vX.Y.Z --notes-from-tag` är vägen, men kör inte det automatiskt.
- Skapar **ingen PR**. Releaser går direkt på branchen.
- Force-push:ar aldrig.
- Tar inte bort eller flyttar befintliga taggar.
- Bumpar inte versioner i låsfiler manuellt — om `package-lock.json` eller liknande behöver uppdateras, kör paketmanagern (`npm install` etc.) så filen genereras korrekt.

## Vanliga fallgropar

- **Tag pushad utan branch (eller tvärtom).** `git push` pushar inte taggar; `git push --tags` pushar bara taggar. Båda behövs.
- **Glömt CHANGELOG.** Lätt att fokusera på versionsnumret och missa att dokumentera ändringarna.
- **Bumpat version i en fil men inte alla.** Vissa projekt har versionen på flera ställen (build-fil + `__init__.py` + README-badge). Grep:a efter den gamla versionen efter bump för att hitta alla förekomster.
- **Skapat lättviktig tag istället för annoterad.** `git tag vX.Y.Z` är lättviktig, `git tag -a vX.Y.Z -m "..."` är annoterad. Använd alltid `-a`.
