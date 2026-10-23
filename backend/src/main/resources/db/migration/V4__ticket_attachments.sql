-- Files an author attaches to a ticket (screenshots, PDFs). The bytes are stored with the metadata so a backup
-- of the database is a complete backup; the app lists them without reading content.
CREATE TABLE attachments (
    id            uuid                      NOT NULL,
    ticket_id     uuid                      NOT NULL,
    uploaded_by   uuid                      NOT NULL,
    file_name     varchar(100)              NOT NULL,
    content_type  varchar(100)              NOT NULL,
    size_bytes    bigint                    NOT NULL,
    content       bytea                     NOT NULL,
    created_at    timestamp(6) with time zone NOT NULL,
    CONSTRAINT pk_attachments PRIMARY KEY (id),
    CONSTRAINT fk_attachments_ticket FOREIGN KEY (ticket_id) REFERENCES tickets (id),
    CONSTRAINT fk_attachments_uploader FOREIGN KEY (uploaded_by) REFERENCES app_users (id)
);

CREATE INDEX idx_attachments_ticket_created ON attachments (ticket_id, created_at);
