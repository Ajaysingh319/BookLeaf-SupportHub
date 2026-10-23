-- An admin's manual category/priority choice must never be overwritten by an AI result that
-- arrives later. This flag is set when an admin changes either field.
ALTER TABLE tickets ADD COLUMN triage_overridden boolean NOT NULL DEFAULT false;
