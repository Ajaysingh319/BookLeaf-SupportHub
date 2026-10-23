-- Baseline schema (TDD sections 4 and 4.2): users, books, tickets, messages and the AI call log.
-- Money columns are NUMERIC; enums are stored as text with CHECK constraints; keys are UUIDs.
-- Changes after this point go in new files (V2__..., V3__...); never edit this one.

CREATE TABLE app_users (
    id                uuid                     NOT NULL,
    source_author_id  varchar(255),
    name              varchar(255)             NOT NULL,
    email             varchar(255)             NOT NULL,
    password_hash     varchar(255)             NOT NULL,
    role              varchar(255)             NOT NULL,
    phone             varchar(255),
    city              varchar(255),
    joined_date       date,
    CONSTRAINT pk_app_users PRIMARY KEY (id),
    CONSTRAINT uq_app_users_email UNIQUE (email),
    CONSTRAINT uq_app_users_source_author_id UNIQUE (source_author_id),
    CONSTRAINT ck_app_users_role CHECK (role IN ('AUTHOR', 'ADMIN'))
);

CREATE TABLE books (
    id                        uuid           NOT NULL,
    source_book_id            varchar(255)   NOT NULL,
    author_id                 uuid           NOT NULL,
    title                     varchar(255)   NOT NULL,
    isbn                      varchar(255)   NOT NULL,
    genre                     varchar(255)   NOT NULL,
    publication_date          date,
    status                    varchar(255)   NOT NULL,
    mrp                       numeric(12, 2),
    author_royalty_per_copy   numeric(12, 2),
    total_copies_sold         integer,
    total_royalty_earned      numeric(14, 2),
    royalty_paid              numeric(14, 2),
    royalty_pending           numeric(14, 2),
    last_royalty_payout_date  date,
    print_partner             varchar(255),
    CONSTRAINT pk_books PRIMARY KEY (id),
    CONSTRAINT uq_books_source_book_id UNIQUE (source_book_id),
    CONSTRAINT fk_books_author FOREIGN KEY (author_id) REFERENCES app_users (id)
);

CREATE INDEX idx_books_author ON books (author_id);

CREATE TABLE book_available_on (
    book_id   uuid          NOT NULL,
    platform  varchar(255)  NOT NULL,
    CONSTRAINT fk_book_available_on_book FOREIGN KEY (book_id) REFERENCES books (id)
);

CREATE INDEX idx_book_available_on_book ON book_available_on (book_id);

CREATE TABLE tickets (
    id                 uuid                      NOT NULL,
    author_id          uuid                      NOT NULL,
    book_id            uuid,
    subject            varchar(160)              NOT NULL,
    description        varchar(10000)            NOT NULL,
    status             varchar(255)              NOT NULL,
    category           varchar(255)              NOT NULL,
    priority           varchar(255)              NOT NULL,
    ai_category        varchar(255),
    ai_priority        varchar(255),
    ai_outcome         varchar(255)              NOT NULL,
    ai_reason          varchar(255),
    assigned_admin_id  uuid,
    created_at         timestamp(6) with time zone NOT NULL,
    updated_at         timestamp(6) with time zone NOT NULL,
    resolved_at        timestamp(6) with time zone,
    replied_at         timestamp(6) with time zone,
    CONSTRAINT pk_tickets PRIMARY KEY (id),
    CONSTRAINT fk_tickets_author FOREIGN KEY (author_id) REFERENCES app_users (id),
    CONSTRAINT fk_tickets_book FOREIGN KEY (book_id) REFERENCES books (id),
    CONSTRAINT fk_tickets_assigned_admin FOREIGN KEY (assigned_admin_id) REFERENCES app_users (id),
    CONSTRAINT ck_tickets_status CHECK (status IN ('OPEN', 'IN_PROGRESS', 'RESOLVED', 'CLOSED')),
    CONSTRAINT ck_tickets_category CHECK (category IN (
        'ROYALTY_PAYMENTS', 'ISBN_METADATA', 'PRINTING_QUALITY',
        'DISTRIBUTION_AVAILABILITY', 'BOOK_STATUS_PRODUCTION', 'GENERAL_INQUIRY')),
    CONSTRAINT ck_tickets_priority CHECK (priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_tickets_ai_category CHECK (ai_category IS NULL OR ai_category IN (
        'ROYALTY_PAYMENTS', 'ISBN_METADATA', 'PRINTING_QUALITY',
        'DISTRIBUTION_AVAILABILITY', 'BOOK_STATUS_PRODUCTION', 'GENERAL_INQUIRY')),
    CONSTRAINT ck_tickets_ai_priority CHECK (ai_priority IS NULL OR ai_priority IN ('CRITICAL', 'HIGH', 'MEDIUM', 'LOW')),
    CONSTRAINT ck_tickets_ai_outcome CHECK (ai_outcome IN ('PENDING', 'SUCCESS', 'FALLBACK'))
);

-- Queue ordering and filters, "My Tickets", and the category and assignee filters (TDD 4.2).
CREATE INDEX idx_tickets_status_priority_created ON tickets (status, priority, created_at);
CREATE INDEX idx_tickets_author_created ON tickets (author_id, created_at);
CREATE INDEX idx_tickets_category ON tickets (category);
CREATE INDEX idx_tickets_assigned_admin ON tickets (assigned_admin_id);

-- Public replies and internal notes in one table; every author query filters on visibility = 'PUBLIC'.
CREATE TABLE ticket_messages (
    id          uuid                      NOT NULL,
    ticket_id   uuid                      NOT NULL,
    sender_id   uuid                      NOT NULL,
    body        varchar(10000)            NOT NULL,
    visibility  varchar(255)              NOT NULL,
    created_at  timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_ticket_messages PRIMARY KEY (id),
    CONSTRAINT fk_ticket_messages_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
    CONSTRAINT fk_ticket_messages_sender FOREIGN KEY (sender_id) REFERENCES app_users (id),
    CONSTRAINT ck_ticket_messages_visibility CHECK (visibility IN ('PUBLIC', 'INTERNAL'))
);

CREATE INDEX idx_ticket_messages_ticket_created ON ticket_messages (ticket_id, created_at);

-- One row per AI call: task, provider, model, outcome, latency and token counts.
-- No prompts or answers are stored. ticket_id has no foreign key so the log outlives a ticket.
CREATE TABLE ai_events (
    id              uuid                      NOT NULL,
    ticket_id       uuid                      NOT NULL,
    task_type       varchar(255)              NOT NULL,
    provider        varchar(255),
    model           varchar(255),
    outcome         varchar(255)              NOT NULL,
    failure_reason  varchar(255),
    latency_ms      bigint,
    input_tokens    integer,
    output_tokens   integer,
    created_at      timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_ai_events PRIMARY KEY (id),
    CONSTRAINT ck_ai_events_task_type CHECK (task_type IN ('TRIAGE', 'DRAFT')),
    CONSTRAINT ck_ai_events_outcome CHECK (outcome IN ('SUCCESS', 'FALLBACK', 'FAILED'))
);

CREATE INDEX idx_ai_events_ticket ON ai_events (ticket_id);
CREATE INDEX idx_ai_events_created ON ai_events (created_at);
