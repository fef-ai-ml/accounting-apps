-- Ledger hardening before Phase 2. See docs/superpowers/specs/2026-11-03-ledger-hardening-design.md.

-- Columns

ALTER TABLE accounting.journal_entry
    ADD COLUMN deleted_at      TIMESTAMPTZ,
    ADD COLUMN deleted_by_sub  VARCHAR(100),
    ADD COLUMN deleted_by_name VARCHAR(200);

ALTER TABLE accounting.journal_line
    ADD COLUMN deleted_at      TIMESTAMPTZ,
    ADD COLUMN deleted_by_sub  VARCHAR(100),
    ADD COLUMN deleted_by_name VARCHAR(200);

-- Constraints and index

ALTER TABLE accounting.journal_entry
    ADD CHECK ((deleted_at IS NULL) = (deleted_by_sub IS NULL)),
    ADD CHECK ((deleted_at IS NULL) = (deleted_by_name IS NULL)),
    ADD CHECK (deleted_at IS NULL OR status = 'DRAFT');

ALTER TABLE accounting.journal_line
    ADD CHECK ((deleted_at IS NULL) = (deleted_by_sub IS NULL)),
    ADD CHECK ((deleted_at IS NULL) = (deleted_by_name IS NULL));

-- A saved draft reuses line numbers, so only live lines must be unique.
ALTER TABLE accounting.journal_line DROP CONSTRAINT journal_line_tenant_id_entry_id_line_no_key;
CREATE UNIQUE INDEX journal_line_live_no_idx ON accounting.journal_line (tenant_id, entry_id, line_no)
    WHERE deleted_at IS NULL;

-- Guards

CREATE OR REPLACE FUNCTION accounting.guard_posted_entry_balances() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    line_count BIGINT;
    debits     NUMERIC;
    credits    NUMERIC;
BEGIN
    IF NEW.status = 'DRAFT' THEN
        RETURN NULL;
    END IF;
    SELECT count(*), coalesce(sum(debit), 0), coalesce(sum(credit), 0)
    INTO line_count, debits, credits
    FROM accounting.journal_line
    WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.id AND deleted_at IS NULL;
    IF line_count < 2 OR debits <> credits OR debits = 0 THEN
        RAISE EXCEPTION 'posted journal entry % is not balanced: % lines, debit %, credit %',
            NEW.id, line_count, debits, credits;
    END IF;
    RETURN NULL;
END $$;

CREATE OR REPLACE FUNCTION accounting.guard_entry_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal entry % cannot be deleted; a draft is marked deleted instead', OLD.id;
    END IF;
    IF OLD.deleted_at IS NOT NULL THEN
        RAISE EXCEPTION 'journal entry % is deleted and cannot be changed', OLD.id;
    END IF;
    IF OLD.status = 'DRAFT' THEN
        IF NEW.status = 'REVERSED' THEN
            RAISE EXCEPTION 'journal entry % is DRAFT and cannot be changed to REVERSED', OLD.id;
        END IF;
        RETURN NEW;
    END IF;
    -- The single allowed change; version may move because JPA increments it on every update.
    IF OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
       AND OLD.reversed_by IS NULL AND NEW.reversed_by IS NOT NULL
       AND to_jsonb(NEW) - 'status' - 'reversed_by' - 'version'
           = to_jsonb(OLD) - 'status' - 'reversed_by' - 'version' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'journal entry % is % and cannot be changed', OLD.id, OLD.status;
END $$;

CREATE OR REPLACE FUNCTION accounting.guard_line_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_status  VARCHAR;
    parent_deleted TIMESTAMPTZ;
BEGIN
    IF TG_OP = 'DELETE' THEN
        RAISE EXCEPTION 'journal line % cannot be deleted; it is marked deleted instead', OLD.id;
    END IF;
    IF TG_OP = 'UPDATE' THEN
        IF OLD.deleted_at IS NOT NULL THEN
            RAISE EXCEPTION 'journal line % is deleted and cannot be changed', OLD.id;
        END IF;
        SELECT status INTO parent_status FROM accounting.journal_entry
        WHERE tenant_id = OLD.tenant_id AND id = OLD.entry_id FOR SHARE;
        IF parent_status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION 'lines of journal entry % are % and cannot be changed', OLD.entry_id, parent_status;
        END IF;
    END IF;
    SELECT status, deleted_at INTO parent_status, parent_deleted FROM accounting.journal_entry
    WHERE tenant_id = NEW.tenant_id AND id = NEW.entry_id FOR SHARE;
    IF parent_status IN ('POSTED', 'REVERSED') THEN
        RAISE EXCEPTION 'lines of journal entry % are % and cannot be changed', NEW.entry_id, parent_status;
    END IF;
    -- Deleting a draft marks the entry and its lines in one transaction, in whichever order Hibernate flushes them.
    IF parent_deleted IS NOT NULL AND NEW.deleted_at IS NULL THEN
        RAISE EXCEPTION 'journal entry % is deleted and cannot take lines', NEW.entry_id;
    END IF;
    RETURN NEW;
END $$;

CREATE OR REPLACE FUNCTION accounting.guard_line_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    usable BOOLEAN;
BEGIN
    -- A removed line uses no account any more: a draft on a since-deactivated account must stay editable.
    IF NEW.deleted_at IS NOT NULL THEN
        RETURN NEW;
    END IF;
    SELECT postable AND active INTO usable FROM accounting.account
    WHERE tenant_id = NEW.tenant_id AND id = NEW.account_id FOR SHARE;
    -- No such account in this tenant: the composite foreign key rejects it.
    IF FOUND AND NOT usable THEN
        RAISE EXCEPTION 'account % is not postable and active', NEW.account_id;
    END IF;
    RETURN NEW;
END $$;

CREATE FUNCTION accounting.guard_no_truncate() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    RAISE EXCEPTION '% cannot be truncated', TG_TABLE_NAME;
END $$;

CREATE TRIGGER entry_no_truncate BEFORE TRUNCATE ON accounting.journal_entry
    FOR EACH STATEMENT EXECUTE FUNCTION accounting.guard_no_truncate();

CREATE TRIGGER line_no_truncate BEFORE TRUNCATE ON accounting.journal_line
    FOR EACH STATEMENT EXECUTE FUNCTION accounting.guard_no_truncate();

-- Revoke

REVOKE DELETE ON accounting.journal_entry, accounting.journal_line FROM app_runtime;

-- Backfill
