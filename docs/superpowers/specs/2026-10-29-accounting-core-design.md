# Accounting core (Phase 1): chart of accounts, journal, periods, trial balance, ledger

Date: 2026-10-29
Status: backend implemented on feat/accounting-core; frontend not started
Depends on: `2026-10-29-multi-tenancy-foundation-design.md` (must be merged first)

## Goal

Real double-entry bookkeeping for Indonesian small and medium companies, sold to several companies later. Each
company is a tenant; this module is tenant-aware from its first line. Phase 1 delivers the core ledger: a company can
set up its chart of accounts, record and post journal entries with maker/checker control, close periods, and read a
trial balance and general ledger.

### Success criteria

- Every posted entry balances (sum of debits equals sum of credits) and can never be edited or deleted; corrections
  are reversing entries.
- Posted entries are numbered `JE-<year>-<6 digits>` without gaps or duplicates per tenant and year, also under
  concurrent posting.
- No entry is posted into a closed period.
- With maker/checker on, nobody posts an entry they created or last edited.
- The trial balance always has a zero difference; tenants never see each other's data.

### Decisions taken during design

| Topic | Decision |
|---|---|
| Scope | Phase 1 = core ledger only (see "Out of scope") |
| Currency | IDR only; amounts `NUMERIC(19,2)` / `BigDecimal`, sent as JSON strings |
| Maker/checker | Per-tenant setting `require_separate_poster`, default on, every change audited |
| Numbering | Assigned at posting, not at draft; gapless per tenant and year |
| Corrections | Reverse (new posted entry with swapped sides); optional "reverse & copy to draft" |
| Chart of accounts | Copied per tenant from a global template at setup; tenants edit their own copy |
| Reports | Computed live from posted lines; no stored balances in Phase 1 |

### Settled by the backend plan

- `settings` has a surrogate `id` plus `UNIQUE (tenant_id)` instead of `tenant_id` as primary key: `@TenantId` is not used on an `@Id` anywhere in this codebase. One row per tenant still holds.
- `entry_sequence` is locked by an atomic `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`, not a separate `SELECT ... FOR UPDATE`: the upsert holds the same row lock until commit and also creates the row on first use.
- `GET /settings` before setup returns 200 with `initialized: false`, so the UI can show its empty state. Writes before setup return 422 `not-initialized`.
- New error code `account-code-exists` (409) for a duplicate account code; the spec's table has no code for it.
- Changing an account to non-postable while it has lines is `account-in-use`, and a parent with children cannot become postable (`validation`).
- "Today" is `Asia/Jakarta`, through one `Clock` bean.
- Closing an already closed period, or reopening an open one, returns the period unchanged and writes no audit row.
- A line naming an unknown or another tenant's account is `account-not-postable` (422), so ids of other tenants stay indistinguishable from missing ones.

Settled during implementation, beyond the plan's list:

- Database guards are stricter than the first design: every non-draft entry must balance at commit, DRAFT -> REVERSED is refused, line writes take `FOR SHARE` on the parent entry and the account so they serialize with posting and deactivation, and `line_account` fires on any line insert or update.
- The REST body field for the entry date is `date`.
- A missing `copyToDraft` means false (the field is `Boolean`, since Jackson 3 refuses a missing primitive).
- Post and draft delete require `version`; a stale one is 409 `version-conflict`. The POST audit row records `totalDebit` and `version`.
- A reversal cannot be dated before its original (400 `validation`).
- New codes: 409 `conflict` (unique violation, SQLSTATE 23505), 500 `database-error`; `invariant-violated` is the 500 code for trigger/CHECK rejections (P0001, 23514). Data exceptions (class 22) are 400 `validation`.
- A `PUT` of an account without `active` keeps the current value; only create defaults it to true.

## Architecture

```
frontend/accounting (ZK)                         backend/accounting
  AccountingUiModule: 4 UiModule beans     REST    AccountingModule
  Journal | Accounts | Periods | Reports  ─────▶   @Import(CommonModule, SecurityModule, TenancyModule)
                                                   controllers → services (rules) → schema "accounting"
                                                   tenant_id + @TenantId + RLS on every tenant table
```

Like every module, `backend/accounting` never imports `iam`, owns schema `accounting`, keeps migrations in
`db/migration/accounting/` with date-based versions, and boots alone in its tests.

## Permissions and roles

New `iam` migration adds permissions and two realm roles (also added to the realm file, with demo users `bookkeeper`
and `accountant`, members of organization `demo`, password = username):

| Permission | VIEWER | BOOKKEEPER | ACCOUNTANT | ADMIN |
|---|---|---|---|---|
| `accounting:read` | ✅ | ✅ | ✅ | ✅ |
| `accounting:write` (drafts) | | ✅ | ✅ | ✅ |
| `accounting:post` (post, reverse) | | | ✅ | ✅ |
| `accounting:period-manage` | | | ✅ | ✅ |
| `accounting:account-manage` (accounts, setup, settings) | | | | ✅ |

## Data model (schema `accounting`)

Global, read-only for `app_runtime`, no RLS:

```sql
coa_template (code VARCHAR(20) PRIMARY KEY, name VARCHAR(200) NOT NULL,
              type VARCHAR(20) NOT NULL, parent_code VARCHAR(20), postable BOOLEAN NOT NULL)
```

Tenant tables: every one has `tenant_id UUID NOT NULL`, `@TenantId` in its entity, RLS policy `tenant_isolation`
(`USING` and `WITH CHECK`), grants to `app_runtime`, and indexes/uniques that start with `tenant_id`.

```sql
settings       (tenant_id PRIMARY KEY, require_separate_poster BOOLEAN NOT NULL DEFAULT true,
                initialized_at TIMESTAMPTZ, updated_by_sub, updated_by_name, updated_at)

account        (id BIGINT IDENTITY, tenant_id, code VARCHAR(20), name VARCHAR(200),
                type VARCHAR(20) CHECK (type IN ('ASSET','LIABILITY','EQUITY','REVENUE','EXPENSE')),
                parent_id BIGINT, postable BOOLEAN, active BOOLEAN DEFAULT true,
                UNIQUE (tenant_id, code), UNIQUE (tenant_id, id),
                FOREIGN KEY (tenant_id, parent_id) REFERENCES account (tenant_id, id))

fiscal_period  (id BIGINT IDENTITY, tenant_id, year INT, month INT CHECK (month BETWEEN 1 AND 12),
                start_date DATE, end_date DATE, status VARCHAR(10) CHECK (status IN ('OPEN','CLOSED')),
                closed_by_sub, closed_by_name, closed_at,
                UNIQUE (tenant_id, year, month), UNIQUE (tenant_id, id))

entry_sequence (tenant_id, year INT, next_value BIGINT NOT NULL, PRIMARY KEY (tenant_id, year))

journal_entry  (id BIGINT IDENTITY, tenant_id, entry_no VARCHAR(20),          -- null until posted
                entry_date DATE NOT NULL, period_id BIGINT NOT NULL,
                reference VARCHAR(100), description VARCHAR(500) NOT NULL,
                status VARCHAR(10) CHECK (status IN ('DRAFT','POSTED','REVERSED')),
                reversal_of BIGINT, reversed_by BIGINT,
                created_by_sub, created_by_name, created_at,
                last_modified_by_sub, last_modified_by_name, last_modified_at,
                posted_by_sub, posted_by_name, posted_at,
                version BIGINT NOT NULL,
                UNIQUE (tenant_id, entry_no), UNIQUE (tenant_id, id),
                FOREIGN KEY (tenant_id, period_id) REFERENCES fiscal_period (tenant_id, id))

journal_line   (id BIGINT IDENTITY, tenant_id, entry_id BIGINT, line_no INT, account_id BIGINT,
                debit NUMERIC(19,2) NOT NULL DEFAULT 0, credit NUMERIC(19,2) NOT NULL DEFAULT 0,
                memo VARCHAR(200),
                CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0)),
                UNIQUE (tenant_id, entry_id, line_no),
                FOREIGN KEY (tenant_id, entry_id)   REFERENCES journal_entry (tenant_id, id),
                FOREIGN KEY (tenant_id, account_id) REFERENCES account (tenant_id, id))

audit_log      (id BIGINT IDENTITY, tenant_id,
                action VARCHAR(30) CHECK (action IN ('SETUP','POST','REVERSE','PERIOD_CLOSE',
                                                     'PERIOD_REOPEN','SETTING_CHANGE')),
                entity VARCHAR(30), entity_id BIGINT, actor_sub, actor_name, at TIMESTAMPTZ, details JSONB)
```

`*_sub` columns hold the Keycloak `sub` (identity for checks); `*_name` columns hold `preferred_username` at the time
of the action (display only, never rewritten). Indexes: `journal_line (tenant_id, account_id)`,
`journal_entry (tenant_id, entry_date, status)`.

Database guards behind the service checks (defence in depth; a violation is a bug, logged as such):

- Deferred constraint trigger: when an entry is `POSTED` at commit, its lines balance and number at least two.
- Trigger: a `POSTED` or `REVERSED` entry and its lines cannot be updated or deleted, except the single transition
  `POSTED → REVERSED` with `reversed_by` set.
- Trigger: a line's account is `postable` and `active` when the line is inserted or changed.

### Chart-of-accounts template (seed of `coa_template`)

Header accounts (not postable) and their postable leaves, a common Indonesian SME layout:

| Code | Name | Type | Postable |
|---|---|---|---|
| 1000 | Aset | ASSET | no |
| 1100 | Aset Lancar | ASSET | no |
| 1110 | Kas | ASSET | yes |
| 1120 | Bank | ASSET | yes |
| 1130 | Piutang Usaha | ASSET | yes |
| 1140 | Persediaan | ASSET | yes |
| 1150 | PPN Masukan | ASSET | yes |
| 1160 | Biaya Dibayar di Muka | ASSET | yes |
| 1200 | Aset Tetap | ASSET | no |
| 1210 | Peralatan | ASSET | yes |
| 1220 | Kendaraan | ASSET | yes |
| 1290 | Akumulasi Penyusutan | ASSET | yes |
| 2000 | Liabilitas | LIABILITY | no |
| 2100 | Utang Usaha | LIABILITY | yes |
| 2110 | PPN Keluaran | LIABILITY | yes |
| 2120 | Utang PPh 21 | LIABILITY | yes |
| 2130 | Utang PPh 23 | LIABILITY | yes |
| 2140 | Biaya yang Masih Harus Dibayar | LIABILITY | yes |
| 2200 | Utang Bank Jangka Panjang | LIABILITY | yes |
| 3000 | Ekuitas | EQUITY | no |
| 3100 | Modal Disetor | EQUITY | yes |
| 3200 | Laba Ditahan | EQUITY | yes |
| 3300 | Laba Tahun Berjalan | EQUITY | yes |
| 4000 | Pendapatan | REVENUE | no |
| 4100 | Penjualan | REVENUE | yes |
| 4200 | Pendapatan Jasa | REVENUE | yes |
| 4900 | Pendapatan Lain-lain | REVENUE | yes |
| 5000 | Beban Pokok Penjualan | EXPENSE | no |
| 5100 | Harga Pokok Penjualan | EXPENSE | yes |
| 6000 | Beban Operasional | EXPENSE | no |
| 6100 | Beban Gaji | EXPENSE | yes |
| 6110 | Beban Perlengkapan Kantor | EXPENSE | yes |
| 6120 | Beban Sewa | EXPENSE | yes |
| 6130 | Beban Listrik, Air dan Internet | EXPENSE | yes |
| 6140 | Beban Penyusutan | EXPENSE | yes |
| 6190 | Beban Lain-lain | EXPENSE | yes |
| 7000 | Beban Non-Operasional | EXPENSE | no |
| 7100 | Beban Bunga | EXPENSE | yes |
| 7200 | Beban Administrasi Bank | EXPENSE | yes |

Normal balance for reports: `ASSET` and `EXPENSE` on the debit side; `LIABILITY`, `EQUITY`, `REVENUE` on the credit
side. `1290 Akumulasi Penyusutan` is a contra-asset with a credit balance; Phase 1 shows it as a negative asset.

## REST API (`/api/accounting`)

| Method and path | Permission | Notes |
|---|---|---|
| `POST /setup` | `account-manage` | Copies the template, creates `settings`, opens the 12 months of the current year; 409 if already done |
| `GET /settings`, `PUT /settings` | `read` / `account-manage` | `PUT` writes `audit_log SETTING_CHANGE` with old and new value |
| `GET /accounts` | `read` | Flat list with `parentId`; the UI builds the tree |
| `POST /accounts`, `PUT /accounts/{id}` | `account-manage` | `type` changes only while the account has no lines; parents must be non-postable |
| `DELETE /accounts/{id}` | `account-manage` | Only without any lines |
| `GET /periods?year=` | `read` | |
| `POST /periods/year/{year}` | `period-manage` | Opens the 12 months of that year; 409 if they exist |
| `POST /periods/{id}/close` | `period-manage` | 409 `period-has-drafts` while drafts dated in it exist |
| `POST /periods/{id}/reopen` | `period-manage` | Body `{reason}` required; stored in `audit_log` |
| `GET /journal-entries?from&to&status&accountId&q&page&size` | `read` | Server-side paging (`size` max 200, default 50) |
| `GET /journal-entries/{id}` | `read` | Entry with lines |
| `POST /journal-entries` | `write` | Creates a draft |
| `PUT /journal-entries/{id}` | `write` | Draft only; body carries `version`; replaces all lines |
| `DELETE /journal-entries/{id}?version=` | `write` | Draft only; `version` required |
| `POST /journal-entries/{id}/post?version=` | `post` | `version` required (the one reviewed); see posting rules |
| `POST /journal-entries/{id}/reverse` | `post` | Body `{date, copyToDraft}`; returns the reversal (and the new draft id) |
| `GET /reports/trial-balance?asOf=` | `read` | Per account: debit total, credit total, balance on its normal side; totals |
| `GET /reports/general-ledger?accountId&from&to` | `read` | Opening balance, lines with running balance, closing balance |

Amounts are JSON strings with two decimals (`"1665000.00"`), read and written as `BigDecimal`.

## Posting rules

**Draft** (`accounting:write`): may be unbalanced. Every line has exactly one side greater than zero and a postable,
active account; at most 500 lines. `entry_date` must fall into an existing period (open or closed). Each change sets
`last_modified_by_*` and requires the current `version` (409 otherwise).

**Post** (`accounting:post`), one transaction:

1. Entry is `DRAFT`, has at least two lines and sum debit = sum credit > 0.
2. Every account is still postable and active.
3. The period of `entry_date` exists and is `OPEN`; it is locked `FOR SHARE` (period close locks it `FOR UPDATE`).
4. With `require_separate_poster` on, the poster's `sub` differs from `created_by_sub` and `last_modified_by_sub`.
5. Number from `entry_sequence` locked `FOR UPDATE` (row created on first use for the year); set `posted_by_*`,
   `posted_at`, status `POSTED`; write `audit_log POST`.

**Reverse** (`accounting:post`): the original is `POSTED` and not reversed. A new entry with the same accounts and
swapped sides, description `Reversal of <entry_no>`, `reversal_of` set, dated by the user (default today) in an
`OPEN` period, is created and posted at once (maker/checker does not apply: the reversal is derived from an approved
entry). The original becomes `REVERSED` with `reversed_by` set; `audit_log REVERSE`. With `copyToDraft`, a new draft
with the original's lines is also created for correction.

**Periods**: close is refused while drafts dated in the period exist; reopen requires a reason. Both are audited.

## Frontend (`frontend/accounting`)

`AccountingUiModule` registers four `UiModule` beans, all with `accounting:read`: Journal (order 20), Accounts (21),
Periods (22), Reports (23). The shell needs no change.

- **Journal list**: filters (date range, status, account, text), server-side paged listbox, "New entry".
- **Entry form**: header (date, period and its status, reference, description); editable lines grid with an account
  combobox (search by code or name, postable and active only), memo, debit and credit `decimalbox` (format
  `#,##0.00`, Indonesian separators; typing one side clears the other); live totals with a Balanced / Difference
  banner; a "Balance" button that puts the difference on the current line. Buttons by state and permission: DRAFT →
  Save draft, Delete (`write`), Post (`post`, enabled only when balanced, with the hint "You prepared this entry;
  another user must post it" when maker/checker blocks the user); POSTED → read-only, Reverse, Reverse & copy to draft
  (`post`); REVERSED → read-only with a link to the reversal. A 409 shows "Someone else changed this entry. Reload?".
- **Accounts**: before setup an empty state with "Initialize from template" (`account-manage`); afterwards a tree with
  add child, edit, activate/deactivate; a settings panel with the "Require a different user to post entries" switch.
- **Periods**: year selector, twelve months with status, close (lists blocking drafts with links), reopen (asks for a
  reason), "Open year N+1".
- **Reports**: trial balance (as-of date) and general ledger (account, date range), both with CSV export through
  ZK `Filedownload`.

Line rows are JavaBean classes (ZK EL cannot read record accessors). Totals and balance logic live in a plain class
so they are unit-testable without ZK. `ApiErrors` gains showing the problem `detail` for 409 and 422.

## Error handling

Every failure is an RFC 9457 `ProblemDetail` with a stable `code` property:

| Status | Codes |
|---|---|
| 400 | `invalid-line`, `too-many-lines`, `invalid-amount`, `validation` |
| 403 | `forbidden`, `separate-poster-required` |
| 404 | `not-found` (also for another tenant's ids) |
| 409 | `conflict`, `version-conflict`, `not-draft`, `already-reversed`, `already-initialized`, `period-has-drafts`, `periods-exist`, `account-in-use` |
| 422 | `unbalanced`, `too-few-lines`, `period-closed`, `no-period`, `account-not-postable`, `not-initialized` |
| 500 | `invariant-violated` (a trigger or CHECK rejected what the service allowed; logged as a bug), `database-error` (any other database failure) |

## Testing

- Unit: balance calculation, maker/checker matrix (creator, last editor, setting off), reversal swaps sides, no silent
  rounding (more than two decimals is `invalid-amount`).
- API (Testcontainers, module boots alone, application as `app_runtime`): full lifecycle; one test per error code;
  two tenants cannot see each other's accounts and entries, and both get their own `JE-<year>-000001`.
- Concurrency: 20 parallel posts get numbers 1–20 without gap or duplicate; a post racing a period close never leaves
  a posted entry in a closed period; a failed post consumes no number.
- Database guards, bypassing the service: posted unbalanced entry rejected at commit; update of a posted line
  rejected; a line referencing another tenant's account fails the composite foreign key.
- Reports: trial balance difference is zero; general ledger running balance; an entry and its reversal net to zero.
- Frontend: context load; unit tests for the form's totals and balance logic.
- README manual checklist: bookkeeper drafts and accountant posts; the same user is blocked, then allowed after
  switching the setting off; period close blocked by drafts; CSV export opens in a spreadsheet; `acme-admin` sees no
  `demo` accounting data.

## Delivery

Two implementation plans after the tenancy foundation is merged: (1) backend (schema, rules, API, tests), (2)
frontend screens. Each is a separate pull request.

## Out of scope (later phases)

- Balance sheet and income statement, year-end closing entry into retained earnings.
- PPN/PPh helpers, e-Faktur/Coretax integration.
- Attachments (receipts), recurring entries, entry templates.
- Multiple currencies and exchange rates.
- Sub-ledgers (accounts receivable/payable, cash and bank) posting through this module's API.
- Period balance snapshots for large ledgers; budget vs actual.
- Several companies inside one tenant, intercompany and consolidation.
