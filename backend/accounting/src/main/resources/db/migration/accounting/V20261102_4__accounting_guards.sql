-- Defence in depth behind the service checks: a violation here is a bug in the service, not a user error.
-- These run as the caller (app_runtime), so row-level security also applies to what they read.

CREATE FUNCTION accounting.guard_posted_entry_balances() RETURNS trigger LANGUAGE plpgsql AS $$
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
    WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.id;
    IF line_count < 2 OR debits <> credits OR debits = 0 THEN
        RAISE EXCEPTION 'posted journal entry % is not balanced: % lines, debit %, credit %',
            NEW.id, line_count, debits, credits;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER posted_entry_balances
    AFTER INSERT OR UPDATE ON accounting.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_posted_entry_balances();

CREATE FUNCTION accounting.guard_entry_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'DRAFT' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        IF NEW.status = 'REVERSED' THEN
            RAISE EXCEPTION 'journal entry % is DRAFT and cannot be changed to REVERSED', OLD.id;
        END IF;
        RETURN NEW;
    END IF;
    -- The single allowed change; version may move because JPA increments it on every update.
    IF TG_OP = 'UPDATE' AND OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
       AND OLD.reversed_by IS NULL AND NEW.reversed_by IS NOT NULL
       AND to_jsonb(NEW) - 'status' - 'reversed_by' - 'version'
           = to_jsonb(OLD) - 'status' - 'reversed_by' - 'version' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'journal entry % is % and cannot be changed', OLD.id, OLD.status;
END $$;

CREATE TRIGGER entry_immutable
    BEFORE UPDATE OR DELETE ON accounting.journal_entry
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_entry_immutable();

CREATE FUNCTION accounting.guard_line_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_status VARCHAR;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        SELECT status INTO parent_status FROM accounting.journal_entry
        WHERE tenant_id = OLD.tenant_id AND id = OLD.entry_id FOR SHARE;
        IF parent_status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION 'lines of journal entry % are % and cannot be changed', OLD.entry_id, parent_status;
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    SELECT status INTO parent_status FROM accounting.journal_entry
    WHERE tenant_id = NEW.tenant_id AND id = NEW.entry_id FOR SHARE;
    IF parent_status IN ('POSTED', 'REVERSED') THEN
        RAISE EXCEPTION 'lines of journal entry % are % and cannot be changed', NEW.entry_id, parent_status;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER line_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.journal_line
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_line_immutable();

CREATE FUNCTION accounting.guard_line_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    usable BOOLEAN;
BEGIN
    SELECT postable AND active INTO usable FROM accounting.account
    WHERE tenant_id = NEW.tenant_id AND id = NEW.account_id FOR SHARE;
    -- No such account in this tenant: the composite foreign key rejects it.
    IF FOUND AND NOT usable THEN
        RAISE EXCEPTION 'account % is not postable and active', NEW.account_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER line_account
    BEFORE INSERT OR UPDATE ON accounting.journal_line
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_line_account();
