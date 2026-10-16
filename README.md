# TruthLens AI — local MVP

TruthLens is an evidence-aware local application: WELFake article classification, FEVER claim
verification against a local evidence index, LIAR related-claim analysis, and FaceForensics++ image
assessment. Model artifacts are read from local disk only; no external inference API is called.

Private analysis requires a signed-in account (USER, FACT_CHECKER or ADMIN). Guest submission is not
implemented. An article is verified for at most three claims.

Naming: the SRS calls the text dataset FakeNewsNet; this build uses WELFake. Evidence verification uses
FEVER. Image work is single FaceForensics++ frames, not video or audio detection.

Evaluation and design documentation lives in `docs/architecture.md`, `docs/model-integration.md`,
`docs/implementation-status.md`, `docs/srs-traceability.md` and `docs/verification.md`. This README is
setup and usage only; it does not restate those documents.

---

# Supervisor setup: run it from a fresh clone with Docker Desktop

Access required: the repository is **private**, so you need read access to
`https://github.com/ProfOP/TruthLens` (collaborator invite or organisation membership). The model
bundle is **not** on GitHub and must be obtained from the developer separately — see §2.

Prerequisites: Docker Desktop only. No Java, Python, Node or PostgreSQL install is needed for the Docker
path. Ports **8001, 8080 and 5173** must be free on the machine.

## 0. What is in Git, and what is not

| | |
|---|---|
| **In** the repository (all you need to build) | `ai-service/` (app.py, related_passages.py, requirements.txt, Dockerfile, tests/), `backend/` (pom.xml, `mvnw`, `mvnw.cmd`, `.mvn/`, `src/`), `frontend/` (src/, index.html, package.json, package-lock.json, tsconfig.json, vite.config.ts), `docker-compose.yml`, `.env.example`, `.gitignore`, `backend/.dockerignore`, `ai-service/.dockerignore`, `docs/`, `scripts/`, `.github/workflows/ci.yml`, `model_files.txt` (a listing of the asset tree) |
| **Not** in the repository | `models/` (12.1 GB of exported checkpoints and indexes; only ~1.3 GB is read at run time, see §2), `data/` (9.3 GB raw/licensed datasets — not needed to run the app), `storage/` (user uploads and analysis output), `verification-logs/` (1.44 GB, including the optional feature's pilot index), `.tools/`, `ai-service/.venv/`, `codes/myenv/`, `backend/target/`, `frontend/node_modules/`, `frontend/dist/`, `.env` |

Every bind mount in `docker-compose.yml` is a **relative** path (`./models`, `./storage`,
`./verification-logs`, `./frontend`), so the clone works from any directory — nothing points at the
developer's absolute path.

## 1. Clone

```powershell
git clone https://github.com/ProfOP/TruthLens.git
cd TruthLens
```

## 2. Supply the model assets (not in Git — required)

`ai-service` reads its artifacts from `TRUTHLENS_ROOT/models`, i.e. `<clone dir>\models`, through the
read-only bind mount. These are the paths the serving code loads:

| Path (relative to the clone root) | Size | Used by |
|---|---|---|
| `models/Welfake/results/truthlens_distilbert/` (5 files) | 269 MB | WELFake article classification |
| `models/Welfake/results/truthlens_text_model_config.json` | 491 B | text tokeniser length, label mapping, credibility-score definition |
| `models/FFC++/truthlens_faceforensics_model/` (13 files) | 34 MB | FaceForensics++ image assessment |
| `models/Liar/truthlens_liar_models/` (14 files) | 109 MB | LIAR related-claim analysis |
| `models/Fever/truthlens_fever_models/` (17 files, incl. `minilm_encoder/` and `nli_model/`) | 746 MB | claim encoder + NLI cross-encoder |
| `models/Fever/truthlens_fever_iteration2/` (5 files) | 184 MB | evidence index used for claim verification |
| **total** | **~1.34 GB** | |

Copy these six paths as they are. Each folder also exists as a sibling archive on the developer machine
(`models/Welfake/results/truthlens_distilbert_model.zip` 247 MB,
`models/FFC++/truthlens_faceforensics_model.zip` 31 MB, `models/Liar/truthlens_liar_models.zip` 99 MB,
`models/Fever/truthlens_fever_models.zip` 647 MB,
`models/Fever/truthlens_fever_iteration2.zip` 160 MB — ~1.18 GB in total). Ask the developer for the
transfer (USB or private file share); retraining is out of scope for this handoff.

Two extraction rules, both verified against the code:

- The archives store their entries **flat**, with no top-level folder. Create the destination folder
  first and extract into it, so the paths in the table above exist unchanged.
- The `*.json` configuration files inside those folders are **loaded at request time**, not decorative:
  `FFC++/truthlens_faceforensics_model/model_config.json` supplies the image input size, normalisation,
  temperature and binary threshold; `Fever/truthlens_fever_iteration2/experiment_report_v2.json` supplies
  the evidence gate parameters; `Welfake/results/truthlens_text_model_config.json` supplies the text
  `max_length`, label mapping and credibility-score definition. Omitting one silently changes behaviour,
  so copy whole folders rather than picking weights only. Note that the text config file sits next to the
  `truthlens_distilbert/` folder and is **not** inside `truthlens_distilbert_model.zip` — copy it
  separately.

Create an empty `storage/` directory next to `docker-compose.yml` (Docker Desktop creates it if it is
missing; the backend and the AI service both mount it read-write).

Optional, only for the disabled-by-default candidate-discovery feature (§11):
`verification-logs/fever-title-pilot-20261015/` (877 MB folder, of which `title-index.sqlite3` is 742 MB),
`verification-logs/srs-fever-corpus-20261015/` (296 MB) and
`models/Fever/truthlens_fever/raw/wiki-pages.zip` (1 713 MB). Without them keep
`TRUTHLENS_RELATED_PASSAGES=0`.

If `models/` is missing the stack still starts (artifacts load lazily per request), but
`GET http://localhost:8001/health` reports the four components as unavailable and every analysis ends
`FAILED`. The bundle in the table above is therefore not optional.

## 3. Configure secrets

```powershell
Copy-Item .env.example .env
notepad .env
```

`.env.example` lists every variable the stack reads. For the Docker path only three matter:

- `POSTGRES_PASSWORD` — used both for the database superuser and, through `docker-compose.yml`, as the
  backend datasource password. Any local secret.
- `JWT_SECRET` — required; Compose fails fast if it is unset. At least 32 characters.
- `TRUTHLENS_RELATED_PASSAGES` — leave at `0` unless you took the optional assets in §2.

`docker-compose.yml` also interpolates `TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD`,
`TRUTHLENS_IMAGE_UNCERTAIN_MARGIN` and `TRUTHLENS_RELATED_PASSAGES` from `.env` when you set them, and
falls back to the deployment values `0.70`, `0.05` and `0` otherwise — so on the Docker path you can leave
them out. The other entries in `.env.example` (`SPRING_DATASOURCE_*`, `AI_SERVICE_URL`,
`TRUTHLENS_STORAGE_DIR`) are used only by the native scripts in the last section; Compose sets its own
in-network equivalents and ignores them. Never commit `.env`, never paste a real value into an issue, PR
or chat.

## 4. Build and start

From the clone root, with Docker Desktop running:

```powershell
docker compose config --quiet      # interpolates .env, validates the file
docker compose up -d --build       # first build: Maven package + pip install (torch), allow a few minutes
```

Startup order is enforced by health gates: postgres (`pg_isready`) -> ai-service (`/health`, up to a 120 s
start period) -> backend (Flyway V1-V5 applies automatically to a fresh database) -> frontend.

## 5. Health checks

```powershell
docker compose ps                  # 4 services running; postgres + ai-service "healthy"
curl.exe http://localhost:8001/health
```

Expect `"status":"ok"`, `"device":"cpu"`, and `available: true` under `components` for `welfake`,
`faceforensics`, `liar` and `fever`. `/ready` answers true as soon as any component is present.

The backend has **no unauthenticated health endpoint**: only `/api/v1/users/register` and
`/api/v1/users/login` are public. After you sign in as ADMIN, `GET /api/v1/admin/health` returns the
backend status plus the AI service's own health body, and `GET /api/v1/admin/models` returns the loaded
component status. `http://localhost:5173` is the UI; the browser-side API default is
`http://localhost:8080/api/v1` (override with `VITE_API_URL`, as an environment variable or in
`frontend/.env.local`). CORS allows only
`http://localhost:5173` and `http://127.0.0.1:5173`, so do not remap the frontend port and expect the
browser to work.

## 6. First sign-in: register, then create the ADMIN account

The repository ships **no** default admin and no admin credential. The database starts empty and the
account you register is an ordinary `USER`.

1. Open `http://localhost:5173`, register, sign in, and use the app (that already proves the stack).
2. Create the ADMIN account with the bootstrap runner built into the backend image. The password is
   entered as a `SecureString`, passed to the container as a temporary environment variable, and cleared
   in `finally`, so it never lands on a command line or in a file:

```powershell
$adminEmail = 'you@example.com'      # must NOT already exist; register uses a different address
$adminName  = 'Admin'
$secure = Read-Host 'Admin password (min 8 chars)' -AsSecureString
$env:TRUTHLENS_BOOTSTRAP_ADMIN_EMAIL = $adminEmail
$env:TRUTHLENS_BOOTSTRAP_ADMIN_NAME  = $adminName
$env:TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD = [System.Net.NetworkCredential]::new('', $secure).Password
try {
  docker compose run --rm --no-deps --entrypoint java `
    -e TRUTHLENS_BOOTSTRAP_ADMIN_EMAIL -e TRUTHLENS_BOOTSTRAP_ADMIN_NAME `
    -e TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD -e TRUTHLENS_JOBS_SYNC=true `
    backend -jar /app/app.jar --spring.main.web-application-type=none
} finally {
  Remove-Item Env:TRUTHLENS_BOOTSTRAP_ADMIN_EMAIL,
              Env:TRUTHLENS_BOOTSTRAP_ADMIN_NAME,
              Env:TRUTHLENS_BOOTSTRAP_ADMIN_PASSWORD -ErrorAction SilentlyContinue
}
```

`--no-deps` reuses the containers that are already running; `TRUTHLENS_JOBS_SYNC=true` keeps this one-shot
process from starting a second analysis worker. The runner is idempotent: if the email already exists it
does nothing, and it never overwrites an existing account. Sign out and sign in as that ADMIN — the JWT
filter re-reads `role` and `active` from the database on every request, so no restart is needed.

## 7. Using the app

- **Analyze** — submit an article (title + body), a single claim, or an image. Submission returns
  immediately (`202`) and the report appears when the queued job reaches `COMPLETED`.
- **Reports / History** — past analyses, per-claim verdicts, retrieved evidence, SHAP token contributions
  for text and a Grad-CAM heatmap for images.
- **Dashboard** — counts and status mix over stored analyses.
- **Admin** — user management, the review queue, model/component status, and system controls.

## 8. What the handoff does **not** include

The supervisor's database starts fresh. The developer's admin login, existing user accounts, stored
analyses and uploaded images are **not** part of the repository — they live in the named volume
`truthlens_truthlens-db` and in `storage/`, which are deliberately untracked. Nothing needs to be
imported to run the demo; create your own accounts.

Known deployment characteristic, kept deliberately: the `frontend` service is a `node:22-alpine` dev
container that bind-mounts `./frontend` and runs `npm install && npm run dev`. The UI you see is host
source served by a container, not a baked frontend image. `docs/` and `scripts/` also assume the
repository root as the working directory.

## 9. Stop / restart

```powershell
docker compose stop                 # containers are stopped, NOT removed; data is kept
docker compose up -d                # start them again, reports come back unchanged
docker compose restart backend ai-service
```

**Do not** run `docker compose down -v` — it deletes the named volumes
(`truthlens_truthlens-db`, `truthlens_truthlens-frontend-modules`), i.e. the database and every stored
analysis.

## 10. CI

`.github/workflows/ci.yml` runs on every push and pull request: frontend `npm ci`, `npm test`,
`npm run build`; backend `./mvnw -q -DskipTests compile` and `./mvnw -q -Dtest=JwtServiceTest test`;
ai-service `python -m compileall app.py` plus an AST parse of `app.py`. It needs no model assets.

## 11. Optional: candidate "related passages" display

`TRUTHLENS_RELATED_PASSAGES=1` adds a `related_passages` block to the claim-verification response: top-5
passages retrieved from the optional FEVER page-title pilot index (§2) and shown as candidate material in
the report. It ships `0`, because the assets it reads are not part of the Git repository, and it is
display-only: those candidates are not part of the evidence that determines a verdict. With the flag at
`0` the verification response is exactly as before; rollback is that one value plus a restart.

---

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
- Model thresholds (text confidence 0.70, image binary 0.42 with a 0.05 uncertain margin), the three-claim
  limit, JWT and storage rules are deliberately **not** editable here. The text threshold and the image
  margin are deployment values passed from `.env` through `docker-compose.yml`; the image binary threshold
  is read from `model_config.json` inside the model bundle. None of them is editable in the UI, and the
  evaluation documents assume these values.
- Job pickup is not an admin setting either. A committed queue row wakes the idle worker immediately; the
  fallback idle wait is `truthlens.jobs.poll-ms`, whose 1000 ms default lives in the code
  (`AnalysisWorker` constructor, `@Value("${truthlens.jobs.poll-ms:1000}")`, floored at 1 ms) and is
  **not** listed in `backend/src/main/resources/application.properties`, where
  `truthlens.jobs.workers`, `lease-seconds`, `heartbeat-ms` and `sweep-ms` are. Set it there or in the
  environment and restart; there is no in-app control for it.

---

# Development without Docker (native Windows)

Only needed if you want to edit and run the services outside containers. Prerequisites: a JDK 21, the
Node.js/npm used by `frontend/package.json`, a Python environment built from
`ai-service/requirements.txt` at `ai-service/.venv`, and a local PostgreSQL on `localhost:5432` with
database `truthlens` and user `truthlens`.

1. Copy `.env.example` to `.env` and set `SPRING_DATASOURCE_PASSWORD` and `JWT_SECRET` (at least 32
   characters). Do not commit `.env`.
2. Point `JAVA_HOME` at your JDK 21 before starting the script. If it is unset, `start-local.ps1` falls
   back to a developer-local `.tools\jdk21` path that is not in Git, so on a fresh machine set it
   explicitly.
3. From the repository root:

```powershell
powershell -File scripts\start-local.ps1
```

The script reads `.env` without printing values and defaults `TRUTHLENS_TEXT_CONFIDENCE_THRESHOLD=0.70`,
`TRUTHLENS_IMAGE_UNCERTAIN_MARGIN=0.05`, `TRUTHLENS_RELATED_PASSAGES=0` and `AI_SERVICE_URL` unless
already set (Uvicorn does not load `.env.example` by itself). It checks each target port first and will
not launch a duplicate onto an occupied port (it prints which port is in use); it writes timestamped logs
so a running instance's logs are not clobbered, and reports readiness by polling each service rather than
treating a launched PID as ready — it exits non-zero if any component is not verified up. Stop the
recorded stack with `powershell -File scripts\stop-local.ps1` before a clean restart; that script only
kills PIDs recorded in `.truthlens-pids.txt` and will not stop unrelated Java processes.
`powershell -File scripts\restart-backend.ps1` stops only the `backend=` PID recorded there (and its
children) and starts it again; the AI service and the dev server stay up.

Native admin bootstrap (no Docker):

```powershell
powershell -File scripts\bootstrap-admin.ps1 -Email 'you@example.com' -Name 'Admin' -Password (Read-Host -AsSecureString) -JwtSecret $env:JWT_SECRET
```

If port 8080 is already used by a previous TruthLens process, stop that process yourself or start with
`powershell -File scripts\start-local.ps1 -BackendPort 8090` and point the frontend at it
(`VITE_API_URL=http://localhost:8090/api/v1`). The frontend port is the one you should not change: CORS
allow-lists only the `http://localhost:5173` and `http://127.0.0.1:5173` origins (§5).

---

# How to read an assessment

- Every result is an automated assessment that requires human verification. It is not definitive proof.
- Article classification and evidence verification are separate stages with separate verdicts; a
  classification label does not imply that evidence was found, and a claim verdict does not reclassify
  the article.
- Claim verdicts are `SUPPORTED`, `REFUTED`, `INSUFFICIENT` or `UNCERTAIN`. `INSUFFICIENT` means the
  retrieved evidence did not allow a decision; a service or model failure is reported as
  `component_status: UNAVAILABLE` (and the article as `PARTIAL`) instead — the two are deliberately
  distinct and must not be read as the same thing.
- The optional related-passages block is candidate material for display. It does not determine the
  verdict.
- An image result is a frame-level assessment. The Grad-CAM heatmap shows the regions that influenced
  the model's decision, not a proven manipulation location.
