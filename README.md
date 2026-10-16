# TruthLens AI — local MVP

TruthLens is an evidence-aware local MVP for WELFake article classification, FEVER claim verification,
LIAR experimental related-claim analysis, and FaceForensics++ image assessment. It stores model artifacts
locally and does not call external inference APIs.

Private analysis requires a signed-in USER (or FACT_CHECKER/ADMIN). Guest submission is not implemented.

The SRS text dataset name FakeNewsNet is superseded here by WELFake. FEVER is used for evidence
verification. Image work is FaceForensics++ frames, not video/audio detection.

---

# Handoff: run it from a fresh clone with Docker Desktop

Target reader: someone cloning this repository who has **Docker Desktop only** — no Java, no Python, no
Node, no PostgreSQL install required for the Docker path.

## 0. What is in Git, and what is not

| | |
|---|---|
| **In** the repository (all you need to build) | `ai-service/` (app.py, related_passages.py, requirements.txt, Dockerfile, tests/), `backend/` (pom.xml, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`), `frontend/` (src/, index.html, package.json, package-lock.json, tsconfig.json, vite.config.ts), `docker-compose.yml`, `.env.example`, `.gitignore`, `backend/.dockerignore`, `ai-service/.dockerignore`, `docs/`, `scripts/`, `.github/workflows/ci.yml`, `model_files.txt` (a listing of the asset tree) |
|---|---|
| **Not** in the repository | `models/` (12.1 GB of exported checkpoints and indexes), `data/` (9.3 GB raw/licensed datasets), `storage/` (user uploads and the analysis output directory), `verification-logs/` (1.44 GB of measurement evidence), `.tools/`, `ai-service/.venv/`, `codes/myenv/`, `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, `.env` |

Every bind mount in `docker-compose.yml` is a **relative** path (`./models`, `./storage`,
`./verification-logs`, `./frontend`), so the clone works from any directory — nothing points at
`D:\TruthLens`.

## 1. Clone

```powershell
git clone https://github.com/ProfOP/TruthLens.git
cd TruthLens
```

## 2. Supply the model assets (not in Git — required)

`ai-service` reads its artifacts from `TRUTHLENS_ROOT/models`, i.e. `<clone dir>\models` via the
read-only bind mount. These six paths are the ones the serving code loads:

| Path (relative to the clone root) | Size | Used by |
|---|---|---|
| `models/Welfake/results/truthlens_distilbert/` | 256 MB | WELFake article classification |
| `models/Welfake/results/truthlens_text_model_config.json` | ~3 KB | the text operating point + provenance |
| `models/FFC++/truthlens_faceforensics_model/` | 33 MB | FaceForensics++ image assessment |
| `models/Liar/truthlens_liar_models/` | 104 MB | LIAR related-claim analysis |
| `models/Fever/truthlens_fever_models/` | 711 MB | MiniLM encoder + NLI cross-encoder |
| `models/Fever/truthlens_fever_iteration2/` | 175 MB | deployed evidence index (107,525 sentences) |
| **total** | **~1.28 GB** | |

How to obtain them: they were produced on the developer machine and are transferred **outside GitHub**
(each folder also exists as a sibling export archive, e.g. `models/Fever/truthlens_fever_models.zip`
647 MB, `models/Fever/truthlens_fever_iteration2.zip` 160 MB,
`models/FFC++/truthlens_faceforensics_model.zip`, `models/Liar/truthlens_liar_models.zip`,
`models/Welfake/results/truthlens_distilbert_model.zip`). Copy the folders, or copy the zips and
extract them so the paths above exist unchanged under `<clone dir>\models`. Ask the developer for the
transfer (USB / private file share); do not attempt to re-create them here — retraining is out of
scope for this handoff and the published accuracy figures are tied to these exact bytes.

Also create an empty `storage/` directory next to `docker-compose.yml` (Docker Desktop creates it if it
is missing; the backend and the AI service both mount it read-write).

Optional, only for the disabled-by-default candidate-discovery feature:
`verification-logs/fever-title-pilot-20261015/` (742 MB `title-index.sqlite3`, folder 836 MB),
`verification-logs/srs-fever-corpus-20261015/` (282 MB) and
`models/Fever/truthlens_fever/raw/wiki-pages.zip` (1 634 MB). Without them keep
`TRUTHLENS_RELATED_PASSAGES=0`; the served verdict path never reads them.

If `models/` is missing the stack still starts (artifacts load lazily per request), but
`GET :8001/health` reports the four components as unavailable and every analysis ends `FAILED`. The
asset bundle is therefore not optional.

## 3. Configure secrets

```powershell
Copy-Item .env.example .env
notepad .env
```

Set `POSTGRES_PASSWORD` (any local secret) and `JWT_SECRET` (at least 32 characters). Compose fails fast
if `JWT_SECRET` is unset. Never commit `.env`, never paste a real value into an issue, PR or chat.

## 4. Build and start

From the clone root, with Docker Desktop running:

```powershell
docker compose config --quiet      # interpolates .env, validates the file
docker compose up -d --build       # first build: Maven package + pip install (torch), allow a few minutes
```

Startup order is enforced by health gates: postgres (`pg_isready`) -> ai-service (`/health`, up to
120 s start period) -> backend (Flyway V1-V5 applies automatically) -> frontend.

## 5. Health checks

```powershell
docker compose ps                  # 4 services running; postgres + ai-service "healthy"
curl.exe http://localhost:8001/health
```

Expect `"status":"ok"`, `"device":"cpu"`, and `available: true` under `components` for `welfake`,
`faceforensics`, `liar` and `fever`. `http://localhost:5173` is the UI; the backend API is
`http://localhost:8080/api/v1` (browser-side default, CORS allows `http://localhost:5173` and
`http://127.0.0.1:5173`).

## 6. First sign-in: create an ADMIN account

The repository ships **no** default admin and no admin credential. The database starts empty; the first
person to register is an ordinary `USER`.

1. Open `http://localhost:5173`, register, sign in, and use the app (that already proves the stack).
2. Promote that account once, from the Compose database:

```powershell
docker compose exec postgres psql -U truthlens -d truthlens -c "update users set role='ADMIN' where email='you@example.com';"
```

Sign out and back in (the JWT filter re-reads `role`/`active` from the database on every request, so no
restart is needed).

Alternative, the bootstrap runner built into the backend, if you would rather not touch SQL. Set the
password through the environment so it never lands on a command line, and keep `TRUTHLENS_JOBS_SYNC=true`
so this one-shot process does not start a second job worker:

```powershell
$env:TL_PW = Read-Host 'admin password'
$env:TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD = $env:TL_PW
docker compose run --rm -e TRUTHLENS_BOOTSTRAP_ADMIN_EMAIL=you@example.com -e TRUTHLENS_BOOTSTRAP_ADMIN_NAME=Admin -e TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD -e TRUTHLENS_JOBS_SYNC=true backend --spring.main.web-application-type=none
Remove-Item Env:TL_PW,Env:TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD
```

## 7. What the handoff does **not** include

The supervisor's Docker database starts fresh. The developer's admin login, existing user accounts,
stored analyses and uploaded images are **not** part of the repository — they live in the named volume
`truthlens_truthlens-db` and in `storage/`, which are deliberately untracked. Nothing needs to be
imported to run the demo; create your own accounts.

Known deployment limitation, kept deliberately and checked against the SRS on 2026-10-17: the `frontend`
service is a `node:22-alpine` dev container that bind-mounts `./frontend` and runs `npm install &&
npm run dev`. The UI you see is host source served by a container, not a baked frontend image. `docs/`
and `scripts/` also assume the repository root as the working directory.

## 8. Stop / restart

```powershell
docker compose stop                 # containers removed, data kept
docker compose up -d                # start again, reports come back unchanged
docker compose restart backend ai-service
```

**Do not** run `docker compose down -v` — it deletes the named volumes (`truthlens_truthlens-db`,
`truthlens_truthlens-frontend-modules`), i.e. the database and every stored analysis.

## 9. CI

`.github/workflows/ci.yml` runs on every push/PR: frontend `npm ci && npm test && npm run build`,
backend `./mvnw -DskipTests compile` plus `./mvnw -Dtest=JwtServiceTest test`, ai-service syntax
compile. It needs no model assets.

---

# Development without Docker (native Windows)

1. Copy `.env.example` to `.env` and set `SPRING_DATASOURCE_PASSWORD` and `JWT_SECRET` (at least 32
   characters). Do not commit `.env`.
2. Project JDK: `D:\TruthLens\.tools\jdk21\jdk-21.0.12.1+1` (this path is developer-local; `.tools/` is
   not in Git).
3. From the repository root:

```powershell
powershell -File scripts\start-local.ps1
```

The script reads `.env` without printing values and sets `TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD=0.70` and
`TRUTHLENS_IMAGE_UNCERTAIN_MARGIN=0.05` unless already set. Uvicorn does not load `.env.example` by
itself. The script checks each target port first and will NOT launch a duplicate onto an occupied port
(it prints which port is already in use); it writes timestamped logs so a running instance's logs are not
clobbered, and reports readiness by polling each service rather than treating a launched PID as "ready" —
it exits non-zero if any component is not verified up. Stop the recorded stack with
`powershell -File scripts\stop-local.ps1` before a clean restart; that script only kills PIDs recorded in
`.truthlens-pids.txt` and will not stop unrelated Java processes.

Manual equivalent:

```powershell
$env:JAVA_HOME = "$PWD\.tools\jdk21\jdk-21.0.12.1+1"
$env:TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD = '0.70'
$env:TRUTHLENS_IMAGE_UNCERTAIN_MARGIN = '0.05'
ai-service\.venv\Scripts\python.exe -m uvicorn app:app --app-dir ai-service --port 8001
# other terminal
cd backend; .\mvnw.cmd spring-boot:run
# other terminal
cd frontend; npm run dev
```

The native path additionally needs a local PostgreSQL on `localhost:5432`, database `truthlens`, user
`truthlens`, and a Python environment for `ai-service` (see `ai-service/requirements.txt`).
Required runtime variables: `SPRING_DATASOURCE_PASSWORD`, `JWT_SECRET` (min 32 characters).

Native admin bootstrap (no Docker, needs the project JDK):

```powershell
powershell -File scripts\bootstrap-admin.ps1 -Email 'you@example.com' -Name 'Admin' -Password (Read-Host -AsSecureString) -JwtSecret $env:JWT_SECRET
```

If port 8080 is already in use by a previous TruthLens jar/`spring-boot:run`, either stop that specific
project process yourself or start with
`powershell -File scripts\start-local.ps1 -BackendPort 8090` and point `VITE_API_URL` at that port. Do not
overwrite `backend\target\truthlens-backend-0.1.0.jar` while a process has it locked.

# Administrator system control

One persisted runtime setting is exposed: **Accept new analyses** (`submissions.enabled` in the
`system_settings` table, seeded open).

- UI: Admin -> *System controls*. The switch is applied only when the button is pressed, and the record
  shows who last changed it and when.
- API: `GET` / `PATCH /api/v1/admin/settings` (ADMIN only — enforced both by the security filter and by
  the controller's role check). Body is `{"submissionsEnabled": true|false}`; any other value, or a
  missing key, is rejected with 400.
- Effect while paused: `POST /api/v1/analysis/text|claim|image` returns 503 with
  `New analyses are paused by the administrator. Existing reports remain available.` The Analyze page
  reads `GET /api/v1/system/status` (any signed-in role) and shows the pause before anyone presses
  submit. Reports, History, Dashboard and the review queue are unaffected.
- The value is read from the database on every submission, so no restart is required. Each accepted
  change writes one `ADMIN_SYSTEM_SETTING_UPDATE` audit event.
- Model thresholds (text 0.70, image binary 0.42 with 0.05 margin), the three-claim limit, JWT and
  storage rules are deliberately **not** editable here: they are fixed by the verified artifacts the
  accuracy figures in `docs/verification.md` depend on.
- Job pickup is not an admin setting either. A committed queue row wakes the idle worker immediately; the
  fallback idle wait is `truthlens.jobs.poll-ms`, whose 1000 ms default lives in the code
  (`AnalysisWorker` constructor, `@Value("${truthlens.jobs.poll-ms:1000}")`, floored at 1 ms) and is
  **not** listed in `backend/src/main/resources/application.properties`, where
  `truthlens.jobs.workers`, `lease-seconds`, `heartbeat-ms` and `sweep-ms` are. Set it there or in the
  environment and restart; there is no in-app control for it. Measured effect of the wake-up: submit ->
  first claim row 0.505-0.976 s -> 0.081-0.121 s, with report output unchanged.

`powershell -File scripts\restart-backend.ps1` (native only) stops only the `backend=` PID recorded in
`.truthlens-pids.txt` (and its children) and starts it again; the AI service and the dev server stay up.

# Safety and semantics

Results are automated assessment requiring human verification; not definitive proof. WELFake credibility
is P(REAL)x100. LIAR similarity is relatedness, not evidence. FEVER uses only the local limited corpus;
two larger lawfully built candidate indices were measured against FEVER gold on 2026-10-15 (merged top-5
gold recall 0.750 then 0.870, both **in-sample**) and a third, page-title-search route was then evaluated
on **400 unseen** claims (0.275 -> 0.645 merged top-5,
`verification-logs/fever-title-pilot-20261015/` — an untracked local directory); **none of the three is
deployed**, so the served retrieval stage remains the 107,525-sentence Iteration-2 index at 0.275 and the
> 80 % retrieval target is not met. FaceForensics++ is evaluated on facial-manipulation frames, not
arbitrary internet images. Deployment rules in force, both of them abstention overrides over unchanged
exported checkpoints: text is `UNCERTAIN` when max softmax < **0.70** (exported operating point 0.96),
and an image is `UNCERTAIN` when |P(manipulated) - **0.42**| < **0.05** (exported margin 0.135). These
dataset metrics are not live-service accuracy, and the image metric does not meet the SRS image-accuracy
target (70.07 % overall on held-out FaceForensics++ frames).

See `docs/architecture.md`, `docs/model-integration.md`, `docs/srs-traceability.md`,
`docs/verification.md`, and `docs/implementation-status.md`.
