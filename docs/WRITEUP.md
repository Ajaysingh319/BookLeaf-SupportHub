# BookLeaf SupportHub: approach write-up

**What I built.** A two-sided portal for author support. Authors see only their own books, royalties and tickets, and raise tickets that update on screen as the team replies. The operations team works a prioritised queue, with AI to classify and score each ticket and to draft a reply that a person edits and sends. A Spring Boot API, a React client and PostgreSQL, built to the assignment, PRD and TDD.

**What I prioritised.**
1. **The complete ticket lifecycle, with security first.** Role checks sit in one server-side filter, and every query is scoped to the caller, so changing an ID in a URL returns nothing. Internal notes cannot reach an author because the author queries and response types have no way to carry them.
2. **AI as an assistant, not a dependency.** The AI is the heaviest-weighted criterion, so the design centres on what happens when it is wrong or absent. A ticket is saved before any AI call. Model output is validated strictly (exact fields, exact allowed values). If the AI is down, rate-limited, or returns junk, a rule-based fallback sets category and priority and the queue flags "manual review needed"; if drafting fails, the reply box still works. The model never sends anything.
3. **Grounded, cost-aware prompts.** Triage is a tiny JSON-only call, once per ticket. Drafting runs only when asked and sends just the ticket, one book's verified record and a few recent replies, plus a compact knowledge base. The prompt forbids invented dates or figures and marks missing data "not available". Every call is logged with tokens and latency.
4. **No hardcoded configuration.** Connection details, limits, intervals, model and policy figures come from validated configuration; startup fails with a clear message if something is missing. Prompts, the knowledge base and the fallback rules are editable files. The client reads limits, labels and allowed status changes from the server, so the two cannot disagree.

**Trade-offs.**
- Short polling instead of WebSockets: simpler to build and deploy, a few seconds of latency.
- Rule-based fallback keywords are blunt; they are a safety net, not a classifier.
- Cookie sessions are safer than a script-readable token, but they need CSRF protection and a same-site deployment (the host must proxy `/api`, or serve both from one place). I accepted that cost. Session revocation and the sign-in throttle are held in memory, which suits one instance and would need a shared store to scale out.
- Attachments are stored in PostgreSQL, checked by their real content type and size and always served as downloads: simple and safe for the volumes of a support desk, not for large media.
- No RAG: the knowledge base is small and fixed, so putting it in the prompt is cheaper and easier to debug.
- Tests run on an in-memory database for speed and portability; PostgreSQL-specific behaviour is covered by startup validation of the Flyway migrations and an end-to-end smoke script.
- Verified so far: over 230 automated tests (212 backend, 21 client) (including API integration tests with a fake AI that covers good, malformed, failing and slow answers), an end-to-end smoke test and manual browser checks. Live calls to the real AI provider are the main thing not yet exercised.

**Evolving to production.**
- A shared store for session revocation and login throttling, refresh-token rotation and MFA for admins, an audit log of admin actions.
- Object storage and malware scanning for attachments; email notifications; SSE for live updates at scale.
- An evaluation set for classification accuracy and draft quality; track draft edit rate, latency and cost per ticket from the AI event log; add retrieval and policy versioning with citations when the knowledge base grows.
- Observability (structured logs with the request id, metrics, alerts), backups, load tests, and automated integration and browser tests in CI.
