# BookLeaf SupportHub

An author support and communication portal for BookLeaf Publishing. Authors sign in, see their books and royalties, and raise support tickets. The operations team triages those tickets in an admin queue, with AI help to classify, prioritise and draft replies. A human always reviews and sends the reply.

- **Author portal:** My Books, New Ticket, My Tickets, Ticket Detail (updates automatically).
- **Admin portal:** ticket queue with filters, ticket detail, category/priority overrides, assignment, internal notes, AI-drafted replies.
- **AI:** classification, priority score and reply drafting from the BookLeaf knowledge base, with a rule-based fallback so the system works when the AI is down.

> **Status:** all functionality is built and tested locally. Not yet done: the live deployment, the `.env` for production, and live testing of the AI provider (see [Known limitations](#known-limitations-and-next-steps)).

## Live demo and credentials

| | |
|---|---|
| Live URL | _to be added after deployment_ |
| Admin login | email = the `SEED_ADMIN_EMAIL` value used when seeding; password = `SEED_ADMIN_PASSWORD` |
| Author logins | the 10 author emails in `backend/src/main/resources/seed/bookleaf_sample_data.json` (for example `priya.sharma@email.com`); password = `SEED_AUTHOR_PASSWORD` |

The sample data has no passwords, so demo passwords are chosen at seed time from environment variables and stored only as BCrypt hashes. Rotate them after evaluation.

## Tech stack and why

| Layer | Choice | Why |
|---|---|---|
| Frontend | React 19 + Vite + TypeScript, React Router | Small, fast, typed. Plain hooks and a typed API client; no state library is needed for this scope (TDD section 10). |
| Backend | Java 17, Spring Boot 4, Spring Data JPA (Hibernate) | Strong layering (controller, service, repository), built-in validation, mature security primitives. |
| Database | PostgreSQL | Relational integrity for authors, books, tickets and messages; `NUMERIC` for money. |
| Auth | BCrypt password hashes, signed JWT (HS256), checked in one filter | Stateless, simple, server-side role checks. |
| Real-time | Short polling (5 s on a ticket, 10 s on lists) | Meets "near real-time" without WebSocket infrastructure (TDD section 9). |
| AI | Any OpenAI-compatible chat API (Gemini planned) | Provider, model and URL are configuration, not code. See [AI integration](#ai-integration). |

## Architecture

```
Browser (React)  ──HTTPS/JSON──▶  Spring Boot API  ──JDBC──▶  PostgreSQL
                                      │
                                      ├─ RequestIdFilter → AuthFilter (JWT + role rules)
                                      ├─ Controllers → Services → Repositories
                                      ├─ GlobalExceptionHandler (one error shape)
                                      └─ AI layer ──HTTPS──▶ LLM provider (server-side only)
```

- **Layers:** `web` (controllers, error handling) → `service` → `repository` / `domain`; `ai` is a separate package; `security` holds the filters; `config` holds typed configuration.
- **Access control is enforced on the server.** `AuthFilter` is the single place that decides who may call what: `/api/v1/author/**` is authors only, `/api/v1/admin/**` is admins only. Services also scope every query to the caller (an author's ticket lookup includes the author id), so another author's ticket is simply "not found" (404). The client route guards are only a convenience.
- **Persist before AI.** A ticket is saved first. Triage then runs in the background after the transaction commits, so an AI outage can never block or roll back ticket creation.
- **Public replies and internal notes** are one `TicketMessage` table with a `visibility` flag. Every author query filters on `PUBLIC`, and the author DTOs have no field that could carry an internal note.
- **AI suggestion vs final value:** `aiCategory`/`aiPriority` keep what the model said; `category`/`priority` are the working values an admin can override.
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
cd backend && ./mvnw spring-boot:run          # API on SERVER_PORT
cd client && cp .env.example .env && npm install && npm run dev   # app on http://localhost:5173
```

Set `DEV_API_PROXY_TARGET` in `client/.env` to the backend address.

### 5. Tests

```bash
cd backend && ./mvnw test -Dtest='*Test'     # unit tests (parser, fallback rules, prompts, JWT, status rules)
cd client && npm test && npm run build       # unit tests, type-check and production build
```

The end-to-end smoke test starts the backend, then checks login, role separation, ownership, validation, the full admin workflow and the AI-unavailable fallback, and removes its test tickets:

```powershell
cd backend
powershell -ExecutionPolicy Bypass -File .\scripts\smoke-test.ps1
```

## Configuration

Environment-specific values and secrets are never in source code. They come from environment variables or `backend/.env`; real environment variables win. Tunables that are not environment-specific (page sizes, AI token limits, poll intervals) are in `backend/src/main/resources/application.properties`.

| Variable | Secret | Purpose |
|---|---|---|
| `SERVER_PORT` | | API port |
| `DB_URL`, `DB_USERNAME` | | PostgreSQL connection (JDBC URL, app user) |
| `DB_PASSWORD` | yes | App user's password |
| `JPA_DDL_AUTO` | | `update` for development, `validate` once migrations are used |
| `AUTH_SECRET` | yes | Token signing key, at least 32 characters |
| `TOKEN_TTL_MINUTES` | | Token lifetime |
| `CLIENT_ORIGIN` | | Exact frontend origin allowed by CORS |
| `LLM_API_KEY` | yes | Provider key. Optional: leave unset to run without AI |
| `LLM_PROVIDER_NAME`, `LLM_MODEL`, `LLM_BASE_URL` | | Provider label (for logs), model id, OpenAI-compatible base URL |
| `APP_BRAND_NAME`, `APP_CURRENCY_CODE`, `APP_LOCALE` | | Display settings sent to the client (ISO 4217 code, BCP 47 locale) |
| `SEED_ADMIN_EMAIL`, `SEED_ADMIN_NAME` | | Admin account created by the seed |
| `SEED_AUTHOR_PASSWORD`, `SEED_ADMIN_PASSWORD` | yes | Demo passwords, only needed when seeding |
| `DEV_API_PROXY_TARGET` (client) | | Dev server proxy target for `/api` |
| `VITE_API_BASE_URL` (client) | | Public API address for a deployed build. Never put secrets in `VITE_` variables |

The API key is never sent to the browser. `.env` files are git-ignored; only `.env.example` files are committed.

## AI integration

**Tasks** (TDD section 7):
1. **Classification:** one of the six categories (Royalty & Payments, ISBN & Metadata Issues, Printing & Quality, Distribution & Availability, Book Status & Production Updates, General Inquiry).
2. **Priority:** Critical / High / Medium / Low, with a one-sentence reason.
3. **Reply draft:** grounded in the knowledge base and the ticket, for an admin to edit and send. It is never sent automatically.

**Prompt strategy.** Triage and drafting are separate prompts:
- *Triage* is a short system prompt plus the ticket subject, description and book status. It asks for JSON with exactly `category`, `priority`, `reason`, at temperature 0, and includes a few policy facts that change urgency (for example the 45-day payout window, ISBN errors are high priority).
- *Drafting* uses the BookLeaf knowledge base (company, royalty policy, ISBN, printing, distribution, production stages, tone guidelines), response patterns condensed from the brief's sample table, and the **verified book record** (status, MRP, copies sold, royalty earned/paid/pending, last payout, print partner, platforms). The prompt tells the model to use only those figures, to say "the team needs to verify" when something is missing, never to invent payout dates or statuses, and to start with an acknowledgement and end with a clear next step and timeline. Missing fields are passed as "not available".
- The prompt text, knowledge base and fallback rules are plain files in `backend/src/main/resources/ai/`, editable without code changes. Policy numbers in them (payout days, minimum payout) are filled in from configuration, and the category list comes from the code's enum, so nothing drifts.

**Model choice.** The client speaks the OpenAI chat-completions format, so the provider is configuration: base URL, model and key. The plan is Google Gemini through its OpenAI-compatible endpoint, using a fast, low-cost model: classification and short drafting do not need a large model, and low latency and cost matter for a queue that processes many tickets. The TDD suggested gpt-4o-mini; the assignment allows any provider. *The Gemini endpoint has not been tested yet* (no key at the time of writing), so verify JSON-mode behaviour when you add the key. If the provider rejects JSON mode, the strict parser treats any malformed reply as a failure and the fallback applies.

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

Base path `/api/v1`. JSON in and out. Protected routes need `Authorization: Bearer <token>`. Every response carries an `X-Request-Id` header.

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
| 403 | `FORBIDDEN` (wrong role) |
| 404 | `TICKET_NOT_FOUND`, `NOT_FOUND` (also used for another author's ticket) |
| 405 | `METHOD_NOT_ALLOWED` |
| 503 | `AI_UNAVAILABLE` |
| 500 | `INTERNAL_ERROR` (safe message plus request id) |

### Public

| Method and path | Purpose |
|---|---|
| `GET /health` | Liveness and database check. Returns only `{"status":"UP"}` (503 `DOWN`). No details. |
| `GET /config` | Client settings: brand, locale, currency, poll intervals, page size, limits, policy figures, option labels, status transitions. No secrets. |
| `POST /auth/login` | Body `{email, password}` → `{token, user}`. Same message for unknown email and wrong password. |

### Any signed-in user

| Method and path | Purpose |
|---|---|
| `GET /auth/me` | Current user (`id`, `name`, `email`, `role`). |
| `POST /auth/logout` | `204`. Tokens are stateless; the client discards its token. |

### Author (role AUTHOR; only the caller's own data)

| Method and path | Purpose |
|---|---|
| `GET /author/books` | The author's books: title, ISBN, genre, publication date, status, MRP, copies sold, royalty earned/paid/pending, last payout, print partner, platforms. Null for fields that do not exist yet (books in production); zero is a real value. |
| `GET /author/tickets?page&size` | The author's tickets, newest first, `{data, pagination}`. Page size is capped. |
| `POST /author/tickets` | Body `{bookId?, subject, description}` → `201` with the ticket. `bookId` null means General / Account Level; a book that is not the author's gives `400` on `bookId`. Subject 3–160 characters, description 10–10,000. |
| `GET /author/tickets/{ticketId}` | One own ticket with **public** replies only. No AI, priority, category or assignee fields. |

### Admin (role ADMIN)

| Method and path | Purpose |
|---|---|
| `GET /admin/tickets` | Queue. Filters: `status`, `category`, `priority`, `from`, `to` (dates, `yyyy-MM-dd`, UTC, `to` inclusive); `page`, `size`. Order: unresolved first, then priority Critical→Low, then oldest first. Rows include author, book, assignee, age in hours and a manual-review flag. |
| `GET /admin/tickets/summary` | Counts by status, unresolved counts by priority, unassigned unresolved count. |
| `GET /admin/tickets/{ticketId}` | Ticket, author, full book record, AI info (outcome, suggestion, reason), public replies and internal notes. |
| `PATCH /admin/tickets/{ticketId}` | Change `status`, `category`, `priority`, `assignedAdminId` (only the fields sent). Status moves follow the table above; the assignee must be an admin. AI suggestions are left untouched. |
| `POST /admin/tickets/{ticketId}/notes` | Body `{body}` → `201`. Internal note, never visible to authors. |
| `POST /admin/tickets/{ticketId}/ai-draft` | Returns `{draft}` for the admin to edit. Nothing is saved or sent. `503 AI_UNAVAILABLE` if the AI cannot help. |
| `POST /admin/tickets/{ticketId}/replies` | Body `{body, status?}` → `201`. Public reply; the status changes only if `status` is sent. |

## Project structure

```
backend/
  src/main/java/com/bookleaf/support/
    web/ (controllers, error handling)   service/   repository/   domain/   dto/
    security/ (JWT, filters)   ai/ (prompts, client, parser, fallback, triage, draft)
    config/ (typed properties, CORS, async)   seed/ (idempotent importer)
  src/main/resources/  application.properties   ai/ (prompts, knowledge base, rules)   seed/ (dataset)
  src/test/java/       unit tests
  scripts/             setup-db.ps1, smoke-test.ps1
client/
  src/  features/ (auth, config, books, tickets, admin, home)   components/   lib/ (api, format, polling hook)
docs/WRITEUP.md        one-page write-up
```

## Known limitations and next steps

- **Not yet done:** live deployment and URL; production `.env`; live verification of the AI provider (prompt quality, JSON mode, latency and cost on real tickets). The AI paths are unit-tested and the fallback path is end-to-end tested, but no real model call has been made yet.
- **Auth:** the token is kept in `sessionStorage` (it ends with the tab) and is valid until it expires; there is no revocation, refresh or login rate limiting. Production should use HttpOnly cookies with CSRF protection, token rotation and rate limiting (TDD sections 5 and 17).
- **Schema:** Hibernate `ddl-auto=update` creates tables for development; production should use versioned migrations (Flyway).
- **Real time:** polling, not SSE/WebSockets.
- **Tickets:** authors cannot reply to a ticket after the first message, so reopen-on-reply does not exist (TDD section 8 allows documenting this). An admin cannot unassign a ticket. Resolved and closed tickets sort oldest-first like the rest.
- **Attachments:** UI placeholder only, as the brief allows.
- **API docs:** this README section is the documentation; there is no generated OpenAPI file.
- **Tests:** unit tests plus an end-to-end smoke script. Automated integration tests against a throwaway database (for example Testcontainers) and browser tests would be the next step.
- **AI quality:** add a labelled evaluation set for category accuracy, track the rate at which admins edit drafts, and move to retrieval only when the knowledge base grows beyond what fits in a prompt.

## Deviations from the design documents

- **Backend is Spring Boot, not Express/Prisma** (a project decision); the layering and API follow the TDD.
- **Extra endpoints:** `GET /config` (so the client has no hardcoded rules) and `GET /admin/tickets/summary` (PRD P1 summary counts).
- **Gemini** is the intended AI provider rather than the TDD's example gpt-4o-mini, through the same OpenAI-compatible client.
- **Token storage** in `sessionStorage` instead of cookies (see limitations).
