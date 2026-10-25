# BookLeaf SupportHub
An author support and communication portal for BookLeaf Publishing. Authors sign in, see their books and royalties, and raise support tickets. The operations team triages those tickets in an admin queue, with AI help to classify, prioritise and draft replies. A human always reviews and sends the reply.

- **Author portal:** My Books, New Ticket, My Tickets, Ticket Detail (updates automatically).
- **Admin portal:** ticket queue with filters, ticket detail, category/priority overrides, assignment, internal notes, AI-drafted replies.
- **AI:** classification, priority score and reply drafting from the BookLeaf knowledge base, with a rule-based fallback so the system works when the AI is down.

> **Status:** all functionality is built, tested, and deployed (client on Vercel, API on Render, database on Neon). The AI has been verified against the real Gemini API. See [Deployment](#deployment) and [Known limitations](#known-limitations-and-next-steps).

## Live demo and credentials

| | |
|---|---|
| Live URL | https://book-leaf-support-ghna9d93y-techaj.vercel.app/ (the client; the API is reached through it at `/api`) |
| API health | https://bookleaf-supporthub.onrender.com/api/v1/health |
| Admin login | email = the `SEED_ADMIN_EMAIL` value used when seeding; password = `SEED_ADMIN_PASSWORD` |
| Author logins | the 10 author emails in `backend/src/main/resources/seed/bookleaf_sample_data.json` (for example `priya.sharma@email.com`); password = `SEED_AUTHOR_PASSWORD` |

The sample data has no passwords, so demo passwords are chosen at seed time from environment variables and stored only as BCrypt hashes. Rotate them after evaluation.

> **Note on the first request:** the API runs on a free instance that sleeps when idle, so the first request after a pause can take up to a minute. Open the live URL once before a demo.

## Tech stack and why

| Layer | Choice | Why |
|---|---|---|
| Frontend | React 19 + Vite + TypeScript, React Router | Small, fast, typed. Plain hooks and a typed API client; no state library is needed for this scope (TDD section 10). |
| Backend | Java 17, Spring Boot 4, Spring Data JPA (Hibernate) | Strong layering (controller, service, repository), built-in validation, mature security primitives. |
| Database | PostgreSQL | Relational integrity for authors, books, tickets and messages; `NUMERIC` for money. |
| Auth | BCrypt password hashes; a signed session token in an HttpOnly cookie, CSRF token on writes, sign-in throttling; all checked in one filter | The token is invisible to page scripts, expires when idle, can be revoked at sign-out, and cross-site requests cannot ride on it. |
| Real-time | Short polling (5 s on a ticket, 10 s on lists) | Meets "near real-time" without WebSocket infrastructure (TDD section 9). |
| Hosting | Vercel (client), Render (API in a Docker container), Neon (PostgreSQL) | All three have free tiers. The client proxies `/api` to the API so the session cookie is first-party. |
| AI | Any OpenAI-compatible chat API (Gemini planned) | Provider, model and URL are configuration, not code. See [AI integration](#ai-integration). |

## Architecture

```
Browser (React)  ──HTTPS/JSON──▶  Spring Boot API  ──JDBC──▶  PostgreSQL
                                      │
                                      ├─ RequestIdFilter → AuthFilter (session cookie + CSRF + role rules)
                                      ├─ Controllers → Services → Repositories
                                      ├─ GlobalExceptionHandler (one error shape)
                                      └─ AI layer ──HTTPS──▶ LLM provider (server-side only)
```

- **Layers:** `web` (controllers, error handling) → `service` → `repository` / `domain`; `ai` is a separate package; `security` holds the filters; `config` holds typed configuration.
- **Access control is enforced on the server.** `AuthFilter` is the single place that decides who may call what: `/api/v1/author/**` is authors only, `/api/v1/admin/**` is admins only. Services also scope every query to the caller (an author's ticket lookup includes the author id), so another author's ticket is simply "not found" (404). The client route guards are only a convenience.
- **Sessions.** Signing in sets an HttpOnly, `Path=/api` cookie holding a signed token (`Secure` and `SameSite` from configuration). The token is never in a response body or in browser storage. It expires after `TOKEN_TTL_MINUTES` of inactivity and is renewed (same session, same CSRF token) while the person keeps working, but never lives past `app.auth.session-max-minutes` (12 hours) from sign-in. Signing out revokes the session on the server, so a copied cookie stops working. Revocations and the sign-in throttle are held in memory: they reset on restart and are per instance, which is right for one instance and needs a shared store if the API is scaled out.
- **CSRF.** Reading is safe; every write (POST/PATCH/PUT/DELETE) must carry the session's CSRF token in an `X-CSRF-Token` header, which the app learns from `POST /auth/login` or `GET /auth/session` and keeps in memory only. If the request has an `Origin` header it must equal `CLIENT_ORIGIN`. Both failures give `403 CSRF_INVALID`.
- **Sign-in throttling.** 5 failed attempts for the same email from the same address lock that pair for 15 minutes (`429 TOO_MANY_ATTEMPTS` with `Retry-After`), and 50 failures from one address lock the address. Unknown emails are counted exactly like real ones and cost the same time, so neither the lock nor the timing shows which accounts exist. Limits are in `application.properties`.
- **Persist before AI.** A ticket is saved first. Triage then runs in the background after the transaction commits, so an AI outage can never block or roll back ticket creation.
- **Public replies and internal notes** are one `TicketMessage` table with a `visibility` flag. Every author query filters on `PUBLIC`, and the author DTOs have no field that could carry an internal note.
- **AI suggestion vs final value:** `aiCategory`/`aiPriority` keep what the model said; `category`/`priority` are the working values an admin can override.
- **Versioned migrations.** The schema is created by Flyway (`backend/src/main/resources/db/migration`, applied automatically at startup) and Hibernate only *validates* it (`JPA_DDL_AUTO=validate`), so entities and schema cannot drift apart silently. An existing database without migration history is adopted as version 1.
- **Admin choices are never overwritten by a late AI answer.** If an admin sets the category or priority while triage is still running, the AI result is stored only as the suggestion.
- **Attachments.** An author can attach up to 5 files (JPEG, PNG, WebP or PDF, 5 MB each; all configurable) to their own open ticket, in the new-ticket form or later on the ticket page. The type is decided from the file's first bytes, never from its name or the browser's Content-Type, and the stored name is cleaned of paths and odd characters with an extension that matches the detected type. Files live in PostgreSQL (`bytea`) next to their metadata, are listed without reading their content, and are served only to the ticket's author and to admins, always as `Content-Disposition: attachment` with `nosniff` and a sandboxing CSP. Adding a file does not by itself reopen a ticket or flag it as awaiting reply; the author's message does.
- **No hardcoded configuration.** See [Configuration](#configuration).
- **The client holds no copies of server rules.** It reads `GET /api/v1/config` at startup: limits, poll intervals, policy figures, locale/currency, category/priority/status labels and the allowed status transitions.

### Ticket lifecycle (TDD section 8)

| From | May move to |
|---|---|
| Open | In Progress, Resolved, Closed |
| In Progress | Open, Resolved, Closed |
| Resolved | Open, Closed |
| Closed | Open |

Moving to Resolved or Closed sets `resolvedAt`; reopening clears it. A reply never changes the status unless the admin picks one.

**Author follow-ups.** An author can reply on their own ticket until it is Closed (`409 TICKET_CLOSED`: they raise a new ticket). A reply on a Resolved ticket reopens it. The admin queue marks a ticket **awaiting reply** while it is unresolved and either has no team reply yet or the author wrote after the latest one; `awaiting=true` filters on that.

## Getting started locally

**Prerequisites:** JDK 17, Node.js 20+, PostgreSQL 14+. No Docker is needed.

### 1. Database

Create a dedicated application user and database (do not use the superuser in the app). On Windows you can use the helper, which prompts for passwords and stores the app password in the `DB_PASSWORD` user environment variable:

```powershell
cd backend
powershell -ExecutionPolicy Bypass -File .\scripts\setup-db.ps1
```

Or run it by hand as a PostgreSQL administrator:

```sql
CREATE ROLE <app_user> WITH LOGIN PASSWORD '<choose a password>';
CREATE DATABASE <database> OWNER <app_user>;
\connect <database>
GRANT ALL ON SCHEMA public TO <app_user>;
```

### 2. Configure the backend

Copy `backend/.env.example` to `backend/.env` (git-ignored) and fill in the non-secret values. Put secrets in real environment variables (`DB_PASSWORD`, `AUTH_SECRET`, `LLM_API_KEY`, `SEED_AUTHOR_PASSWORD`, `SEED_ADMIN_PASSWORD`). Missing required values stop startup with a message naming the property.

### 3. Seed the sample data

The import is idempotent (safe to run twice) and keeps the dataset's values exactly: nulls stay null for books in production, zeros stay zero. It creates the 10 authors, 18 books and one admin.

```bash
cd backend
./mvnw spring-boot:run -Dspring-boot.run.arguments="--app.seed.enabled=true --spring.main.web-application-type=none"
```

### 4. Run the backend and the client

```bash
cd backend && ./mvnw spring-boot:run          # API on PORT
cd client && cp .env.example .env && npm install && npm run dev   # app on http://localhost:5173
```

Set `DEV_API_PROXY_TARGET` in `client/.env` to the backend address.

### 5. Tests

```bash
cd backend && ./mvnw test                    # unit tests and API integration tests (no setup needed)
cd client && npm test && npm run build       # unit tests, type-check and production build
```

The backend tests need no PostgreSQL, `.env` or environment variables: the integration tests run the real application (filters, controllers, services, JPA) against an in-memory database seeded from the sample dataset, with a **fake AI provider** so every AI path (good answer, malformed answer, rate limit, timeout, slow answer) is tested without a key. They cover the TDD section 13 cases: authors see only their own data, authors cannot call admin endpoints, invalid book ids are rejected, tickets survive AI failures, admins can reply and add notes, internal notes never reach authors, password hashing and verification, status transitions, prompt contents, and AI cost and privacy limits.

The end-to-end smoke test is a separate check against a real PostgreSQL database. It starts the backend, then checks login, role separation, ownership, validation, the full admin workflow and the AI-unavailable fallback, and removes its test tickets:

```powershell
cd backend
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

## Configuration

Environment-specific values and secrets are never in source code. They come from environment variables or `backend/.env`; real environment variables win. Tunables that are not environment-specific (page sizes, AI token limits, poll intervals) are in `backend/src/main/resources/application.properties`.

| Variable | Secret | Purpose |
|---|---|---|
| `PORT` | | API port (hosts usually provide it) |
| `DB_URL`, `DB_USERNAME` | | PostgreSQL connection (JDBC URL, app user) |
| `DB_PASSWORD` | yes | App user's password |
| `JPA_DDL_AUTO` | | `update` for development, `validate` once migrations are used |
| `AUTH_SECRET` | yes | Token signing key, at least 32 characters |
| `TOKEN_TTL_MINUTES` | | Minutes of inactivity before a session ends (it renews while in use) |
| `COOKIE_SECURE` | | `true` on HTTPS (production); `false` only for local HTTP |
| `COOKIE_SAMESITE` | | `Lax` (default choice), `Strict`, or `None` (needs `COOKIE_SECURE=true`) |
| `CLIENT_ORIGIN` | | Exact frontend origin allowed by CORS and accepted in the `Origin` check |
| `LLM_API_KEY` | yes | Provider key. Optional: leave unset to run without AI |
| `LLM_PROVIDER_NAME`, `LLM_MODEL`, `LLM_BASE_URL` | | Provider label (for logs), model id, OpenAI-compatible base URL |
| `LLM_REASONING_EFFORT` | | Optional. Needed for "thinking" models (Gemini 3.x): `none`, `minimal` or `low`. Leave unset for models that do not support it |
| `APP_BRAND_NAME`, `APP_CURRENCY_CODE`, `APP_LOCALE` | | Display settings sent to the client (ISO 4217 code, BCP 47 locale) |
| `SEED_ADMIN_EMAIL`, `SEED_ADMIN_NAME` | | Admin account created by the seed |
| `SEED_AUTHOR_PASSWORD`, `SEED_ADMIN_PASSWORD` | yes | Demo passwords, only needed when seeding |
| `DEV_API_PROXY_TARGET` (client) | | Dev server proxy target for `/api` |
| `VITE_API_BASE_URL` (client) | | Public API address for a deployed build. Never put secrets in `VITE_` variables |

Other tunables live in `application.properties`: the absolute session limit and throttle limits (`app.auth.*`), and attachment limits and allowed types (`app.attachments.*`, with the matching multipart size limits).

**Cookie sessions need a same-site deployment.** The app and the API must be served from the same site (for example the host proxies `/api` to the API, or one service serves both), otherwise browsers treat the cookie as third-party and may drop it. Locally the Vite dev server does this with its proxy.

The API key is never sent to the browser. `.env` files are git-ignored; only `.env.example` files are committed.

## Deployment

```
Browser ──▶ Vercel (React build)
              │  /api/*  rewritten (vercel.json)
              ▼
           Render (Spring Boot in Docker)  ──JDBC/SSL──▶  Neon (PostgreSQL)
                          │
                          └──▶ Gemini API (key only on Render)
```

**Why a proxy:** the login is an HttpOnly cookie, which only works when the page and the API are on the same site. The browser only ever talks to the Vercel address; `client/vercel.json` forwards `/api/*` to Render and sends every other path to `index.html` so page refreshes work. `VITE_API_BASE_URL` stays empty in production.

**1. Database (Neon).** Create a project and note the host, database, user and password. The backend's `DB_URL` is the JDBC form: `jdbc:postgresql://<host>/<database>?sslmode=require`. Flyway creates the schema on first start.

**2. API (Render).** New Web Service from this repository, language **Docker**, Dockerfile path `backend/Dockerfile`, root directory left empty (the Dockerfile copies `backend/` itself). Render supplies `PORT`. Set these environment variables (secrets as secret values):

| Variable | Value |
|---|---|
| `DB_URL`, `DB_USERNAME`, `DB_PASSWORD` | The Neon connection details |
| `JPA_DDL_AUTO` | `validate` |
| `AUTH_SECRET` | A new random string of at least 32 characters |
| `TOKEN_TTL_MINUTES` | `60` |
| `COOKIE_SECURE`, `COOKIE_SAMESITE` | `true`, `Lax` |
| `CLIENT_ORIGIN` | The exact Vercel address people use, with no trailing slash |
| `LLM_API_KEY`, `LLM_PROVIDER_NAME`, `LLM_MODEL`, `LLM_REASONING_EFFORT`, `LLM_BASE_URL` | As in [Configuration](#configuration) |
| `APP_BRAND_NAME`, `APP_CURRENCY_CODE`, `APP_LOCALE` | Display settings |
| `SEED_ENABLED`, `SEED_ADMIN_EMAIL`, `SEED_ADMIN_NAME`, `SEED_ADMIN_PASSWORD`, `SEED_AUTHOR_PASSWORD` | See below |

**3. Seed the sample data.** Set `SEED_ENABLED=true` for the first start. The import is idempotent (it adds by source id and never duplicates), creates the 10 authors, 18 books and the admin, and hashes the passwords. Passwords are only set when an account is first created, so changing `SEED_*_PASSWORD` later does not change existing accounts. Set `SEED_ENABLED=false` afterwards.

**4. Client (Vercel).** Import the repository, root directory `client`, preset Vite, no environment variables. Then set `CLIENT_ORIGIN` on Render to the Vercel address and redeploy the API: the backend rejects writes whose `Origin` differs from it.

**5. Check.** `/api/v1/health` returns `{"status":"UP"}`; `/api/v1/config` returns the brand and options; sign in as both roles on the Vercel address.

Free-tier notes: Render sleeps idle instances (first request is slow) and Neon pauses an idle database (a second of delay). Gemini's free tier allows only about 20 requests a day per model; enable billing for an evaluation. Sessions and the sign-in throttle are held in memory, so they reset when the API restarts.

## AI integration

**Tasks** (TDD section 7):
1. **Classification:** one of the six categories (Royalty & Payments, ISBN & Metadata Issues, Printing & Quality, Distribution & Availability, Book Status & Production Updates, General Inquiry).
2. **Priority:** Critical / High / Medium / Low, with a one-sentence reason.
3. **Reply draft:** grounded in the knowledge base and the ticket, for an admin to edit and send. It is never sent automatically.

**Prompt strategy.** Triage and drafting are separate prompts:
- *Triage* is a short system prompt plus the ticket subject, description and book status. It asks for JSON with exactly `category`, `priority`, `reason`, at temperature 0, and includes a few policy facts that change urgency (for example the 45-day payout window, ISBN errors are high priority).
- *Drafting* uses the BookLeaf knowledge base (company, royalty policy, ISBN, printing, distribution, production stages, tone guidelines), response patterns condensed from the brief's sample table, and the **verified book record** (status, MRP, copies sold, royalty earned/paid/pending, last payout, print partner, platforms). The prompt tells the model to use only those figures, to say "the team needs to verify" when something is missing, never to invent payout dates or statuses, and to start with an acknowledgement and end with a clear next step and timeline. Missing fields are passed as "not available".
- The prompt text, knowledge base and fallback rules are plain files in `backend/src/main/resources/ai/`, editable without code changes. Policy numbers in them (payout days, minimum payout) are filled in from configuration, and the category list comes from the code's enum, so nothing drifts.

**Model choice.** The client speaks the OpenAI chat-completions format, so the provider is configuration: base URL, model and key. It runs on Google Gemini through its OpenAI-compatible endpoint (`https://generativelanguage.googleapis.com/v1beta/openai`) with `gemini-3.5-flash`: classification and short drafting do not need a large model, and low latency and cost matter for a queue that processes many tickets. The TDD suggested gpt-4o-mini; the assignment allows any provider.

**Verified against the real API** (live runs on the brief's sample queries):
- Triage: all 5 live classifications matched the brief's expected category and priority, in about 3 s with about 330 input and 30 output tokens. A ticket with "asked three times, urgent" came back Critical.
- Drafts: about 2.5 s, about 1,600 input and 215 output tokens. Reviewing them led to prompt rules against invented departments, the wrong reply channel, guessed dates and quarters, and wrong production-stage order, and to showing amounts with their currency and digit grouping.
- Findings that shaped the client: Gemini 3.x are "thinking" models whose hidden reasoning tokens count against the output limit and truncate the answer, so `LLM_REASONING_EFFORT=none` is set and the token ceilings are generous (they are caps, not costs). Google answers transient `429`/`503`, so one quick retry is configured (`app.ai.max-attempts`). Some model ids retire or stall (`gemini-2.5-flash` returned 404; `gemini-3.8-flash` did not answer), so check the model list for your key before choosing.
- **Quota:** a free Google key allows only about 20 generate requests per day per model. A demo or evaluation needs a key with billing enabled, or a different model per day; when the quota is exhausted the system keeps working through the fallback.

**Output validation.** The triage reply must be a JSON object with exactly the three fields, a category and priority that are exactly one of the allowed values, and a short reason. Anything else (extra fields, unknown values, prose, empty) counts as a failed AI call. A markdown code fence around the JSON is tolerated.

**Graceful degradation.**
- Tickets are saved before any AI call. Triage runs on a background thread after commit.
- If the AI is unconfigured, times out, is rate-limited, errors, or returns unusable output, the **rule-based fallback** assigns category and priority (PRD section 7 rules, kept in `ai/fallback-triage.properties`) and the ticket is flagged *"AI unavailable; manual review needed"* in the queue.
- If drafting fails, the API returns `503 AI_UNAVAILABLE` ("You can write the reply manually.") and the reply box stays usable.
- Provider error text is never forwarded to clients; only a short reason code is logged.

**Cost awareness.**
- Triage is called **once per ticket** and is a small prompt: no knowledge base, truncated description, 150 max output tokens.
- Drafting is called **only when an admin asks**, and sends only the current ticket, the one book's record, and at most the last three public replies (trimmed). It never sends other tickets, other authors, or contact details.
- Per-task temperature, token limits and truncation lengths are configuration.
- Every AI call is recorded in `ai_events` (task, provider, model, outcome, latency, input/output tokens, failure code) with no prompts or answers stored, so cost and failure rates can be reported.

**Security.** The key is read from the `LLM_API_KEY` environment variable on the server only. The client bundle contains no provider details.

## API reference

Base path `/api/v1`. JSON in and out (file upload is `multipart/form-data`). Protected routes need the session cookie; writes also need the `X-CSRF-Token` header. Every response carries an `X-Request-Id` header.

**Error shape** (never a stack trace):

```json
{ "error": { "code": "VALIDATION_ERROR", "message": "Please correct the highlighted fields.",
             "details": [ { "field": "subject", "message": "Subject must be between 3 and 160 characters." } ] },
  "requestId": "…" }
```

| Status | Codes |
|---|---|
| 400 | `VALIDATION_ERROR` (field-level `details`), `INVALID_REQUEST_BODY` |
| 401 | `UNAUTHENTICATED`, `INVALID_CREDENTIALS` |
| 403 | `FORBIDDEN` (wrong role), `CSRF_INVALID` |
| 404 | `TICKET_NOT_FOUND`, `ATTACHMENT_NOT_FOUND`, `NOT_FOUND` (also used for another author's ticket) |
| 405 | `METHOD_NOT_ALLOWED` |
| 409 | `TICKET_CLOSED`, `ATTACHMENT_LIMIT` |
| 413 | `FILE_TOO_LARGE` |
| 415 | `UNSUPPORTED_FILE_TYPE` |
| 429 | `TOO_MANY_ATTEMPTS` (with `Retry-After` seconds) |
| 503 | `AI_UNAVAILABLE` |
| 500 | `INTERNAL_ERROR` (safe message plus request id) |

### Public

| Method and path | Purpose |
|---|---|
| `GET /health` | Liveness and database check. Returns only `{"status":"UP"}` (503 `DOWN`). No details. |
| `GET /config` | Client settings: brand, locale, currency, poll intervals, page size, limits, policy figures, option labels, status transitions. No secrets. |
| `POST /auth/login` | Body `{email, password}` → `{user, csrfToken}` and the session cookie. Same message for unknown email and wrong password; throttled (see above). |
| `GET /auth/session` | Always `200`: `{user, csrfToken}` for a valid session, nulls otherwise. The app calls it at startup. |
| `POST /auth/logout` | `204`. Revokes the session if there is one and deletes the cookie. Works even when the session has expired. |

### Any signed-in user

| Method and path | Purpose |
|---|---|
| `GET /auth/me` | Current user (`id`, `name`, `email`, `role`). |

### Author (role AUTHOR; only the caller's own data)

| Method and path | Purpose |
|---|---|
| `GET /author/books` | The author's books: title, ISBN, genre, publication date, status, MRP, copies sold, royalty earned/paid/pending, last payout, print partner, platforms. Null for fields that do not exist yet (books in production); zero is a real value. |
| `GET /author/tickets?page&size` | The author's tickets, newest first, `{data, pagination}`. Page size is capped. |
| `POST /author/tickets` | Body `{bookId?, subject, description}` → `201` with the ticket. `bookId` null means General / Account Level; a book that is not the author's gives `400` on `bookId`. Subject 3–160 characters, description 10–10,000. |
| `GET /author/tickets/{ticketId}` | One own ticket with **public** replies and its attachments. No AI, priority, category or assignee fields. |
| `POST /author/tickets/{ticketId}/replies` | Body `{body}` → `201` with the ticket. Reopens a Resolved ticket; `409` on a Closed one. |
| `POST /author/tickets/{ticketId}/attachments` | `multipart/form-data`, one part named `file` → `201` with `{id, fileName, contentType, sizeBytes, createdAt}`. `415` if the content is not an allowed type (whatever the name says), `413` over the size limit, `409` over the per-ticket limit or on a Closed ticket. |
| `GET /author/tickets/{ticketId}/attachments/{attachmentId}` | The file, as a download. Another author's file is `404`. |

### Admin (role ADMIN)

| Method and path | Purpose |
|---|---|
| `GET /admin/tickets` | Queue. Filters: `status`, `category`, `priority`, `from`, `to` (dates, `yyyy-MM-dd`, UTC, `to` inclusive), `search` (subject, description, author name or email, book title; max 100 characters), `assignedTo` (an admin id, or `unassigned`), `awaiting=true` (waiting for a team reply); `page`, `size`. Order: unresolved first, then priority Critical→Low, then oldest first. Rows include author, book, assignee, age in hours and a manual-review flag. |
| `GET /admin/admins` | The admins a ticket can be assigned to. |
| `GET /admin/tickets/summary` | Counts by status, unresolved counts by priority, unassigned unresolved count. |
| `GET /admin/tickets/{ticketId}` | Ticket, author, full book record, AI info (outcome, suggestion, reason), when the last reply was sent, public replies and internal notes. |
| `GET /admin/tickets/{ticketId}/attachments/{attachmentId}` | The file, as a download. |
| `PATCH /admin/tickets/{ticketId}` | Change `status`, `category`, `priority`, `assignedAdminId`, or `unassign: true` (only the fields sent). Status moves follow the table above; the assignee must be an admin. AI suggestions are left untouched. |
| `POST /admin/tickets/{ticketId}/notes` | Body `{body}` → `201`. Internal note, never visible to authors. |
| `POST /admin/tickets/{ticketId}/ai-draft` | Returns `{draft}` for the admin to edit. Nothing is saved or sent. `503 AI_UNAVAILABLE` if the AI cannot help. |
| `POST /admin/tickets/{ticketId}/replies` | Body `{body, status?}` → `201`. Public reply; the status changes only if `status` is sent. |

## Project structure

```
backend/
  src/main/java/com/bookleaf/support/
    web/ (controllers, error handling)   service/   repository/   domain/   dto/
    security/ (sessions, cookie, CSRF, throttle, filters)   ai/ (prompts, client, parser, fallback, triage, draft)
    config/ (typed properties, CORS, async)   seed/ (idempotent importer)
  src/main/resources/  application.properties   ai/ (prompts, knowledge base, rules)   seed/ (dataset)
  src/test/java/       unit tests
  scripts/             setup-db.ps1, smoke-test.ps1
client/
  src/  features/ (auth, config, books, tickets, admin, home)   components/   lib/ (api, format, polling hook)
docs/WRITEUP.md        one-page write-up
```

## Known limitations and next steps

- **Deployment:** runs on free tiers (see [Deployment](#deployment)): the API sleeps when idle, there is one instance, and no backups or monitoring beyond the host's defaults. The AI was exercised live on the brief's sample queries (see above), but classification accuracy has only been measured on 5 live samples and draft quality reviewed by reading about 13 drafts; a larger labelled set is the next step.
- **Sessions:** revocations and the sign-in throttle are in memory (they reset on restart and are not shared between instances); a multi-instance deployment needs a shared store such as Redis or a database table. The client address used for throttling is the connection's address, so behind a proxy the proxy's forwarded-address handling must be configured. There is no refresh-token rotation, MFA, or password reset flow.
- **Real time:** polling, not SSE/WebSockets.
- **Tickets:** there is no assignment history or audit log of admin actions. Resolved and closed tickets sort oldest-first like the rest.
- **Attachments:** stored in PostgreSQL, which is simple and keeps backups complete but will not suit large volumes; object storage with signed URLs is the next step. There is no malware scan, image re-encoding or preview; files are only offered for download. Admins cannot upload, and removing an attachment is not supported.
- **API docs:** this README section is the documentation; there is no generated OpenAPI file.
- **Tests:** the backend has unit and API integration tests (on an in-memory H2 database, so SQL that is specific to PostgreSQL is covered by the smoke script and startup validation, not by the unit suite) and the client has unit tests. There are no automated browser tests; running the same suite against PostgreSQL (for example with Testcontainers) would be the next step.
- **AI quality:** add a labelled evaluation set for category accuracy, track the rate at which admins edit drafts, and move to retrieval only when the knowledge base grows beyond what fits in a prompt.

## Deviations from the design documents

- **Backend is Spring Boot, not Express/Prisma** (a project decision); the layering and API follow the TDD.
- **Extra endpoints:** `GET /config` (so the client has no hardcoded rules) and `GET /admin/tickets/summary` (PRD P1 summary counts).
- **Gemini** is the intended AI provider rather than the TDD's example gpt-4o-mini, through the same OpenAI-compatible client.
- **Extras beyond the documents:** author follow-up replies with reopen-on-reply, queue search, unassign, an "awaiting reply" filter, and real file attachments (the brief allowed a placeholder).
- **Cookie sessions require a same-site deployment** (see Configuration); the TDD's token-in-header option was not used.
