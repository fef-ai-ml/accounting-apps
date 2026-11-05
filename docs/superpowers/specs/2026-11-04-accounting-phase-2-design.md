# Accounting Phase 2: statements and year-end close

Adds the income statement, the balance sheet and a year-end close into retained earnings, on top of the hardened ledger.
Phase 1 is `2026-10-29-accounting-core-design.md`; the hardening is `2026-11-03-ledger-hardening-design.md`.

## Decisions

| Decision | Choice | Decided by |
|---|---|---|
| First readers | Internal use and demos: on-screen reports with CSV, in the chart's own hierarchy | User |
| A late item after a close | Undo and redo: reopen the year, post it, close again; both closes stay in the ledger | User |
| How the close is stored | A real posted journal entry, marked with `closing_year` | User |
| Fiscal year | The calendar year; periods are already stored as year and month | Assumed, not corrected |
| Who closes and reopens | Holders of `accounting:period-manage`: accountant and admin | Assumed, not corrected |
| Second person on a close | None: it posts at once, as a reversal does, and is audited | Assumed, not corrected |

**Success means:** assets equal liabilities plus equity on any date, including mid-year and before any close; after a close,
revenue and expense accounts carry no balance into the new year; an income statement for a year reads the same before a
close, after it and after a reopen.

**Why the marker:** a closing entry takes revenue and expense accounts to zero, so a plain income statement for that year
would show nothing. Reports skip marked entries where they must. Without a marker, the close could only be told apart by
date or description.

## 1. Close a year

`POST /api/accounting/years/{year}/close`, body `{ "retainedEarningsAccountId": 123 }`, permission `accounting:period-manage`.
Returns the year, the entry id and number, and the net profit or loss.

The retained earnings account comes from the request and is not a setting, because the closing entry records which was used.
The dialog defaults to code `3200` when that account exists. It must be `EQUITY`, postable and active.

Refusals, all checked in the service before anything changes:

| Case | Status and code |
|---|---|
| No periods exist for the year | 422 `no-period` |
| Any draft dated in the year; the message gives the count | 409 `year-has-drafts` |
| A revenue or expense account has a balance dated before 1 January of the year | 409 `earlier-year-open` |
| The year already has an active close | 409 `year-closed` |
| Every revenue and expense account nets to zero through 31 December | 409 `nothing-to-close` |
| An account with a balance is deactivated or not postable; the message names it | 422 `account-not-postable` |
| The chosen account is not equity, postable and active | 422 `bad-retained-earnings-account` |

A year with nothing to close has no entry and does not block the next year, because the `earlier-year-open` test looks at
balances and not at closes. Its months are closed one by one on the Periods screen, as today.

In one transaction:

1. Lock the tenant's `settings` row `FOR UPDATE`. Close and reopen both start here, so all year actions in a tenant run one
   at a time. Without this, reopening 2025 while closing 2026 could leave 2026 closed on top of an open year.
2. Lock the periods of every earlier year and then the year's 12 periods `FOR UPDATE`, in year and month order. A post
   already holds its period `FOR SHARE`, so it either finishes first and is counted, or waits and then sees `CLOSED`.
   Without the earlier years, a post into an open earlier year could commit between the `earlier-year-open` check and the
   balance read, and the closing entry would absorb it.
3. Run the refusal checks.
4. Add up each revenue and expense account's balance through 31 December, closing entries included. Build one entry dated
   31 December of the year, described `Closing entry for <year>`, with `closing_year` set:
   - one line per account with a non-zero balance, on the side that takes it to zero; an account running against its normal
     side gets the other side;
   - one line on the retained earnings account for the difference: a credit for a profit, a debit for a loss, and no line
     when they net to zero.
5. Save it as a draft with its lines, as a reversal does, then take a normal `JE-<year>-<6 digits>` number and post it. No
   maker/checker check runs. If December is closed, open it for this step, because the posting guard needs an `OPEN` period.
6. Close every month of the year still open, recording the actor as closing a month does. No separate `PERIOD_CLOSE` rows.
7. Audit `YEAR_CLOSE` with the year, entry number, net profit or loss and retained earnings account code.

The entry may exceed `JournalRules.MAX_LINES`. That cap is checked where users key lines, and a chart with 500 revenue and
expense accounts is not expected, so there is no special case.

## 2. Reopen a year

`POST /api/accounting/years/{year}/reopen`, body `{ "reason": "..." }`, permission `accounting:period-manage`. The reason is
required, as when reopening a month.

| Case | Status and code |
|---|---|
| The year has no active close | 409 `year-not-closed` |
| A later year has an active close | 409 `later-year-closed` |
| An account on the closing entry has since been deactivated; the message names it, and it can be reactivated | 422 `account-not-postable` |

In one transaction, in the global lock order, with `settings` first:

1. Lock the `settings` row `FOR UPDATE`, then the closing entry through `lockForChange`.
2. Lock December `FOR UPDATE` and open it.
3. Reverse the closing entry through the existing reversal code, dated 31 December of the year, with `closing_year` on the
   reversal too. No `copyToDraft`.
4. Audit `YEAR_REOPEN` with the year, both entry numbers and the reason.

December stays open. The other months stay closed until reopened one by one, which is now allowed because the year has no
active close. Closing again creates a new closing entry; the old one and its reversal stay in the ledger.

## 3. Rules that protect a close

An **active close** is an entry with `closing_year` set, status `POSTED` and `reversal_of` null. A reversed close has status
`REVERSED`, and its reversal has `reversal_of` set, so neither is active.

In the services:

- `PostingService.reverse` refuses an entry with `closing_year` set, with 409 `closing-entry`. Only reopen undoes a close.
- `PeriodService.reopen` refuses a month of a year with an active close, with 409 `year-closed`.
- `PeriodService.close` does not change. Closing the last open month of a year does not close the year.

In the database, in a new migration:

```sql
ALTER TABLE accounting.journal_entry
    ADD COLUMN closing_year INT,
    ADD CHECK (closing_year IS NULL OR entry_date = make_date(closing_year, 12, 31));

CREATE UNIQUE INDEX journal_entry_active_close_idx ON accounting.journal_entry (tenant_id, closing_year)
    WHERE closing_year IS NOT NULL AND status = 'POSTED' AND reversal_of IS NULL;
```

Commit-time constraint triggers, because each rule is briefly false inside a legitimate transaction:

- On `journal_entry`, a row with `closing_year` set must, in its state at commit, not be `DRAFT`; and if it is an active
  close, all 12 months of that year must be `CLOSED`. A deferred trigger's `NEW` is the row at the time of the event, so the
  guard re-reads the row.
- On `journal_entry`, a row with `reversal_of` set must carry the same `closing_year` as the entry it reverses. A normal
  reversal of a close and a closing reversal of a normal entry are both refused.
- On `fiscal_period`, an `OPEN` month is refused when its year has an active close.

The existing immutability trigger already freezes `closing_year` once an entry is posted, because it compares every column.

Also in that migration: `YEAR_CLOSE` and `YEAR_REOPEN` join `audit_log_action_check`. Existing rows need no backfill and the
README pre-check does not change, because these rules do not examine old rows. No new table, so no new RLS policy or grant.

## 4. Reports

Both use `accounting:read`, count only live lines (`l.deleted_at is null`) on entries with status `POSTED` or `REVERSED`, and
carry the tenant predicate the trial balance query has.

A plain class builds the hierarchy from accounts and balances, and is unit-tested without a database. A parent row's amount
includes its own balance and its descendants'. An account whose parent is missing or of another type becomes a top-level row.
Rows that net to zero, with no non-zero descendant, are hidden.

### Income statement

`GET /api/accounting/reports/income-statement?from&to`. Both dates are required and `from` must not be after `to`, or 400
`validation`. The screen defaults to 1 January of this year through today.

- Sections: revenue, expenses, each by hierarchy. Then total revenue, total expenses, and net profit or loss.
- Leaves out every entry with `closing_year` set: closes and their reversals. A range across two years therefore works.
- Normal reversals are counted, so a reversed entry nets to zero.

### Balance sheet

`GET /api/accounting/reports/balance-sheet?asOf`. `asOf` defaults to today, as the trial balance does.

- Sections: assets, liabilities, equity, each by hierarchy, with balances through `asOf`, closing entries included.
- Equity ends with one calculated line, **Earnings not yet closed**: revenue balances minus expense balances through `asOf`.
  It is not an account and nothing is posted to it.
- Total assets, total liabilities and equity, and their difference. The difference is shown and not hidden, as on the trial
  balance. It is always 0 unless there is a bug.
- A contra account such as `1290 Akumulasi Penyusutan` shows as a negative within assets.

`3300 Laba Tahun Berjalan` is left alone: the close does not use it, and a balance posted there by hand shows as ordinary
equity.

### Existing reports

The trial balance and general ledger do not change, and show the closing entry as a normal entry. A trial balance for
31 December after a close therefore shows zero revenue and expense, so that tab gets a static note saying so. A pre-close
trial balance is out of scope.

## 5. Year status

`GET /api/accounting/years/{year}`, permission `accounting:read`, returns whether the year has an active close and that
entry's id and number. The Periods screen uses it to choose its button.

`EntryResponse` gains `closingYear`, so the entry screen can show its label and hide Reverse.

## 6. Screens

- **Reports:** Income statement and Balance sheet tabs, each with Run and Export CSV (dot decimals, as the trial balance), plus
  the static note on the trial balance tab.
- **Periods:** a **Close year** or **Reopen year** button beside the year selector, for holders of `period-manage`.
  - Close asks for the retained earnings account and shows the net profit or loss, taken from the income statement for
    1 January to 31 December, before the person confirms.
  - Reopen asks for a reason, as reopening a month does.
  - Refusals appear through `ApiErrors.show`.
- **Entry:** a closing entry or its reversal shows a "Closing entry" label and no Reverse buttons.
- **Audit:** `YEAR_CLOSE` and `YEAR_REOPEN` join the action filter.

DTOs are duplicated in `frontend/accounting`, as the rest of the module does.

## 7. Known limits

- The Phase 1 race remains: `periodOf` takes no lock, so a draft saved during a close can land in the closed year. It cannot
  be posted, and moves no money. It can still be re-dated or deleted, because neither needs its period open.
- A post racing an account deactivation is unchanged.
- These rules guard new changes and do not examine old rows.

## 8. Testing

- **Close:** profit, loss, an account running against its normal side, zero net with non-zero accounts, and every refusal.
- **The invariant:** on 31 December and on a June date, balance sheet difference 0 and total equity unchanged before a close,
  after it and after a reopen. The year's income statement is identical across all three.
- **Close and reopen again:** a late invoice ends up in the second close, and the ledger keeps both closes and the reversal.
- **Order:** years close in order and reopen in reverse order. Next year's revenue starts from zero. A year with nothing to
  close does not block the next.
- **Protection:** Reverse refuses a closing entry and its reversal; a month of a closed year cannot be reopened.
- **Races, with real concurrent transactions:** a post racing a close; close of one year racing reopen of the year before.
- **Database guards, by direct SQL:** a second active close; a wrong date; a closing entry left as a draft; an active close with
  an open month; an open month set after the close; a reversal with a different `closing_year`.
- **Tenancy:** new native queries prove isolation with RLS alone, not through `@TenantId`.
- **Migration:** `LegacyDataMigrationTests` covers a Phase 1 database, including closing a year of old entries.
- **Permissions:** real signed tokens for the bookkeeper and accountant path; a bookkeeper gets 403 on close and reopen.
- **Screens:** render and click-path tests as for the other accounting screens; plain-class tests of the hierarchy builder and
  CSV. New README manual rows, including the balance sheet with a contra account and a close followed by a reopen.

Backend tests run as `app_runtime`, as all of them do.

## Out of scope

- Comparison columns and PDF.
- Fiscal years that are not calendar years.
- A pre-close trial balance.
- A "final, cannot reopen" lock.
- PPN/PPh helpers.
- Fixing the Phase 1 races in section 7.
