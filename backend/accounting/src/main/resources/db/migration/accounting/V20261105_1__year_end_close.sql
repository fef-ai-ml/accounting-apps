-- Year-end close. See docs/superpowers/specs/2026-11-04-accounting-phase-2-design.md.
-- These rules guard new changes only; they do not examine old rows.

ALTER TABLE accounting.journal_entry
    ADD COLUMN closing_year INT,
    ADD CONSTRAINT journal_entry_closing_date_check
        CHECK (closing_year IS NULL OR entry_date = make_date(closing_year, 12, 31));

CREATE UNIQUE INDEX journal_entry_active_close_idx ON accounting.journal_entry (tenant_id, closing_year)
    WHERE closing_year IS NOT NULL AND status = 'POSTED' AND reversal_of IS NULL;

ALTER TABLE accounting.audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE accounting.audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('SETUP', 'POST', 'REVERSE', 'PERIOD_CLOSE', 'PERIOD_REOPEN', 'SETTING_CHANGE', 'ACCOUNT_CHANGE',
                      'YEAR_CLOSE', 'YEAR_REOPEN'));

CREATE FUNCTION accounting.guard_closing_entry() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    now_status     VARCHAR;
    closing        INT;
    reverses       BIGINT;
    target_closing INT;
BEGIN
    -- A deferred trigger's NEW is the row at the event, so read the row as it stands at commit.
    SELECT status, closing_year, reversal_of INTO now_status, closing, reverses
    FROM accounting.journal_entry WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
    IF reverses IS NOT NULL THEN
        SELECT closing_year INTO target_closing FROM accounting.journal_entry
        WHERE tenant_id = NEW.tenant_id AND id = reverses;
        IF closing IS DISTINCT FROM target_closing THEN
            RAISE EXCEPTION 'journal entry % reverses entry %, which has a different closing year', NEW.id, reverses;
        END IF;
    END IF;
    IF closing IS NULL THEN
        RETURN NULL;
    END IF;
    IF now_status = 'DRAFT' THEN
        RAISE EXCEPTION 'closing entry % cannot stay a draft', NEW.id;
    END IF;
    IF now_status = 'POSTED' AND reverses IS NULL
       AND (SELECT count(*) FROM accounting.fiscal_period
            WHERE tenant_id = NEW.tenant_id AND year = closing AND status = 'CLOSED') <> 12 THEN
        RAISE EXCEPTION 'closing entry % needs all 12 months of % closed', NEW.id, closing;
    END IF;
    RETURN NULL;
END $$;

-- Deferred: each rule is briefly false inside a legitimate close or reopen.
CREATE CONSTRAINT TRIGGER closing_entry
    AFTER INSERT OR UPDATE ON accounting.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_closing_entry();

CREATE FUNCTION accounting.guard_period_of_closed_year() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    now_status VARCHAR;
BEGIN
    SELECT status INTO now_status FROM accounting.fiscal_period WHERE tenant_id = NEW.tenant_id AND id = NEW.id;
    IF now_status = 'OPEN' AND EXISTS (SELECT 1 FROM accounting.journal_entry e
                                       WHERE e.tenant_id = NEW.tenant_id AND e.closing_year = NEW.year
                                         AND e.status = 'POSTED' AND e.reversal_of IS NULL) THEN
        RAISE EXCEPTION 'month % of % cannot be open: the year has an active close', NEW.month, NEW.year;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER period_of_closed_year
    AFTER INSERT OR UPDATE ON accounting.fiscal_period
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_period_of_closed_year();
