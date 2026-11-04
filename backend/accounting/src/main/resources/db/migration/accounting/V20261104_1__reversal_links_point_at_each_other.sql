-- reversed_by and reversal_of must describe the same reversal. This guards new changes only; it does not check old rows.

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
        -- A posted row is immutable, so once this passes the link back cannot change. Only POSTED rows qualify:
        -- PostingService posts the reversal first, and a draft could still be deleted.
        IF NOT EXISTS (SELECT 1 FROM accounting.journal_entry r
                       WHERE r.tenant_id = OLD.tenant_id AND r.id = NEW.reversed_by AND r.id <> OLD.id
                         AND r.reversal_of = OLD.id AND r.status = 'POSTED') THEN
            RAISE EXCEPTION 'journal entry % can only be marked REVERSED by the posted entry that reverses it', OLD.id;
        END IF;
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'journal entry % is % and cannot be changed', OLD.id, OLD.status;
END $$;

CREATE FUNCTION accounting.guard_reversal_named_by_original() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF NEW.reversal_of IS NULL OR NEW.status = 'DRAFT' THEN
        RETURN NULL;
    END IF;
    IF NOT EXISTS (SELECT 1 FROM accounting.journal_entry o
                   WHERE o.tenant_id = NEW.tenant_id AND o.id = NEW.reversal_of AND o.reversed_by = NEW.id) THEN
        RAISE EXCEPTION 'journal entry % reverses entry %, which does not name it', NEW.id, NEW.reversal_of;
    END IF;
    RETURN NULL;
END $$;

-- Deferred: the reversal is posted before the original can name it, in the same transaction.
CREATE CONSTRAINT TRIGGER reversal_named_by_original
    AFTER INSERT OR UPDATE ON accounting.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_reversal_named_by_original();
