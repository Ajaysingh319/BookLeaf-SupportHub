-- When the author last wrote on the ticket (a follow-up message). Together with replied_at it tells the
-- admin queue which tickets are waiting for a reply from the team.
ALTER TABLE tickets ADD COLUMN last_author_message_at timestamp(6) with time zone;
