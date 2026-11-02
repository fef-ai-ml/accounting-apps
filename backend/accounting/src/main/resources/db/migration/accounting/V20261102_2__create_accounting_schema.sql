CREATE SCHEMA IF NOT EXISTS accounting;

-- Global and read-only for the API: the starting chart of accounts every tenant copies at setup.
CREATE TABLE accounting.coa_template (
    code        VARCHAR(20)  PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    parent_code VARCHAR(20),
    postable    BOOLEAN      NOT NULL
);

CREATE TABLE accounting.settings (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               UUID         NOT NULL UNIQUE,
    require_separate_poster BOOLEAN      NOT NULL DEFAULT TRUE,
    initialized_at          TIMESTAMPTZ,
    updated_by_sub          VARCHAR(100),
    updated_by_name         VARCHAR(200),
    updated_at              TIMESTAMPTZ
);

CREATE TABLE accounting.account (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID         NOT NULL,
    code      VARCHAR(20)  NOT NULL,
    name      VARCHAR(200) NOT NULL,
    type      VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    parent_id BIGINT,
    postable  BOOLEAN      NOT NULL,
    active    BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, code),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES accounting.account (tenant_id, id)
);

CREATE TABLE accounting.fiscal_period (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    year           INT         NOT NULL,
    month          INT         NOT NULL CHECK (month BETWEEN 1 AND 12),
    start_date     DATE        NOT NULL,
    end_date       DATE        NOT NULL,
    status         VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    closed_by_sub  VARCHAR(100),
    closed_by_name VARCHAR(200),
    closed_at      TIMESTAMPTZ,
    UNIQUE (tenant_id, year, month),
    UNIQUE (tenant_id, id)
);

CREATE TABLE accounting.entry_sequence (
    tenant_id  UUID   NOT NULL,
    year       INT    NOT NULL,
    next_value BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, year)
);

CREATE TABLE accounting.journal_entry (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    entry_no              VARCHAR(20),
    entry_date            DATE         NOT NULL,
    period_id             BIGINT       NOT NULL,
    reference             VARCHAR(100),
    description           VARCHAR(500) NOT NULL,
    status                VARCHAR(10)  NOT NULL CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),
    reversal_of           BIGINT,
    reversed_by           BIGINT,
    created_by_sub        VARCHAR(100) NOT NULL,
    created_by_name       VARCHAR(200) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by_sub  VARCHAR(100) NOT NULL,
    last_modified_by_name VARCHAR(200) NOT NULL,
    last_modified_at      TIMESTAMPTZ  NOT NULL,
    posted_by_sub         VARCHAR(100),
    posted_by_name        VARCHAR(200),
    posted_at             TIMESTAMPTZ,
    version               BIGINT       NOT NULL,
    UNIQUE (tenant_id, entry_no),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, period_id) REFERENCES accounting.fiscal_period (tenant_id, id)
);
CREATE INDEX journal_entry_date_status_idx ON accounting.journal_entry (tenant_id, entry_date, status);

CREATE TABLE accounting.journal_line (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  UUID           NOT NULL,
    entry_id   BIGINT         NOT NULL,
    line_no    INT            NOT NULL,
    account_id BIGINT         NOT NULL,
    debit      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    credit     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    memo       VARCHAR(200),
    CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0)),
    UNIQUE (tenant_id, entry_id, line_no),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES accounting.journal_entry (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounting.account (tenant_id, id)
);
CREATE INDEX journal_line_account_idx ON accounting.journal_line (tenant_id, account_id);

CREATE TABLE accounting.audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  UUID         NOT NULL,
    action     VARCHAR(30)  NOT NULL CHECK (action IN ('SETUP', 'POST', 'REVERSE', 'PERIOD_CLOSE', 'PERIOD_REOPEN',
                                                       'SETTING_CHANGE')),
    entity     VARCHAR(30)  NOT NULL,
    entity_id  BIGINT,
    actor_sub  VARCHAR(100) NOT NULL,
    actor_name VARCHAR(200) NOT NULL,
    at         TIMESTAMPTZ  NOT NULL,
    details    JSONB
);
CREATE INDEX audit_log_tenant_at_idx ON accounting.audit_log (tenant_id, at);

ALTER TABLE accounting.settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.settings
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.account
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.fiscal_period ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.fiscal_period
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.entry_sequence ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.entry_sequence
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.journal_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.journal_entry
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.journal_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.journal_line
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.audit_log
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The API connects as app_runtime (created outside Flyway). Grants are per table on purpose: no default privileges,
-- so a future table gets no access until its migration has also enabled row-level security.
GRANT USAGE ON SCHEMA accounting TO app_runtime;
GRANT SELECT ON accounting.coa_template TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON accounting.settings TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounting.account TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON accounting.fiscal_period TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON accounting.entry_sequence TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounting.journal_entry TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounting.journal_line TO app_runtime;
GRANT SELECT, INSERT ON accounting.audit_log TO app_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA accounting TO app_runtime;
