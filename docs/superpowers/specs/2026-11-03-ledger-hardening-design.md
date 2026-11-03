# Ledger hardening before accounting Phase 2

## Goal

Close the known gaps in the Phase 1 ledger before income statement, balance sheet and year-end close build on it. No
new accounting features. After this work, the database refuses states the services already avoid, no journal line is
ever hard deleted, maker/checker covers every editor of a draft, and accountants and admins can read the audit log.

## Decisions by the user

- Anyone who ever edited a draft cannot post it, when maker/checker is on.
- No journal line is ever hard deleted.
- Account changes are audited: code, name, type, parent, postable and active. Draft edits are not.
- The audit log gets a read API and a screen, for accountant and admin only.
- A reversal can still be reversed. `Asia/Jakarta` stays. Typing `11` then Tab stays.

## Out of scope

- A screen or API for removed lines or deleted drafts. They are reachable by SQL only.
- Audit export, and auditing account create and delete. An account can only be deleted if no line ever used it.
- Soft delete for accounts and other tables. Only journal lines, and the entries that own them, are kept.
- A purge path. Nothing here removes retained rows, including for a tenant that leaves.
- The small test gaps and the "not reproduced live" items parked in earlier reviews.

## 1. No journal line is hard deleted

A line is removed from a draft in two ways today: saving a draft replaces its lines, and deleting a draft deletes its
lines. Both become soft deletes. A deleted draft's entry row stays too, because its lines reference it.

**Columns**, on both `journal_entry` and `journal_line`, following the `posted_by_*` pattern:

```sql
deleted_at      TIMESTAMPTZ,
deleted_by_sub  VARCHAR(100),
deleted_by_name VARCHAR(200),
CHECK ((deleted_at IS NULL) = (deleted_by_sub IS NULL)),
CHECK ((deleted_at IS NULL) = (deleted_by_name IS NULL))
```

`journal_entry` also gets `CHECK (deleted_at IS NULL OR status = 'DRAFT')`. A deleted entry keeps status `DRAFT`, so
`EntryStatus`, the API and the frontend DTOs do not change.

**Uniqueness.** `UNIQUE (tenant_id, entry_id, line_no)` becomes a unique index `WHERE deleted_at IS NULL`, so a saved
draft can reuse line numbers.

**Enforcement in the database:**

- `REVOKE DELETE ON accounting.journal_entry, accounting.journal_line FROM app_runtime`.
- `entry_immutable` and `line_immutable` raise on every `DELETE`, draft or not, so the owner role is refused too.
- A `BEFORE TRUNCATE` statement trigger on both tables raises.
- A row with `deleted_at` set cannot be updated.
- A line cannot be inserted on, or moved to, a deleted entry.
- `line_account` skips rows being soft deleted. Otherwise a draft holding a line on a since-deactivated account could
  not be edited or deleted.
- `posted_entry_balances` counts only lines with `deleted_at IS NULL`.

A role that owns the tables can still disable the triggers or drop the schema on purpose. The API role cannot.

**Services:**

- `JournalEntry.lines` drops `orphanRemoval` and `CascadeType.REMOVE`, keeping `PERSIST`, so Hibernate has no path to a
  `DELETE`.
- `@SQLRestriction("deleted_at is null")` on `JournalEntry` and `JournalLine` hides removed rows from JPA, Criteria and
  collection loads. A deleted draft's id answers 404.
- `JournalEntry.removeLines(actor, at)` marks every live line. `JournalService.update` calls it, flushes, clears the list
  and adds the new lines. The flush must stay: Hibernate inserts before it updates, which would trip the unique index.
- `JournalEntry.markDeleted(actor, at)` marks the entry and calls `removeLines`. `JournalService.delete` uses it, and
  `entries.delete` is removed.
- `DELETE /api/accounting/entries/{id}` keeps its contract: 204, 409 on a stale version, 404 when already deleted.

**Native SQL does not see `@SQLRestriction`.** Each of these must filter by hand:

| Site | Change |
|---|---|
| `ReportService.trialBalance` | `l.deleted_at is null` |
| `ReportService.generalLedger`, opening balance | `l.deleted_at is null` |
| `ReportService.generalLedger`, lines | `l.deleted_at is null` |
| `FiscalPeriodRepository`, draft count for period close | `deleted_at is null` |
| `AccountRepository` | see below |

A missed filter here makes a posted entry's report wrong, since an edited draft keeps its old lines after posting.
Every report site gets a test that posts an entry edited first and checks the totals.

**Accounts.** `hasLines` stays as it is for `AccountService.delete`, because the foreign key from a removed line still
blocks the delete. New `hasLiveLines` guards type and postable changes, so a deleted draft does not freeze an account's
type. The delete refusal says "An account used by journal lines, even in deleted drafts, or with child accounts cannot
be deleted". The screens have no delete button for accounts, so deactivating stays the way out.

**Costs.** Every save keeps the lines it replaced, so a 10-line draft saved five times leaves 40 removed rows. Memos on
removed lines stay in the database with no way to purge them. Lines already deleted before this migration cannot be
recovered.

## 2. Who may post

- New column `prepared_by_subs VARCHAR(100)[] NOT NULL DEFAULT '{}'` on `journal_entry`, mapped with
  `@JdbcTypeCode(SqlTypes.ARRAY)`. It holds the creator and every editor, without duplicates. `edit()` appends the actor.
- `JournalRules.mayPost(actorSub, preparedBySubs, separatePoster)` replaces the creator and last-editor arguments.
  `PostingService.post` and `JournalService.response` (`posterBlocked`) both call it.
- The message becomes "You prepared or edited this entry; another user must post it". Both the backend and the frontend
  hint change.
- A reversal posts at once under the same rule as today: maker/checker does not apply to it.
- **Migration:** backfills drafts only, from `created_by_sub` and `last_modified_by_sub`. Earlier editors cannot be
  recovered. Posted rows stay `{}`: updating them would trip `entry_immutable`, and nothing reads the column after
  posting. `entry_immutable` compares `to_jsonb`, so the column freezes at posting with no change to the guard.

An array is chosen over an editors table because an element collection would not get `tenant_id` from `@TenantId`, and
it would need its own RLS policy. A table only pays off if editor names per draft are wanted later.

## 3. Database rules

```sql
ALTER TABLE accounting.journal_entry
    ADD CHECK ((status = 'DRAFT') = (entry_no IS NULL)),
    ADD UNIQUE (tenant_id, reversal_of),
    ADD FOREIGN KEY (tenant_id, reversal_of) REFERENCES accounting.journal_entry (tenant_id, id),
    ADD FOREIGN KEY (tenant_id, reversed_by) REFERENCES accounting.journal_entry (tenant_id, id);
```

- `UNIQUE (tenant_id, reversal_of)` allows reversing a reversal, because each has a different `reversal_of`. Several
  `NULL`s are allowed.
- New `BEFORE INSERT OR UPDATE` trigger on `journal_entry`: a row becoming `POSTED`, by insert or from `DRAFT`, needs its
  period `OPEN`. It reads the period `FOR SHARE`. `POSTED → REVERSED` is not affected, so an entry in a closed period can
  still be reversed into an open one.
- **Failure mode:** existing rows are checked when the migration runs, so bad data stops the backend from starting. The
  services already follow these rules, so none is expected. The README gets a read-only SQL pre-check to run first.

## 4. Row lock

`JournalService.loadForChange(id)` uses a `PESSIMISTIC_WRITE` query. Post, update, delete and reverse all use it instead
of `load`.

| Race | Result for the loser |
|---|---|
| Delete vs post, post won | 409 `not-draft` |
| Delete vs post, delete won | 404, because `FOR UPDATE` re-checks `deleted_at is null` on the committed row |
| Two reverses | 409 `already-reversed` |
| Two posts | 409 `not-draft` |

Lock order is entry, then period, then sequence. Period close locks the period and takes no entry locks, so there is no
cycle.

## 5. Audit

**Recording.** New action `ACCOUNT_CHANGE`; the migration replaces the `audit_log` action `CHECK`. `AccountService.update`
writes one row holding only the fields that changed, as `{"name": {"from": "Kas", "to": "Kas Kecil"}}`, for `code`, `name`,
`type`, `parent`, `postable` and `active`. `parent` is recorded as the account code, or `null` at the top level. No change, no row.

**API.** `GET /api/accounting/audit?from&to&action&page&size`, requiring new permission `accounting:audit-read`, mapped
to ACCOUNTANT and ADMIN by `iam/V20261103_1__add_audit_read_permission.sql`.

- Newest first, by `at` then `id`. `size` is 1 to 200, as on the journal list.
- `from` and `to` are dates in `Asia/Jakarta`, both inclusive. `from` after `to` is 400 `validation`.
- An unknown `action` is 400 `validation`.
- A row carries `at`, `actorName`, `action`, `entity`, `entityId` and `details`.
- The existing `(tenant_id, at)` index serves this query, so no new index.

**Screen.** `audit.zul`, menu item "Audit" (`UiModule.embedded("accounting-audit", "Audit", 24, ...)` with the new
permission). Filters: date range and action. Paging. Columns: time, who, action, record, details. Details show as flat
text, such as `name: Kas → Kas Kecil`, formatted by a plain class with a unit test. Read-only, no export.

## 6. Safety checks

- **Startup check.** In `backend/tenancy`, an `ApplicationRunner` stops the app if the connected role is superuser, has
  `BYPASSRLS`, or owns any table, using the same queries as `ApiApplicationTests.connectsAsARoleThatRowLevelSecurityAppliesTo`.
  The error names the role and the reason. There is no off switch. It runs just after the server starts, so there is a
  brief moment before the app exits.
- **Security gate.** `KeycloakJwtAuthenticationConverter` resolves the tenant first. No active tenant means no
  authorities, and `PermissionResolver` is not called. The duplicate tenant lookup goes.
- **Error handler.** `AccountingExceptionHandler` adds `log.warn` with the SQLState and constraint name on 23505 and
  class 22, which can hide a numbering or cast bug. A direct unit test covers every SQLState branch.

## 7. Migrations

Flyway versions are global, so these two share one date with different numbers.

- `iam/V20261103_1__add_audit_read_permission.sql`
- `accounting/V20261103_2__ledger_hardening.sql`, in this order: columns, constraints and index, guards, `REVOKE`,
  backfill.

No new tables, so no new RLS policies or grants.

## 8. Testing

Rules are proven with native SQL, bypassing the services, as `DatabaseGuardTests` does.

- **No hard delete:** `DELETE` as `app_runtime` is refused on draft, posted and reversed rows. `DELETE` and `TRUNCATE` as
  the owner are refused by the triggers. `has_table_privilege` shows no `DELETE`.
- **Retention:** saving a draft keeps the old lines with who and when; deleting a draft keeps the entry and lines; neither
  shows through the API, the journal list or the account filter.
- **Reports:** an entry edited and then posted counts only its live lines, in the trial balance and in both general
  ledger queries.
- **Period close:** a deleted draft does not block it.
- **Accounts:** a draft on a since-deactivated account can be edited and deleted. An account used only by a deleted draft
  can change type but cannot be deleted.
- **Maker/checker:** a user who edited, then a later editor saved, still cannot post. Setting off allows it. Backfill is
  checked on a draft.
- **Database rules:** each `CHECK`, unique and foreign key above; posting into a closed period refused by insert and by
  update; a reversal of a reversal still works.
- **Concurrency:** delete against post, with real threads as in `PostingConcurrencyTests`.
- **Audit:** one row per change holding only changed fields; none when nothing changed; filters, Jakarta day boundaries,
  paging; 403 for bookkeeper and viewer; tenant isolation through RLS alone.
- **Screen:** render test, and click-path tests for filter and paging.
- **Startup check:** refuses a role that skips RLS, and passes as `app_runtime`.
- **Security gate:** a token with no active tenant gets no authorities and the resolver is not called.

**Existing tests that change:** `DatabaseGuardTests.draftsStayEditable` deletes a draft, and four delete assertions on
posted rows expect "cannot be changed" but will now see a permission error. The test at `DatabaseGuardTests:49` inserts a
`POSTED` row with no `entry_no`, which the new `CHECK` refuses before its current assertion can match. The unit matrix for
`mayPost` moves to the new signature.

## 9. Docs

- Accounting core spec: the invariant "nobody posts an entry they created or last edited" (line 21), the posting rule
  (line 255), the trigger rule (line 166), and the hint text.
- README: checklist rows for the audit screen and the maker/checker wording, plus the pre-check SQL.
- `CLAUDE.md`: a gotcha that journal lines are never deleted, so every native query on `journal_line` must filter
  `deleted_at`; Status updated.

## Not verified yet

- `ddl-auto=validate` with the array column. If the mapping fails, the fallback is `String[]` with an explicit type.
- Whether `@SQLRestriction` on `JournalLine` reaches the `@OneToMany` collection and the Criteria subquery in
  `JournalService.filter` under Hibernate 7.4. Tests above pin both; if not, the annotation also goes on the collection.
- Whether test fixtures insert rows the new rules refuse, such as `POSTED` into a closed period or with no `entry_no`.
- The name of the `audit_log` action `CHECK`, which the migration must drop. It is unnamed in the DDL, so Postgres should
  have called it `audit_log_action_check`.
