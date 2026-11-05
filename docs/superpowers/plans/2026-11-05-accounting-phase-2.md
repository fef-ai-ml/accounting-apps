# Accounting Phase 2 Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Add the income statement, the balance sheet and a year-end close into retained earnings, on top of the hardened ledger.

**Architecture:** A close is a real posted journal entry marked with `closing_year`; reports skip marked entries where they must, and commit-time triggers back the service rules. A new `YearEndService` drives close and reopen and reuses `PostingService` for numbering and reversal. `ReportService` gains two statements built by a plain, unit-tested `StatementHierarchy`; the ZK screens gain two Reports tabs and Close year / Reopen year panels on Periods.

**Tech Stack:** Java 25, Spring Boot 4.1.2, Hibernate 7.4, Jackson 3, Flyway, Postgres 17 with RLS, ZK 10.3 MVVM, Testcontainers, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-04-accounting-phase-2-design.md`

## Global Constraints

- Work on branch `feat/accounting-phase-2`. Do not push.
- The migration is `V20261105_1__year_end_close.sql`, in `backend/accounting/src/main/resources/db/migration/accounting/`. Do not run it against the home-lab database; Testcontainers only.
- Commits are signed through 1Password. Never bypass signing. Stage explicit paths; never stage `.bloop`, `.metals`, `graphify-out` or `.superpowers`. End each commit message with the attribution trailer your harness specifies.
- Backend tests need Docker (Testcontainers); on the maintainer's Mac that is the SSH-forwarded Podman socket. If the socket is gone, stop and report; do not touch home-lab containers.
- Tests run the application as `app_runtime`; never use `@ServiceConnection`.
- A rule enforced by a trigger must also be checked in the service first, because a trigger failure is a logged 500.
- Native SQL on `journal_line` must filter `l.deleted_at is null` and carry the tenant predicate the trial balance query has.
- Global lock order: `settings` (year actions only), then entry, then period, then sequence. Periods of a year lock in month order.
- ZK: any record bound from ZUL needs JavaBean getters. View models catch `RuntimeException` around API calls and pass it to `ApiErrors.show(...)`. Amounts in JSON are strings with two decimals.
- DTOs are duplicated in `frontend/accounting`; UI modules never import backend classes.
- Run backend module tests with `./mvnw -pl backend/accounting -am test -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false`, and shell tests (no Docker) with `./mvnw -pl frontend/shell -am test -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false`.
- A Maven run with no `BUILD` line is not a pass.

**Ruling on refusal order.** The spec's refusal table does not fix an order. The code checks `no-period`, then `year-closed`, then the rest in table order, so a double click says `year-closed` and not `nothing-to-close`. `no-period` also covers a year with fewer than 12 periods, which only hand-made data can produce and which the commit-time guard would otherwise turn into a 500.

## Review Focus

These are pinned by tests in the owning tasks.

1. The person closing the year prepared some of its entries, with maker/checker on: the close still posts, because the check does not run for it (Task 3).
2. A draft edited before posting: the close counts only live lines, not the removed ones (Task 3).
3. An income statement whose range crosses a closed year and the next: the figures are the real activity, with no closing entry in them (Task 4).
4. A report date box cleared before Run: a warning and no API call, not a failed request (Task 6).
5. The year field changed while the Close year panel is open: the panel goes away, so the close cannot run for a year the person did not review (Task 7).

---

### Task 1: The `closing_year` marker and its database guards

**Files:**
- Create: `backend/accounting/src/main/resources/db/migration/accounting/V20261105_1__year_end_close.sql`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalEntry.java`
- Modify: `backend/accounting/src/test/java/com/example/accounting/AccountingTestSupport.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/YearEndGuardTests.java`

**Interfaces:**
- Consumes: `insertAccount`, `insertPeriod`, `insertEntry`, `insertLine`, `sql` in `AccountingTestSupport`.
- Produces: `JournalEntry.closingYear(Integer)`, `JournalEntry.getClosingYear(): Integer`; test helpers `closingDraft(int year, long debitAccount, long creditAccount): long`, `postBySql(long entry)`, `closeMonthsBySql(int year)`, `closeBySql(int year, long debitAccount, long creditAccount): long`, each called inside a tenant transaction, with all 12 periods of the year existing, for an amount of 100.00.

- [ ] **Step 1: Add the test helpers to `AccountingTestSupport`**

Add above `sql(...)`:

```java
	/** A closing draft of 100.00 dated 31 December; the year's December must exist. Call inside a transaction. */
	long closingDraft(int year, long debitAccount, long creditAccount) {
		long december = ((Number) entityManager.createNativeQuery(
				"select id from accounting.fiscal_period where year = " + year + " and month = 12").getSingleResult()).longValue();
		long entry = insertEntry(december, "DRAFT");
		sql("update accounting.journal_entry set entry_date = make_date(" + year + ", 12, 31), closing_year = " + year
				+ " where id = :id", entry);
		insertLine(entry, 1, debitAccount, "100.00", "0");
		insertLine(entry, 2, creditAccount, "0", "100.00");
		return entry;
	}

	void postBySql(long entry) {
		sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-S" + entry + "' where id = :id", entry);
	}

	void closeMonthsBySql(int year) {
		entityManager.createNativeQuery("update accounting.fiscal_period set status = 'CLOSED' where year = " + year).executeUpdate();
	}

	/** Posts a closing entry and closes the year's months, as the service does. Call inside a transaction. */
	long closeBySql(int year, long debitAccount, long creditAccount) {
		long entry = closingDraft(year, debitAccount, creditAccount);
		postBySql(entry);
		closeMonthsBySql(year);
		return entry;
	}
```

- [ ] **Step 2: Write the failing guard tests**

Create `YearEndGuardTests.java`:

```java
package com.example.accounting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bypasses the services with native SQL as app_runtime: a close must hold even if a service is wrong. */
class YearEndGuardTests extends AccountingTestSupport {

	private static final int CLOSING = 2030;

	/** The 12 open months of the year and two accounts, as {debit, credit}. */
	private long[] fixture() {
		for (int month = 1; month <= 12; month++) {
			insertPeriod(CLOSING, month);
		}
		return new long[] { insertAccount("A", true, true), insertAccount("B", true, true) };
	}

	/** A committed close, as {closing entry, debit account, credit account}. */
	private long[] closed() {
		return inTransaction(() -> {
			long[] accounts = fixture();
			return new long[] { closeBySql(CLOSING, accounts[0], accounts[1]), accounts[0], accounts[1] };
		});
	}

	private long december() {
		return ((Number) entityManager.createNativeQuery(
				"select id from accounting.fiscal_period where year = " + CLOSING + " and month = 12").getSingleResult()).longValue();
	}

	private void markReversed(long original, long reversal) {
		sql("update accounting.journal_entry set status = 'REVERSED', reversed_by = " + reversal
				+ ", version = version + 1 where id = :id", original);
	}

	/** Opens December and posts the mirror closing entry, as reopening a year does. */
	private void reopen(long[] close) {
		inTransaction(() -> {
			sql("update accounting.fiscal_period set status = 'OPEN' where id = :id", december());
			long reversal = closingDraft(CLOSING, close[2], close[1]);
			sql("update accounting.journal_entry set reversal_of = " + close[0] + " where id = :id", reversal);
			postBySql(reversal);
			markReversed(close[0], reversal);
			return null;
		});
	}

	@Test
	void aPostedCloseWithAllMonthsClosedIsAccepted() {
		assertThatCode(this::closed).doesNotThrowAnyException();
	}

	@Test
	void aSecondActiveCloseOfAYearIsRefused() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long[] accounts = fixture();
			postBySql(closingDraft(CLOSING, accounts[0], accounts[1]));
			postBySql(closingDraft(CLOSING, accounts[0], accounts[1]));
			return null;
		})).hasStackTraceContaining("journal_entry_active_close_idx");
	}

	@Test
	void aClosingEntryMustBeDatedTheLastDayOfItsYear() {
		assertThatThrownBy(() -> inTransaction(() -> {
			fixture();
			long entry = insertEntry(december(), "DRAFT");
			return sql("update accounting.journal_entry set closing_year = " + CLOSING + " where id = :id", entry);
		})).hasStackTraceContaining("journal_entry_closing_date_check");
	}

	@Test
	void aClosingEntryCannotStayADraft() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long[] accounts = fixture();
			return closingDraft(CLOSING, accounts[0], accounts[1]);
		})).hasStackTraceContaining("cannot stay a draft");
	}

	@Test
	void anActiveCloseNeedsEveryMonthClosed() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long[] accounts = fixture();
			postBySql(closingDraft(CLOSING, accounts[0], accounts[1]));
			closeMonthsBySql(CLOSING);
			return sql("update accounting.fiscal_period set status = 'OPEN' where id = :id", december());
		})).hasStackTraceContaining("needs all 12 months");
	}

	@Test
	void aMonthCannotBeOpenedUnderAnActiveClose() {
		closed();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.fiscal_period set status = 'OPEN' where id = :id", december())))
				.hasStackTraceContaining("has an active close");
	}

	@Test
	void aNormalReversalOfACloseIsRefused() {
		long[] close = closed();
		assertThatThrownBy(() -> inTransaction(() -> {
			long reversal = insertEntry(insertPeriod(CLOSING + 1, 1), "DRAFT");
			insertLine(reversal, 1, close[2], "100.00", "0");
			insertLine(reversal, 2, close[1], "0", "100.00");
			sql("update accounting.journal_entry set reversal_of = " + close[0] + " where id = :id", reversal);
			postBySql(reversal);
			markReversed(close[0], reversal);
			return null;
		})).hasStackTraceContaining("different closing year");
	}

	@Test
	void aClosingReversalOfANormalEntryIsRefused() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long[] accounts = fixture();
			long normal = insertEntry(december(), "DRAFT");
			insertLine(normal, 1, accounts[0], "100.00", "0");
			insertLine(normal, 2, accounts[1], "0", "100.00");
			postBySql(normal);
			long reversal = closingDraft(CLOSING, accounts[1], accounts[0]);
			sql("update accounting.journal_entry set reversal_of = " + normal + " where id = :id", reversal);
			postBySql(reversal);
			markReversed(normal, reversal);
			return null;
		})).hasStackTraceContaining("different closing year");
	}

	@Test
	void aReopenedYearCanBeClosedAgain() {
		long[] close = closed();
		reopen(close);
		assertThatCode(() -> inTransaction(() -> closeBySql(CLOSING, close[1], close[2]))).doesNotThrowAnyException();
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='YearEndGuardTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, because column `closing_year` does not exist.

- [ ] **Step 4: Write the migration**

Create `V20261105_1__year_end_close.sql`:

```sql
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
```

- [ ] **Step 5: Map the column on `JournalEntry`**

After the `reversedBy` field:

```java
	@Column(name = "closing_year")
	private Integer closingYear;
```

After `reversalOf(Long originalId)`:

```java
	void closingYear(Integer year) {
		this.closingYear = year;
	}
```

After `getReversedBy()`:

```java
	public Integer getClosingYear() {
		return closingYear;
	}
```

- [ ] **Step 6: Run the guard tests, then the module's existing guard and migration tests**

Run: `./mvnw -pl backend/accounting -am test -Dtest='YearEndGuardTests,DatabaseGuardTests,LegacyDataMigrationTests,SchemaTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add backend/accounting/src/main/resources/db/migration/accounting/V20261105_1__year_end_close.sql backend/accounting/src/main/java/com/example/accounting/JournalEntry.java backend/accounting/src/test/java/com/example/accounting/AccountingTestSupport.java backend/accounting/src/test/java/com/example/accounting/YearEndGuardTests.java
git commit -m "Mark closing entries and guard a closed year in the database"
```

---

### Task 2: Income statement and balance sheet API

**Files:**
- Create: `backend/accounting/src/main/java/com/example/accounting/StatementRow.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/StatementHierarchy.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/IncomeStatement.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/BalanceSheet.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/ReportService.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/ReportController.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/StatementHierarchyTests.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/StatementReportTests.java`

**Interfaces:**
- Consumes: `closeBySql` from Task 1.
- Produces:
  - `ReportService.debitMinusCredit(LocalDate through): Map<Long, BigDecimal>`, public, keyed by account id, closing entries included; accounts with no lines are absent.
  - `ReportService.incomeStatement(LocalDate from, LocalDate to): IncomeStatement`, `ReportService.balanceSheet(LocalDate asOf): BalanceSheet`.
  - `GET /api/accounting/reports/income-statement?from&to`, `GET /api/accounting/reports/balance-sheet?asOf`.
  - JSON fields: `IncomeStatement(from, to, revenue, expenses, totalRevenue, totalExpenses, netProfit)`; `BalanceSheet(asOf, assets, liabilities, equity, earningsNotYetClosed, totalAssets, totalLiabilities, totalEquity, totalLiabilitiesAndEquity, difference)`; `StatementRow(accountId, code, name, depth, amount)`. `netProfit` is negative for a loss.

- [ ] **Step 1: Write the failing hierarchy tests**

Create `StatementHierarchyTests.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import com.example.accounting.StatementHierarchy.Node;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class StatementHierarchyTests {

	private static final Node HEADER = new Node(1L, "1000", "Aset", null);

	private static final Node CASH = new Node(2L, "1110", "Kas", 1L);

	private static final Node BANK = new Node(3L, "1120", "Bank", 1L);

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	@Test
	void parentsComeBeforeChildrenSortedByCodeAndIncludeTheirDescendants() {
		List<StatementRow> rows = StatementHierarchy.rows(List.of(BANK, CASH, HEADER),
				Map.of(2L, money("10.00"), 3L, money("5.00")));

		assertThat(rows).extracting(StatementRow::code, StatementRow::depth, StatementRow::amount).containsExactly(
				tuple("1000", 0, money("15.00")), tuple("1110", 1, money("10.00")), tuple("1120", 1, money("5.00")));
	}

	@Test
	void aParentsOwnBalanceCounts() {
		List<StatementRow> rows = StatementHierarchy.rows(List.of(HEADER, CASH), Map.of(1L, money("1.00"), 2L, money("2.00")));

		assertThat(rows.get(0).amount()).isEqualTo(money("3.00"));
	}

	@Test
	void anAccountWhoseParentIsNotInTheSectionIsTopLevel() {
		assertThat(StatementHierarchy.rows(List.of(CASH), Map.of(2L, money("2.00"))))
				.extracting(StatementRow::code, StatementRow::depth).containsExactly(tuple("1110", 0));
	}

	@Test
	void rowsThatNetToZeroWithNoMovementBelowThemAreHidden() {
		assertThat(StatementHierarchy.rows(List.of(HEADER, CASH, BANK), Map.of(2L, money("0.00")))).isEmpty();
	}

	@Test
	void aParentStaysWhenItsChildrenCancelOut() {
		List<StatementRow> rows = StatementHierarchy.rows(List.of(HEADER, CASH, BANK),
				Map.of(2L, money("5.00"), 3L, money("-5.00")));

		assertThat(rows).extracting(StatementRow::code).containsExactly("1000", "1110", "1120");
		assertThat(rows.get(0).amount()).isEqualByComparingTo("0");
	}

}
```

- [ ] **Step 2: Write the failing API tests**

Create `StatementReportTests.java`:

```java
package com.example.accounting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatementReportTests extends AccountingTestSupport {

	@Autowired
	ReportService reports;

	private long cash;

	private long sales;

	private long rent;

	private long retained;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
		rent = account("6120");
		retained = account("3200");
	}

	private long posted(String date, String... lines) throws Exception {
		long id = draft("maker", date, lines);
		postEntry(id, "checker").andExpect(status().isOk());
		return id;
	}

	private ResultActions income(String from, String to) throws Exception {
		return mvc.perform(get("/api/accounting/reports/income-statement").param("from", from).param("to", to).with(as("u", READ)));
	}

	private ResultActions balanceSheet(String asOf) throws Exception {
		return mvc.perform(get("/api/accounting/reports/balance-sheet").param("asOf", asOf).with(as("u", READ)));
	}

	@Test
	void theIncomeStatementShowsEachSectionByHierarchyAndTheNet() throws Exception {
		posted(YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		posted(YEAR + "-01-20", line(rent, "300.00", "0"), line(cash, "0", "300.00"));
		posted(YEAR + "-02-20", line(account("7100"), "50.00", "0"), line(cash, "0", "50.00"));

		income(YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.from").value(YEAR + "-01-01"))
				.andExpect(jsonPath("$.revenue", hasSize(2)))
				.andExpect(jsonPath("$.revenue[0].code").value("4000"))
				.andExpect(jsonPath("$.revenue[0].depth").value(0))
				.andExpect(jsonPath("$.revenue[0].amount").value("1000.00"))
				.andExpect(jsonPath("$.revenue[1].code").value("4100"))
				.andExpect(jsonPath("$.revenue[1].depth").value(1))
				.andExpect(jsonPath("$.expenses", hasSize(4)))
				.andExpect(jsonPath("$.expenses[0].code").value("6000"))
				.andExpect(jsonPath("$.expenses[0].amount").value("300.00"))
				.andExpect(jsonPath("$.expenses[2].code").value("7000"))
				.andExpect(jsonPath("$.totalRevenue").value("1000.00"))
				.andExpect(jsonPath("$.totalExpenses").value("350.00"))
				.andExpect(jsonPath("$.netProfit").value("650.00"));
	}

	@Test
	void theIncomeStatementCountsOnlyItsRange() throws Exception {
		posted(YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		posted(YEAR + "-02-20", line(rent, "50.00", "0"), line(cash, "0", "50.00"));

		income(YEAR + "-02-01", YEAR + "-02-28")
				.andExpect(jsonPath("$.revenue", hasSize(0)))
				.andExpect(jsonPath("$.totalRevenue").value("0.00"))
				.andExpect(jsonPath("$.netProfit").value("-50.00"));
	}

	@Test
	void draftsAreIgnoredAndAReversedEntryNetsToNothing() throws Exception {
		draft("maker", YEAR + "-01-10", line(cash, "9.00", "0"), line(sales, "0", "9.00"));
		long id = posted(YEAR + "-01-10", line(cash, "50.00", "0"), line(sales, "0", "50.00"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", POST))
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + YEAR + "-01-11\"}")).andExpect(status().isOk());

		income(YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(jsonPath("$.revenue", hasSize(0)))
				.andExpect(jsonPath("$.netProfit").value("0.00"));
	}

	@Test
	void linesRemovedFromADraftAreNotCounted() throws Exception {
		long id = draft("maker", YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("editor", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-01-10", line(cash, "30.00", "0"), line(sales, "0", "30.00"))
						.replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(status().isOk());

		income(YEAR + "-01-01", YEAR + "-12-31").andExpect(jsonPath("$.totalRevenue").value("30.00"));
		balanceSheet(YEAR + "-12-31").andExpect(jsonPath("$.totalAssets").value("30.00"));
	}

	@Test
	void parametersAreChecked() throws Exception {
		income(YEAR + "-02-01", YEAR + "-01-01").andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/reports/income-statement").param("from", YEAR + "-01-01").with(as("u", READ)))
				.andExpect(problem(400, "validation"));
		balanceSheet("nope").andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/reports/income-statement").param("from", YEAR + "-01-01").param("to", YEAR + "-02-01").with(as("u")))
				.andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/reports/balance-sheet").with(as("u"))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void theBalanceSheetBalancesWithAContraAssetAndUnclosedEarnings() throws Exception {
		posted(YEAR + "-01-05", line(cash, "1000.00", "0"), line(account("3100"), "0", "1000.00"));
		posted(YEAR + "-01-10", line(account("6140"), "5.00", "0"), line(account("1290"), "0", "5.00"));

		balanceSheet(YEAR + "-06-30")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.asOf").value(YEAR + "-06-30"))
				.andExpect(jsonPath("$.assets[0].code").value("1000"))
				.andExpect(jsonPath("$.assets[0].amount").value("995.00"))
				.andExpect(jsonPath("$.assets[1].code").value("1100"))
				.andExpect(jsonPath("$.assets[2].code").value("1110"))
				.andExpect(jsonPath("$.assets[2].depth").value(2))
				.andExpect(jsonPath("$.assets[3].code").value("1200"))
				.andExpect(jsonPath("$.assets[3].amount").value("-5.00"))
				.andExpect(jsonPath("$.assets[4].code").value("1290"))
				.andExpect(jsonPath("$.liabilities", hasSize(0)))
				.andExpect(jsonPath("$.equity[0].code").value("3000"))
				.andExpect(jsonPath("$.equity[1].code").value("3100"))
				.andExpect(jsonPath("$.earningsNotYetClosed").value("-5.00"))
				.andExpect(jsonPath("$.totalAssets").value("995.00"))
				.andExpect(jsonPath("$.totalLiabilities").value("0.00"))
				.andExpect(jsonPath("$.totalEquity").value("995.00"))
				.andExpect(jsonPath("$.totalLiabilitiesAndEquity").value("995.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void theBalanceSheetDefaultsToToday() throws Exception {
		mvc.perform(get("/api/accounting/reports/balance-sheet").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.asOf").value(LocalDate.now(ZoneId.of("Asia/Jakarta")).toString()));
	}

	@Test
	void aClosingEntryIsLeftOutOfTheIncomeStatementAndCountedOnTheBalanceSheet() throws Exception {
		posted(YEAR + "-01-10", line(cash, "100.00", "0"), line(sales, "0", "100.00"));
		inTransaction(() -> closeBySql(YEAR, sales, retained));

		income(YEAR + "-01-01", YEAR + "-12-31").andExpect(jsonPath("$.totalRevenue").value("100.00"));
		balanceSheet(YEAR + "-12-31")
				.andExpect(jsonPath("$.equity[1].code").value("3200"))
				.andExpect(jsonPath("$.equity[1].amount").value("100.00"))
				.andExpect(jsonPath("$.earningsNotYetClosed").value("0.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
		balanceSheet(YEAR + "-06-30")
				.andExpect(jsonPath("$.equity", hasSize(0)))
				.andExpect(jsonPath("$.earningsNotYetClosed").value("100.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void tenantsSeeOnlyTheirOwnFigures() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		UUID other = UUID.randomUUID();

		mvc.perform(get("/api/accounting/reports/income-statement").param("from", YEAR + "-01-01").param("to", YEAR + "-12-31")
						.with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$.revenue", hasSize(0)));
		assertThat(inTenantTransaction(other, "o", () -> reports.debitMinusCredit(LocalDate.of(YEAR, 12, 31)))).isEmpty();
		assertThat(inTransaction(() -> reports.debitMinusCredit(LocalDate.of(YEAR, 12, 31)))).isNotEmpty();
	}

	@Test
	void rowLevelSecurityAloneHidesAnotherTenantsLinesFromTheReportTables() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		String query = """
				select count(*) from accounting.journal_line l
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				""";

		assertThat(inTenantTransaction(UUID.randomUUID(), "o", () -> (Number) entityManager.createNativeQuery(query).getSingleResult()).longValue()).isZero();
		assertThat(inTransaction(() -> (Number) entityManager.createNativeQuery(query).getSingleResult()).longValue()).isEqualTo(2);
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='StatementHierarchyTests,StatementReportTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at test compilation, because `StatementHierarchy` does not exist.

- [ ] **Step 4: Add the row and the hierarchy builder**

Create `StatementRow.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code amount} is on the account's normal side and includes its descendants, so a contra-asset shows as negative. */
public record StatementRow(Long accountId, String code, String name, int depth,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal amount) {
}
```

Create `StatementHierarchy.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Turns one section's accounts and balances into rows, each parent before its children. */
final class StatementHierarchy {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	record Node(Long id, String code, String name, Long parentId) {
	}

	private StatementHierarchy() {
	}

	/** A parent outside this section makes the account top-level. Rows that net to zero with no movement below are hidden. */
	static List<StatementRow> rows(List<Node> nodes, Map<Long, BigDecimal> balances) {
		Set<Long> section = nodes.stream().map(Node::id).collect(Collectors.toSet());
		Map<Long, List<Node>> children = new HashMap<>();
		List<Node> roots = new ArrayList<>();
		nodes.stream().sorted(Comparator.comparing(Node::code)).forEach(node -> {
			if (node.parentId() != null && section.contains(node.parentId())) {
				children.computeIfAbsent(node.parentId(), parent -> new ArrayList<>()).add(node);
			}
			else {
				roots.add(node);
			}
		});
		List<StatementRow> rows = new ArrayList<>();
		roots.forEach(root -> add(root, 0, children, balances, rows));
		return rows;
	}

	private static BigDecimal add(Node node, int depth, Map<Long, List<Node>> children, Map<Long, BigDecimal> balances,
			List<StatementRow> rows) {
		int at = rows.size();
		BigDecimal own = balances.getOrDefault(node.id(), ZERO);
		BigDecimal amount = own;
		for (Node child : children.getOrDefault(node.id(), List.of())) {
			amount = amount.add(add(child, depth + 1, children, balances, rows));
		}
		// A descendant added a row only if something moved there, so the row count says whether to show this one.
		if (own.signum() != 0 || rows.size() > at) {
			rows.add(at, new StatementRow(node.id(), node.code(), node.name(), depth, amount));
		}
		return amount;
	}

}
```

- [ ] **Step 5: Add the two report records**

Create `IncomeStatement.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code netProfit} is negative for a loss. */
public record IncomeStatement(LocalDate from, LocalDate to, List<StatementRow> revenue, List<StatementRow> expenses,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalRevenue,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalExpenses,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit) {
}
```

Create `BalanceSheet.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code earningsNotYetClosed} is calculated and is not an account; {@code totalEquity} includes it. */
public record BalanceSheet(LocalDate asOf, List<StatementRow> assets, List<StatementRow> liabilities,
		List<StatementRow> equity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal earningsNotYetClosed,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalAssets,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalLiabilities,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalEquity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalLiabilitiesAndEquity,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference) {
}
```

- [ ] **Step 6: Add the statements to `ReportService`**

Add imports `java.util.HashMap`, `java.util.Map`, `jakarta.persistence.Query`. Add this constant below `ZERO`:

```java
	private static final String LINES_THROUGH = """
			select l.account_id, sum(l.debit) - sum(l.credit)
			from accounting.journal_line l
			join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
			where e.status in ('POSTED', 'REVERSED') and l.deleted_at is null and e.entry_date <= :to
			  and l.tenant_id = cast(nullif(current_setting('app.tenant_id', true), '') as uuid)
			""";

	private static final String BY_ACCOUNT = " group by l.account_id";
```

Add these methods above `balance(...)`:

```java
	/** Leaves out closes and their reversals, so a year reads the same before a close, after it and after a reopen. */
	public IncomeStatement incomeStatement(LocalDate from, LocalDate to) {
		JournalService.checkDates(from, to);
		Map<Long, BigDecimal> found = collect(entityManager
				.createNativeQuery(LINES_THROUGH + " and e.closing_year is null and e.entry_date >= :from" + BY_ACCOUNT)
				.setParameter("to", to).setParameter("from", from));
		List<Account> all = accounts.findAll();
		Section revenue = section(AccountType.REVENUE, all, found);
		Section expenses = section(AccountType.EXPENSE, all, found);
		return new IncomeStatement(from, to, revenue.rows(), expenses.rows(), revenue.total(), expenses.total(),
				revenue.total().subtract(expenses.total()));
	}

	public BalanceSheet balanceSheet(LocalDate requestedAsOf) {
		LocalDate asOf = requestedAsOf != null ? requestedAsOf : LocalDate.now(clock);
		Map<Long, BigDecimal> found = debitMinusCredit(asOf);
		List<Account> all = accounts.findAll();
		Section assets = section(AccountType.ASSET, all, found);
		Section liabilities = section(AccountType.LIABILITY, all, found);
		Section equity = section(AccountType.EQUITY, all, found);
		BigDecimal earnings = section(AccountType.REVENUE, all, found).total()
				.subtract(section(AccountType.EXPENSE, all, found).total());
		BigDecimal totalEquity = equity.total().add(earnings);
		BigDecimal liabilitiesAndEquity = liabilities.total().add(totalEquity);
		return new BalanceSheet(asOf, assets.rows(), liabilities.rows(), equity.rows(), earnings, assets.total(),
				liabilities.total(), totalEquity, liabilitiesAndEquity, assets.total().subtract(liabilitiesAndEquity));
	}

	/** Debit minus credit per account through the date, closing entries included; an account with no lines is absent. */
	public Map<Long, BigDecimal> debitMinusCredit(LocalDate through) {
		return collect(entityManager.createNativeQuery(LINES_THROUGH + BY_ACCOUNT).setParameter("to", through));
	}

	@SuppressWarnings("unchecked")
	private static Map<Long, BigDecimal> collect(Query query) {
		Map<Long, BigDecimal> found = new HashMap<>();
		for (Object[] row : (List<Object[]>) query.getResultList()) {
			found.put(((Number) row[0]).longValue(), money(row[1]));
		}
		return found;
	}

	private static Section section(AccountType type, List<Account> all, Map<Long, BigDecimal> debitMinusCredit) {
		List<StatementHierarchy.Node> nodes = new ArrayList<>();
		Map<Long, BigDecimal> balances = new HashMap<>();
		BigDecimal total = ZERO;
		for (Account account : all) {
			if (account.getType() != type) {
				continue;
			}
			nodes.add(new StatementHierarchy.Node(account.getId(), account.getCode(), account.getName(), account.getParentId()));
			BigDecimal raw = debitMinusCredit.getOrDefault(account.getId(), ZERO);
			BigDecimal balance = type.debitNormal() ? raw : raw.negate();
			balances.put(account.getId(), balance);
			total = total.add(balance);
		}
		return new Section(StatementHierarchy.rows(nodes, balances), total);
	}
```

Add this record just before the final closing brace of the class:

```java
	private record Section(List<StatementRow> rows, BigDecimal total) {
	}
```

- [ ] **Step 7: Add the endpoints to `ReportController`**

```java
	@GetMapping("/income-statement")
	@PreAuthorize("hasAuthority('accounting:read')")
	public IncomeStatement incomeStatement(@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return service.incomeStatement(from, to);
	}

	@GetMapping("/balance-sheet")
	@PreAuthorize("hasAuthority('accounting:read')")
	public BalanceSheet balanceSheet(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		return service.balanceSheet(asOf);
	}
```

- [ ] **Step 8: Run the tests**

Run: `./mvnw -pl backend/accounting -am test -Dtest='StatementHierarchyTests,StatementReportTests,ReportTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 9: Commit**

```bash
git add backend/accounting/src/main/java/com/example/accounting/StatementRow.java backend/accounting/src/main/java/com/example/accounting/StatementHierarchy.java backend/accounting/src/main/java/com/example/accounting/IncomeStatement.java backend/accounting/src/main/java/com/example/accounting/BalanceSheet.java backend/accounting/src/main/java/com/example/accounting/ReportService.java backend/accounting/src/main/java/com/example/accounting/ReportController.java backend/accounting/src/test/java/com/example/accounting/StatementHierarchyTests.java backend/accounting/src/test/java/com/example/accounting/StatementReportTests.java
git commit -m "Add the income statement and balance sheet API"
```

---

### Task 3: Close a year

**Files:**
- Create: `backend/accounting/src/main/java/com/example/accounting/YearEndService.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/YearController.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/YearCloseRequest.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/YearCloseResponse.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/YearStatus.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AuditAction.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AccountingSettings.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/SettingsRepository.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/FiscalPeriodRepository.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalEntryRepository.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/PostingService.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/EntryResponse.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalService.java:121` (the lock order comment)
- Test: `backend/accounting/src/test/java/com/example/accounting/YearEndTestSupport.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/YearCloseTests.java`

**Interfaces:**
- Consumes: `ReportService.debitMinusCredit(LocalDate)`, `JournalEntry.closingYear(Integer)`, `incomeStatement`/`balanceSheet` endpoints from Task 2.
- Produces:
  - `YearEndService.close(int year, long retainedEarningsAccountId): YearCloseResponse`, `YearEndService.status(int year): YearStatus`.
  - `YearCloseResponse(int year, Long entryId, String entryNo, BigDecimal netProfit)` (string in JSON, negative for a loss); `YearStatus(int year, boolean closed, Long entryId, String entryNo)`.
  - `PostingService.postDirectly(JournalEntry entry)`: saves a built draft, numbers and posts it, no maker/checker.
  - `AccountingSettings.lock(): Settings`; `FiscalPeriodRepository.lockYear(int year): List<FiscalPeriod>`.
  - `JournalEntryRepository.findActiveClose(int year): Optional<JournalEntry>`, `countActiveClosesAfter(int year): long`, `countDraftsBetween(LocalDate from, LocalDate to): long`.
  - `EntryResponse.closingYear` (last record component).
  - `POST /api/accounting/years/{year}/close`, `GET /api/accounting/years/{year}`.
  - Test base `YearEndTestSupport`, used by Tasks 4 and 5.

- [ ] **Step 1: Write the test base**

Create `YearEndTestSupport.java`:

```java
package com.example.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class YearEndTestSupport extends AccountingTestSupport {

	static final String CLOSER = "closer";

	long cash;

	long sales;

	long rent;

	long retained;

	@BeforeEach
	void accounts() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
		rent = account("6120");
		retained = account("3200");
	}

	long posted(String date, String... lines) throws Exception {
		long id = draft("maker", date, lines);
		postEntry(id, "checker").andExpect(status().isOk());
		return id;
	}

	long sale(String date, String amount) throws Exception {
		return posted(date, line(cash, amount, "0"), line(sales, "0", amount));
	}

	long rentPaid(String date, String amount) throws Exception {
		return posted(date, line(rent, amount, "0"), line(cash, "0", amount));
	}

	void openPeriods(int year) throws Exception {
		mvc.perform(post("/api/accounting/periods/year/" + year).with(admin())).andExpect(status().isOk());
	}

	ResultActions closeYear(int year) throws Exception {
		return closeYear(year, retained);
	}

	ResultActions closeYear(int year, long account) throws Exception {
		return mvc.perform(post("/api/accounting/years/" + year + "/close").with(as(CLOSER, READ, PERIOD_MANAGE))
				.contentType(MediaType.APPLICATION_JSON).content("{\"retainedEarningsAccountId\": " + account + "}"));
	}

	ResultActions reopenYear(int year, String reason) throws Exception {
		return mvc.perform(post("/api/accounting/years/" + year + "/reopen").with(as(CLOSER, READ, PERIOD_MANAGE))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"" + reason + "\"}"));
	}

	ResultActions entryOf(long id) throws Exception {
		return mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ)));
	}

	String body(ResultActions result) throws Exception {
		return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	long idIn(ResultActions result, String path) throws Exception {
		return ((Number) JsonPath.read(body(result), path)).longValue();
	}

	ResultActions income(String from, String to) throws Exception {
		return mvc.perform(get("/api/accounting/reports/income-statement").param("from", from).param("to", to).with(as("u", READ)));
	}

	ResultActions balanceSheet(String asOf) throws Exception {
		return mvc.perform(get("/api/accounting/reports/balance-sheet").param("asOf", asOf).with(as("u", READ)));
	}

	ResultActions periodsOf(int year) throws Exception {
		return mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(year)).with(as("u", READ)));
	}

	long periodId(int year, int month) throws Exception {
		return idIn(periodsOf(year), "$[" + (month - 1) + "].id");
	}

	void deactivate(long account) {
		inTransaction(() -> sql("update accounting.account set active = false where id = :id", account));
	}

}
```

- [ ] **Step 2: Write the failing close tests**

Create `YearCloseTests.java`:

```java
package com.example.accounting;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YearCloseTests extends YearEndTestSupport {

	@Test
	void aProfitMovesToRetainedEarningsAndEveryMonthCloses() throws Exception {
		sale(YEAR + "-03-10", "1000.00");
		rentPaid(YEAR + "-07-10", "300.00");

		closeYear(YEAR)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.year").value(YEAR))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000003"))
				.andExpect(jsonPath("$.netProfit").value("700.00"));

		long entry = idIn(mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u", READ))), "$.entryId");
		entryOf(entry)
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.closingYear").value(YEAR))
				.andExpect(jsonPath("$.date").value(YEAR + "-12-31"))
				.andExpect(jsonPath("$.description").value("Closing entry for " + YEAR))
				.andExpect(jsonPath("$.postedByName").value("closer-name"))
				.andExpect(jsonPath("$.lines", hasSize(3)))
				.andExpect(jsonPath("$.lines[0].accountId").value(sales))
				.andExpect(jsonPath("$.lines[0].debit").value("1000.00"))
				.andExpect(jsonPath("$.lines[1].accountId").value(rent))
				.andExpect(jsonPath("$.lines[1].credit").value("300.00"))
				.andExpect(jsonPath("$.lines[2].accountId").value(retained))
				.andExpect(jsonPath("$.lines[2].credit").value("700.00"));
		periodsOf(YEAR).andExpect(jsonPath("$[*].status", everyItem(is("CLOSED"))));
		mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u", READ)))
				.andExpect(jsonPath("$.closed").value(true))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000003"));

		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'YEAR_CLOSE'").getSingleResult());
		assertThat(details).contains("\"year\": " + YEAR, "JE-" + YEAR + "-000003", "700.00", "3200");
	}

	@Test
	void aLossIsADebitOnRetainedEarnings() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		rentPaid(YEAR + "-07-10", "300.00");

		long entry = idIn(closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("-200.00")), "$.entryId");

		entryOf(entry)
				.andExpect(jsonPath("$.lines[2].accountId").value(retained))
				.andExpect(jsonPath("$.lines[2].debit").value("200.00"));
	}

	@Test
	void anAccountRunningAgainstItsNormalSideGetsTheOtherSide() throws Exception {
		long otherIncome = account("4900");
		sale(YEAR + "-03-10", "100.00");
		posted(YEAR + "-04-10", line(otherIncome, "30.00", "0"), line(cash, "0", "30.00"));

		long entry = idIn(closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("70.00")), "$.entryId");

		entryOf(entry)
				.andExpect(jsonPath("$.lines[0].accountId").value(sales))
				.andExpect(jsonPath("$.lines[0].debit").value("100.00"))
				.andExpect(jsonPath("$.lines[1].accountId").value(otherIncome))
				.andExpect(jsonPath("$.lines[1].credit").value("30.00"))
				.andExpect(jsonPath("$.lines[2].credit").value("70.00"));
	}

	@Test
	void aZeroNetHasNoRetainedEarningsLine() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		rentPaid(YEAR + "-07-10", "100.00");

		long entry = idIn(closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("0.00")), "$.entryId");

		entryOf(entry).andExpect(jsonPath("$.lines", hasSize(2)));
	}

	@Test
	void theMakerOfAnEntryMayCloseTheYearWithMakerCheckerOn() throws Exception {
		long id = draft(CLOSER, YEAR + "-03-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		postEntry(id, "checker").andExpect(status().isOk());

		closeYear(YEAR).andExpect(status().isOk());
	}

	@Test
	void linesRemovedFromADraftAreNotClosed() throws Exception {
		long id = draft("maker", YEAR + "-03-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/accounting/journal-entries/" + id)
				.with(as("editor", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-03-10", line(cash, "30.00", "0"), line(sales, "0", "30.00"))
						.replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(status().isOk());

		closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("30.00"));
	}

	@Test
	void aClosedDecemberIsReopenedForTheCloseAndClosedMonthsKeepTheirCloser() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		mvc.perform(post("/api/accounting/periods/" + periodId(YEAR, 3) + "/close").with(as("early", PERIOD_MANAGE))).andExpect(status().isOk());
		mvc.perform(post("/api/accounting/periods/" + periodId(YEAR, 12) + "/close").with(as("early", PERIOD_MANAGE))).andExpect(status().isOk());

		closeYear(YEAR).andExpect(status().isOk());

		periodsOf(YEAR)
				.andExpect(jsonPath("$[2].closedByName").value("early-name"))
				.andExpect(jsonPath("$[0].closedByName").value("closer-name"))
				.andExpect(jsonPath("$[11].closedByName").value("closer-name"));
		Number monthRows = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'PERIOD_CLOSE'").getSingleResult());
		assertThat(monthRows.longValue()).as("the close writes no PERIOD_CLOSE rows of its own").isEqualTo(2);
	}

	@Test
	void theBalanceSheetIsUnchangedByACloseAndNextYearStartsFromZero() throws Exception {
		sale(YEAR + "-03-10", "1000.00");
		rentPaid(YEAR + "-07-10", "300.00");
		String june = body(balanceSheet(YEAR + "-06-30"));
		balanceSheet(YEAR + "-12-31").andExpect(jsonPath("$.earningsNotYetClosed").value("700.00"));

		closeYear(YEAR).andExpect(status().isOk());

		assertThat(body(balanceSheet(YEAR + "-06-30"))).isEqualTo(june);
		balanceSheet(YEAR + "-12-31")
				.andExpect(jsonPath("$.earningsNotYetClosed").value("0.00"))
				.andExpect(jsonPath("$.totalEquity").value("700.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
		income(YEAR + "-01-01", YEAR + "-12-31").andExpect(jsonPath("$.netProfit").value("700.00"));

		int next = YEAR + 1;
		openPeriods(next);
		sale(next + "-02-01", "50.00");
		closeYear(next).andExpect(jsonPath("$.netProfit").value("50.00"));
		balanceSheet(next + "-12-31").andExpect(jsonPath("$.totalEquity").value("750.00"));
	}

	@Test
	void aYearWithNoPeriodsOrAnIncompleteSetIsRefused() throws Exception {
		closeYear(1999).andExpect(problem(422, "no-period"));
		inTransaction(() -> insertPeriod(1998, 1));
		closeYear(1998).andExpect(problem(422, "no-period"));
	}

	@Test
	void draftsInTheYearBlockTheCloseAndAreCounted() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		balancedDraft("maker", YEAR + "-05-01");
		balancedDraft("maker", YEAR + "-06-01");

		closeYear(YEAR).andExpect(problem(409, "year-has-drafts")).andExpect(jsonPath("$.detail", containsString("the 2 draft")));
		periodsOf(YEAR).andExpect(jsonPath("$[0].status").value("OPEN"));
	}

	@Test
	void anEarlierYearWithABalanceMustCloseFirst() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(earlier + "-05-01", "40.00");
		sale(YEAR + "-05-01", "60.00");

		closeYear(YEAR).andExpect(problem(409, "earlier-year-open")).andExpect(jsonPath("$.detail", containsString("4100")));

		closeYear(earlier).andExpect(jsonPath("$.netProfit").value("40.00"));
		closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("60.00"));
	}

	@Test
	void aYearWithNothingToCloseDoesNotBlockTheNext() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		posted(earlier + "-05-01", line(cash, "40.00", "0"), line(account("3100"), "0", "40.00"));
		sale(YEAR + "-05-01", "60.00");

		closeYear(earlier).andExpect(problem(409, "nothing-to-close"));
		periodsOf(earlier).andExpect(jsonPath("$[0].status").value("OPEN"));
		closeYear(YEAR).andExpect(status().isOk());
	}

	@Test
	void aSecondCloseSaysTheYearIsClosed() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		closeYear(YEAR).andExpect(status().isOk());

		closeYear(YEAR).andExpect(problem(409, "year-closed"));
	}

	@Test
	void aDeactivatedAccountWithABalanceIsNamed() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		deactivate(sales);

		closeYear(YEAR).andExpect(problem(422, "account-not-postable")).andExpect(jsonPath("$.detail", containsString("4100")));
	}

	@Test
	void theRetainedEarningsAccountMustBeUsableEquity() throws Exception {
		sale(YEAR + "-03-10", "100.00");

		closeYear(YEAR, cash).andExpect(problem(422, "bad-retained-earnings-account"));
		closeYear(YEAR, account("3000")).andExpect(problem(422, "bad-retained-earnings-account"));
		closeYear(YEAR, 999999999L).andExpect(problem(422, "bad-retained-earnings-account"));
		deactivate(retained);
		closeYear(YEAR).andExpect(problem(422, "bad-retained-earnings-account"));
	}

	@Test
	void anotherTenantsAccountIsNotARetainedEarningsAccount() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		UUID other = UUID.randomUUID();
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
		String accounts = mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ))).andReturn().getResponse().getContentAsString();
		List<Number> theirs = JsonPath.read(accounts, "$[?(@.code=='3200')].id");

		closeYear(YEAR, theirs.get(0).longValue()).andExpect(problem(422, "bad-retained-earnings-account"));
	}

	@Test
	void permissionsAndBodyAreChecked() throws Exception {
		mvc.perform(post("/api/accounting/years/" + YEAR + "/close").with(as("b", READ, WRITE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"retainedEarningsAccountId\": " + retained + "}"))
				.andExpect(problem(403, "forbidden"));
		mvc.perform(post("/api/accounting/years/" + YEAR + "/close").with(as(CLOSER, PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u"))).andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u", READ)))
				.andExpect(jsonPath("$.closed").value(false))
				.andExpect(jsonPath("$.entryId").isEmpty());
	}

	@Test
	void aCompanyNotSetUpIsToldSo() throws Exception {
		mvc.perform(post("/api/accounting/years/" + YEAR + "/close").with(asTenant(UUID.randomUUID(), "o", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"retainedEarningsAccountId\": 1}"))
				.andExpect(problem(422, "not-initialized"));
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='YearCloseTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, with 404 or 500 responses from the missing `/api/accounting/years` endpoints.

- [ ] **Step 4: Add the audit actions and the records**

In `AuditAction.java`:

```java
	SETUP, POST, REVERSE, PERIOD_CLOSE, PERIOD_REOPEN, SETTING_CHANGE, ACCOUNT_CHANGE, YEAR_CLOSE, YEAR_REOPEN
```

Create `YearCloseRequest.java`:

```java
package com.example.accounting;

import jakarta.validation.constraints.NotNull;

public record YearCloseRequest(@NotNull Long retainedEarningsAccountId) {
}
```

Create `YearCloseResponse.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

/** {@code netProfit} is negative for a loss. */
public record YearCloseResponse(int year, Long entryId, String entryNo,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal netProfit) {
}
```

Create `YearStatus.java`:

```java
package com.example.accounting;

/** {@code entryId} and {@code entryNo} name the active close, and are null when the year has none. */
public record YearStatus(int year, boolean closed, Long entryId, String entryNo) {
}
```

- [ ] **Step 5: Add the locks and queries**

In `SettingsRepository.java`, add imports `jakarta.persistence.LockModeType`, `org.springframework.data.jpa.repository.Lock`, `org.springframework.data.jpa.repository.Query`, and:

```java
	/** Each tenant has one row, and Hibernate adds the tenant to this query. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select s from Settings s")
	Optional<Settings> lockCurrent();
```

In `AccountingSettings.java`, replace `require()` and add `lock()`:

```java
	Settings require() {
		return current().orElseThrow(AccountingSettings::notInitialized);
	}

	/** Closing and reopening a year start here, so they run one at a time in a tenant. */
	Settings lock() {
		return repository.lockCurrent().orElseThrow(AccountingSettings::notInitialized);
	}

	private static AccountingException notInitialized() {
		return AccountingException.unprocessable("not-initialized", "Accounting has not been set up for this company yet");
	}
```

In `FiscalPeriodRepository.java`, add:

```java
	/** A post holds its period FOR SHARE, so it finishes first and is counted, or waits and then sees CLOSED. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from FiscalPeriod p where p.year = :year order by p.month")
	List<FiscalPeriod> lockYear(@Param("year") int year);
```

In `JournalEntryRepository.java`, add imports `java.time.LocalDate` and:

```java
	/** A reversed close is REVERSED and its reversal has reversalOf set, so neither matches. */
	@Query("""
			select e from JournalEntry e where e.closingYear = :year
			and e.status = com.example.accounting.EntryStatus.POSTED and e.reversalOf is null
			""")
	Optional<JournalEntry> findActiveClose(@Param("year") int year);

	@Query("""
			select count(e) from JournalEntry e where e.closingYear > :year
			and e.status = com.example.accounting.EntryStatus.POSTED and e.reversalOf is null
			""")
	long countActiveClosesAfter(@Param("year") int year);

	@Query("""
			select count(e) from JournalEntry e where e.status = com.example.accounting.EntryStatus.DRAFT
			and e.entryDate between :from and :to
			""")
	long countDraftsBetween(@Param("from") LocalDate from, @Param("to") LocalDate to);
```

In `JournalService.java`, replace the comment above `loadForChange`:

```java
	/**
	 * Lock order everywhere is entry, then period, then sequence; closing a period takes no entry locks, so no cycle. Year
	 * actions take the settings row before any of these.
	 */
```

- [ ] **Step 6: Add `postDirectly` to `PostingService`**

Add above `lockOpenPeriod`:

```java
	/** Numbers and posts an entry the system built; no maker/checker. The caller holds its period, which must be open. */
	public void postDirectly(JournalEntry entry) {
		Actor actor = Actor.current();
		// Lines cannot be added to a posted entry, so it goes in as a draft first.
		entries.saveAndFlush(entry);
		assignNumberAndPost(entry, actor);
	}
```

- [ ] **Step 7: Add `closingYear` to `EntryResponse`**

Add `Integer closingYear` as the last record component, after `boolean posterBlocked`, and pass `entry.getClosingYear()` as the last argument in `from(JournalEntry, boolean)`.

- [ ] **Step 8: Write the service**

Create `YearEndService.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/** Closes a year into retained earnings. Every refusal is checked here, before the database guards would see it. */
@Service
@Transactional
public class YearEndService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	private final JournalEntryRepository entries;

	private final AccountRepository accounts;

	private final FiscalPeriodRepository periods;

	private final AccountingSettings settings;

	private final ReportService reports;

	private final JournalService journal;

	private final PostingService posting;

	private final AuditTrail audit;

	private final Clock clock;

	YearEndService(JournalEntryRepository entries, AccountRepository accounts, FiscalPeriodRepository periods,
			AccountingSettings settings, ReportService reports, JournalService journal, PostingService posting,
			AuditTrail audit, Clock clock) {
		this.entries = entries;
		this.accounts = accounts;
		this.periods = periods;
		this.settings = settings;
		this.reports = reports;
		this.journal = journal;
		this.posting = posting;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public YearStatus status(int year) {
		return entries.findActiveClose(year)
				.map(close -> new YearStatus(year, true, close.getId(), close.getEntryNo()))
				.orElse(new YearStatus(year, false, null, null));
	}

	public YearCloseResponse close(int year, long retainedEarningsAccountId) {
		settings.lock();
		List<FiscalPeriod> months = periods.lockYear(year);
		if (months.size() != 12) {
			throw AccountingException.unprocessable("no-period", "The 12 periods of " + year + " do not exist");
		}
		if (entries.findActiveClose(year).isPresent()) {
			throw AccountingException.conflict("year-closed", year + " is already closed");
		}
		LocalDate lastDay = LocalDate.of(year, 12, 31);
		LocalDate firstDay = LocalDate.of(year, 1, 1);
		long drafts = entries.countDraftsBetween(firstDay, lastDay);
		if (drafts > 0) {
			throw AccountingException.conflict("year-has-drafts",
					"Post or delete the " + drafts + " draft entries dated in " + year + " first");
		}
		List<Account> incomeAccounts = accounts.findAll().stream()
				.filter(account -> account.getType() == AccountType.REVENUE || account.getType() == AccountType.EXPENSE)
				.sorted(Comparator.comparing(Account::getCode))
				.toList();
		Map<Long, BigDecimal> before = reports.debitMinusCredit(firstDay.minusDays(1));
		incomeAccounts.stream().filter(account -> before.getOrDefault(account.getId(), ZERO).signum() != 0).findFirst()
				.ifPresent(account -> {
					throw AccountingException.conflict("earlier-year-open",
							"Close the earlier year first: account " + account.getCode() + " has a balance dated before " + firstDay);
				});
		Map<Long, BigDecimal> through = reports.debitMinusCredit(lastDay);
		List<Account> toClose = incomeAccounts.stream()
				.filter(account -> through.getOrDefault(account.getId(), ZERO).signum() != 0)
				.toList();
		if (toClose.isEmpty()) {
			throw AccountingException.conflict("nothing-to-close", "No revenue or expense account has a balance in " + year);
		}
		journal.checkAccountsUsable(toClose.stream().map(Account::getId).toList());
		Account retained = accounts.findById(retainedEarningsAccountId)
				.filter(account -> account.getType() == AccountType.EQUITY && account.isPostable() && account.isActive())
				.orElseThrow(() -> AccountingException.unprocessable("bad-retained-earnings-account",
						"Retained earnings must be a postable, active equity account"));

		Actor actor = Actor.current();
		Instant now = clock.instant();
		FiscalPeriod december = months.get(11);
		if (december.getStatus() == PeriodStatus.CLOSED) {
			// The posting guard needs an open period; this flush puts OPEN in the database before the post.
			december.reopen();
			periods.saveAndFlush(december);
		}
		JournalEntry closing = new JournalEntry(lastDay, december.getId(), null, "Closing entry for " + year, actor, now);
		closing.closingYear(year);
		BigDecimal net = ZERO;
		for (Account account : toClose) {
			BigDecimal balance = through.get(account.getId());
			closing.addLine(account.getId(), towardZero(balance), null);
			net = net.subtract(balance);
		}
		if (net.signum() != 0) {
			// A profit is a credit and a loss a debit, the same sides as a positive and a negative balance above.
			closing.addLine(retained.getId(), towardZero(net), null);
		}
		posting.postDirectly(closing);
		months.stream().filter(month -> month.getStatus() == PeriodStatus.OPEN).forEach(month -> month.close(actor, now));
		audit.record(AuditAction.YEAR_CLOSE, "journal_entry", closing.getId(), Map.of("year", year, "entryNo", closing.getEntryNo(),
				"netProfit", net, "retainedEarningsAccount", retained.getCode()));
		return new YearCloseResponse(year, closing.getId(), closing.getEntryNo(), net);
	}

	/** A credit for a positive amount and a debit for a negative one: the side that takes a debit balance to zero. */
	private static Side towardZero(BigDecimal amount) {
		return amount.signum() > 0 ? new Side(ZERO, amount) : new Side(amount.negate(), ZERO);
	}

}
```

- [ ] **Step 9: Write the controller**

Create `YearController.java`:

```java
package com.example.accounting;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/years")
public class YearController {

	private final YearEndService service;

	public YearController(YearEndService service) {
		this.service = service;
	}

	@GetMapping("/{year}")
	@PreAuthorize("hasAuthority('accounting:read')")
	public YearStatus status(@PathVariable int year) {
		return service.status(year);
	}

	@PostMapping("/{year}/close")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public YearCloseResponse close(@PathVariable int year, @Valid @RequestBody YearCloseRequest request) {
		return service.close(year, request.retainedEarningsAccountId());
	}

}
```

- [ ] **Step 10: Run the tests**

Run: `./mvnw -pl backend/accounting -am test -Dtest='YearCloseTests,PostingTests,JournalDraftTests,AuditApiTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 11: Commit**

```bash
git add backend/accounting/src/main/java/com/example/accounting/YearEndService.java backend/accounting/src/main/java/com/example/accounting/YearController.java backend/accounting/src/main/java/com/example/accounting/YearCloseRequest.java backend/accounting/src/main/java/com/example/accounting/YearCloseResponse.java backend/accounting/src/main/java/com/example/accounting/YearStatus.java backend/accounting/src/main/java/com/example/accounting/AuditAction.java backend/accounting/src/main/java/com/example/accounting/AccountingSettings.java backend/accounting/src/main/java/com/example/accounting/SettingsRepository.java backend/accounting/src/main/java/com/example/accounting/FiscalPeriodRepository.java backend/accounting/src/main/java/com/example/accounting/JournalEntryRepository.java backend/accounting/src/main/java/com/example/accounting/PostingService.java backend/accounting/src/main/java/com/example/accounting/EntryResponse.java backend/accounting/src/main/java/com/example/accounting/JournalService.java backend/accounting/src/test/java/com/example/accounting/YearEndTestSupport.java backend/accounting/src/test/java/com/example/accounting/YearCloseTests.java
git commit -m "Close a year into retained earnings"
```

---

### Task 4: Reopen a year, and the rules that protect a close

**Files:**
- Modify: `backend/accounting/src/main/java/com/example/accounting/YearEndService.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/YearController.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/PostingService.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/PeriodService.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/YearReopenTests.java`

**Interfaces:**
- Consumes: `YearEndTestSupport`, `findActiveClose`, `countActiveClosesAfter`, `settings.lock()` from Task 3.
- Produces: `YearEndService.reopen(int year, String reason): YearStatus`; `POST /api/accounting/years/{year}/reopen` with `ReopenRequest`; `PostingService.postReversal(JournalEntry original, LocalDate date, FiscalPeriod period): JournalEntry`; 409 `closing-entry` from `POST /journal-entries/{id}/reverse`; 409 `year-closed` from `POST /periods/{id}/reopen`.

- [ ] **Step 1: Write the failing tests**

Create `YearReopenTests.java`:

```java
package com.example.accounting;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YearReopenTests extends YearEndTestSupport {

	private static final String LATE_INVOICE = "late invoice";

	private long closeEntry(int year) throws Exception {
		return idIn(closeYear(year), "$.entryId");
	}

	private long count(String sql) {
		return inTransaction(() -> ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
	}

	@Test
	void reopeningReversesTheCloseOpensDecemberAndIsAudited() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		long close = closeEntry(YEAR);

		reopenYear(YEAR, LATE_INVOICE)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closed").value(false));

		long reversal = idIn(entryOf(close).andExpect(jsonPath("$.status").value("REVERSED")), "$.reversedBy");
		entryOf(reversal)
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.closingYear").value(YEAR))
				.andExpect(jsonPath("$.date").value(YEAR + "-12-31"))
				.andExpect(jsonPath("$.reversalOf").value(close));
		periodsOf(YEAR)
				.andExpect(jsonPath("$[11].status").value("OPEN"))
				.andExpect(jsonPath("$[10].status").value("CLOSED"))
				.andExpect(jsonPath("$[0].status").value("CLOSED"));
		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'YEAR_REOPEN'").getSingleResult());
		assertThat(details).contains(LATE_INVOICE, "\"year\": " + YEAR, "reversalEntryNo", "JE-" + YEAR + "-000002", "JE-" + YEAR + "-000003");
	}

	@Test
	void figuresReadTheSameBeforeACloseAfterItAndAfterAReopen() throws Exception {
		sale(YEAR + "-03-10", "1000.00");
		rentPaid(YEAR + "-07-10", "300.00");
		String incomeBefore = body(income(YEAR + "-01-01", YEAR + "-12-31"));
		String juneBefore = body(balanceSheet(YEAR + "-06-30"));
		String yearEndBefore = body(balanceSheet(YEAR + "-12-31"));

		closeYear(YEAR).andExpect(status().isOk());
		assertThat(body(income(YEAR + "-01-01", YEAR + "-12-31"))).isEqualTo(incomeBefore);
		assertThat(body(balanceSheet(YEAR + "-06-30"))).isEqualTo(juneBefore);
		String yearEndClosed = body(balanceSheet(YEAR + "-12-31"));
		assertThat((String) JsonPath.read(yearEndClosed, "$.difference")).isEqualTo("0.00");
		assertThat((String) JsonPath.read(yearEndClosed, "$.totalEquity")).isEqualTo(JsonPath.read(yearEndBefore, "$.totalEquity"));
		assertThat((String) JsonPath.read(yearEndClosed, "$.totalAssets")).isEqualTo(JsonPath.read(yearEndBefore, "$.totalAssets"));

		reopenYear(YEAR, LATE_INVOICE).andExpect(status().isOk());
		assertThat(body(income(YEAR + "-01-01", YEAR + "-12-31"))).isEqualTo(incomeBefore);
		assertThat(body(balanceSheet(YEAR + "-06-30"))).isEqualTo(juneBefore);
		assertThat(body(balanceSheet(YEAR + "-12-31"))).isEqualTo(yearEndBefore);
	}

	@Test
	void aLateInvoiceEndsUpInTheSecondCloseAndTheLedgerKeepsAllOfIt() throws Exception {
		sale(YEAR + "-03-10", "1000.00");
		closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("1000.00"));
		reopenYear(YEAR, LATE_INVOICE).andExpect(status().isOk());

		rentPaid(YEAR + "-12-20", "200.00");
		closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("800.00"));

		balanceSheet(YEAR + "-12-31")
				.andExpect(jsonPath("$.equity[1].code").value("3200"))
				.andExpect(jsonPath("$.equity[1].amount").value("800.00"))
				.andExpect(jsonPath("$.earningsNotYetClosed").value("0.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
		assertThat(count("select count(*) from accounting.journal_entry where closing_year = " + YEAR)).isEqualTo(3);
		assertThat(count("select count(*) from accounting.journal_entry where closing_year = " + YEAR + " and status = 'REVERSED'")).isEqualTo(1);
	}

	@Test
	void anIncomeStatementAcrossAClosedYearAndTheNextShowsRealActivityOnly() throws Exception {
		int next = YEAR + 1;
		openPeriods(next);
		sale(YEAR + "-03-10", "100.00");
		closeYear(YEAR).andExpect(status().isOk());
		sale(next + "-02-10", "20.00");

		income(YEAR + "-01-01", next + "-12-31")
				.andExpect(jsonPath("$.totalRevenue").value("120.00"))
				.andExpect(jsonPath("$.netProfit").value("120.00"));
	}

	@Test
	void yearsReopenInReverseOrder() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(earlier + "-05-01", "40.00");
		sale(YEAR + "-05-01", "60.00");
		closeYear(earlier).andExpect(status().isOk());
		closeYear(YEAR).andExpect(status().isOk());

		reopenYear(earlier, LATE_INVOICE).andExpect(problem(409, "later-year-closed"));
		reopenYear(YEAR, LATE_INVOICE).andExpect(status().isOk());
		reopenYear(earlier, LATE_INVOICE).andExpect(status().isOk());
	}

	@Test
	void aYearWithNoActiveCloseCannotBeReopened() throws Exception {
		reopenYear(YEAR, LATE_INVOICE).andExpect(problem(409, "year-not-closed"));
		sale(YEAR + "-03-10", "100.00");
		closeYear(YEAR).andExpect(status().isOk());
		reopenYear(YEAR, LATE_INVOICE).andExpect(status().isOk());

		reopenYear(YEAR, LATE_INVOICE).andExpect(problem(409, "year-not-closed"));
	}

	@Test
	void aDeactivatedAccountOnTheCloseIsNamedAndCanBeReactivated() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		closeYear(YEAR).andExpect(status().isOk());
		deactivate(retained);

		reopenYear(YEAR, LATE_INVOICE).andExpect(problem(422, "account-not-postable"))
				.andExpect(jsonPath("$.detail", containsString("3200")));
		mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u", READ))).andExpect(jsonPath("$.closed").value(true));

		inTransaction(() -> sql("update accounting.account set active = true where id = :id", retained));
		reopenYear(YEAR, LATE_INVOICE).andExpect(status().isOk());
	}

	@Test
	void reopenNeedsAReasonAndThePermission() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		closeYear(YEAR).andExpect(status().isOk());

		reopenYear(YEAR, "  ").andExpect(problem(400, "validation"));
		mvc.perform(post("/api/accounting/years/" + YEAR + "/reopen").with(as("b", READ, WRITE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}"))
				.andExpect(problem(403, "forbidden"));
	}

	@Test
	void onlyReopenUndoesAClose() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		long close = closeEntry(YEAR);
		String reverse = "{\"date\": \"" + (YEAR + 1) + "-01-05\"}";
		openPeriods(YEAR + 1);

		mvc.perform(post("/api/accounting/journal-entries/" + close + "/reverse").with(as("checker", READ, POST))
						.contentType(MediaType.APPLICATION_JSON).content(reverse))
				.andExpect(problem(409, "closing-entry"));

		reopenYear(YEAR, LATE_INVOICE).andExpect(status().isOk());
		long reversal = idIn(entryOf(close), "$.reversedBy");
		mvc.perform(post("/api/accounting/journal-entries/" + reversal + "/reverse").with(as("checker", READ, POST))
						.contentType(MediaType.APPLICATION_JSON).content(reverse))
				.andExpect(problem(409, "closing-entry"));
		mvc.perform(post("/api/accounting/journal-entries/" + close + "/reverse").with(as("checker", READ, POST))
						.contentType(MediaType.APPLICATION_JSON).content(reverse))
				.andExpect(problem(409, "closing-entry"));
	}

	@Test
	void aMonthOfAClosedYearCannotBeReopenedUntilTheYearIs() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		closeYear(YEAR).andExpect(status().isOk());
		String body = "{\"reason\": \"x\"}";

		mvc.perform(post("/api/accounting/periods/" + periodId(YEAR, 3) + "/reopen").with(as(CLOSER, PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(problem(409, "year-closed"));

		reopenYear(YEAR, LATE_INVOICE).andExpect(status().isOk());
		mvc.perform(post("/api/accounting/periods/" + periodId(YEAR, 3) + "/reopen").with(as(CLOSER, PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content(body))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN"));
	}

}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='YearReopenTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL; the reopen endpoint is missing and `closing-entry` is not refused.

- [ ] **Step 3: Refactor `PostingService.reverse` and refuse closing entries**

Replace `reverse(...)` with:

```java
	public ReverseResponse reverse(long id, ReverseRequest request) {
		Actor actor = Actor.current();
		settings.require();
		JournalEntry original = journal.loadForChange(id);
		if (original.getClosingYear() != null) {
			throw AccountingException.conflict("closing-entry", "A closing entry is undone by reopening its year");
		}
		if (original.getStatus() == EntryStatus.DRAFT) {
			throw AccountingException.conflict("not-draft", "Only a posted entry can be reversed");
		}
		if (original.getStatus() == EntryStatus.REVERSED) {
			throw AccountingException.conflict("already-reversed", "This entry has already been reversed");
		}
		LocalDate date = request.date() != null ? request.date() : LocalDate.now(clock);
		if (date.isBefore(original.getEntryDate())) {
			throw AccountingException.badRequest("validation", "A reversal cannot be dated before the entry it reverses");
		}
		// The guard on journal_line would otherwise reject these lines with an unexplained 500.
		journal.checkAccountsUsable(original.getLines().stream().map(JournalLine::getAccountId).toList());
		FiscalPeriod period = lockOpenPeriod(date);

		JournalEntry reversal = postReversal(original, date, period);
		audit.record(AuditAction.REVERSE, "journal_entry", original.getId(),
				Map.of("entryNo", original.getEntryNo(), "reversalEntryNo", reversal.getEntryNo()));

		Long draftId = Boolean.TRUE.equals(request.copyToDraft()) ? copyToDraft(original, date, period, actor, clock.instant()) : null;
		return new ReverseResponse(EntryResponse.from(reversal), draftId);
	}

	/** Posts the mirror entry and marks the original. The caller has checked the accounts and holds the period, which is open. */
	public JournalEntry postReversal(JournalEntry original, LocalDate date, FiscalPeriod period) {
		JournalEntry reversal = new JournalEntry(date, period.getId(), original.getReference(),
				"Reversal of " + original.getEntryNo(), Actor.current(), clock.instant());
		reversal.reversalOf(original.getId());
		reversal.closingYear(original.getClosingYear());
		original.getLines().forEach(line -> reversal.addLine(line.getAccountId(), line.side().swapped(), line.getMemo()));
		postDirectly(reversal);

		original.markReversed(reversal.getId());
		entries.saveAndFlush(original);
		return reversal;
	}
```

Keep the `java.time.Instant` import: `copyToDraft` still uses it.

- [ ] **Step 4: Refuse reopening a month of a closed year**

In `PeriodService.java`, add a `JournalEntryRepository entries` field and constructor parameter (first after `periods`), and in `reopen`, after the `PeriodStatus.OPEN` early return:

```java
		if (entries.findActiveClose(period.getYear()).isPresent()) {
			throw AccountingException.conflict("year-closed", "Reopen the year " + period.getYear() + " first");
		}
```

- [ ] **Step 5: Add `reopen` to `YearEndService`**

Add above `towardZero`:

```java
	/** The other months stay closed until reopened one by one, which the year having no active close now allows. */
	public YearStatus reopen(int year, String reason) {
		settings.lock();
		JournalEntry active = entries.findActiveClose(year)
				.orElseThrow(() -> AccountingException.conflict("year-not-closed", year + " has no close to undo"));
		if (entries.countActiveClosesAfter(year) > 0) {
			throw AccountingException.conflict("later-year-closed", "Reopen the later years first");
		}
		JournalEntry closing = journal.loadForChange(active.getId());
		journal.checkAccountsUsable(closing.getLines().stream().map(JournalLine::getAccountId).toList());
		FiscalPeriod december = periods.lockForClosing(closing.getPeriodId())
				.orElseThrow(() -> new AccountingNotFoundException("Period " + closing.getPeriodId()));
		// The posting guard needs an open period; this flush puts OPEN in the database before the post.
		december.reopen();
		periods.saveAndFlush(december);
		JournalEntry reversal = posting.postReversal(closing, closing.getEntryDate(), december);
		audit.record(AuditAction.YEAR_REOPEN, "journal_entry", closing.getId(), Map.of("year", year, "entryNo", closing.getEntryNo(),
				"reversalEntryNo", reversal.getEntryNo(), "reason", reason));
		return new YearStatus(year, false, null, null);
	}
```

In `YearController.java`, add:

```java
	@PostMapping("/{year}/reopen")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public YearStatus reopen(@PathVariable int year, @Valid @RequestBody ReopenRequest request) {
		return service.reopen(year, request.reason());
	}
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -pl backend/accounting -am test -Dtest='YearReopenTests,YearCloseTests,PeriodTests,PostingTests,PostingConcurrencyTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add backend/accounting/src/main/java/com/example/accounting/YearEndService.java backend/accounting/src/main/java/com/example/accounting/YearController.java backend/accounting/src/main/java/com/example/accounting/PostingService.java backend/accounting/src/main/java/com/example/accounting/PeriodService.java backend/accounting/src/test/java/com/example/accounting/YearReopenTests.java
git commit -m "Reopen a closed year and protect a close from other changes"
```

---

### Task 5: Races, a Phase 1 database, and real tokens

**Files:**
- Test: `backend/accounting/src/test/java/com/example/accounting/YearEndConcurrencyTests.java`
- Modify (test): `backend/accounting/src/test/java/com/example/accounting/LegacyDataMigrationTests.java`
- Modify (test): `backend/app/src/test/java/com/example/app/TokenToPermissionsTests.java`

**Interfaces:**
- Consumes: `YearEndService.close`, `.reopen`, `PostingService.post`, `YearEndTestSupport`.
- Produces: tests only. If a test here exposes a defect, fix it in the service it names and say so in the report.

- [ ] **Step 1: Write the race tests**

Create `YearEndConcurrencyTests.java`:

```java
package com.example.accounting;

import java.util.List;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real concurrent transactions: one holds its work uncommitted until the other has had to wait or has been refused. */
class YearEndConcurrencyTests extends YearEndTestSupport {

	@Autowired
	YearEndService years;

	@Autowired
	PostingService posting;

	/** Runs the work in a transaction and holds it uncommitted until {@code release}. */
	private Future<?> holding(ExecutorService pool, String sub, Runnable work, CountDownLatch held, CountDownLatch release) {
		return pool.submit(() -> inTenantTransaction(tenant, sub, () -> {
			work.run();
			held.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return null;
		}));
	}

	private static void assertHeld(CountDownLatch held) throws Exception {
		assertThat(held.await(30, TimeUnit.SECONDS)).as("the holder got its locks").isTrue();
	}

	@Test
	void aCloseWaitsForAPostInProgressAndCountsIt() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		long id = draft("maker", YEAR + "-12-20", line(cash, "40.00", "0"), line(sales, "0", "40.00"));
		long version = versionOf(id);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> poster = holding(pool, "checker", () -> posting.post(id, version), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> closing = pool.submit(() -> closeYear(YEAR).andReturn().getResponse());
			Thread.sleep(300);
			assertThat(closing.isDone()).as("the close waits for the post's lock on December").isFalse();
			release.countDown();
			poster.get();
			MockHttpServletResponse response = closing.get();
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat((String) JsonPath.read(response.getContentAsString(), "$.netProfit")).isEqualTo("140.00");
		}
	}

	@Test
	void aReversalOrPostRacingACloseWaitsAndIsRefused() throws Exception {
		long earlier = sale(YEAR + "-03-10", "100.00");
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> closing = holding(pool, CLOSER, () -> years.close(YEAR, retained), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> reversing = pool.submit(() -> mvc
					.perform(post("/api/accounting/journal-entries/" + earlier + "/reverse").with(as("checker", READ, POST))
							.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + YEAR + "-06-15\"}"))
					.andReturn().getResponse());
			Thread.sleep(300);
			assertThat(reversing.isDone()).as("the reversal waits for the close's lock on June").isFalse();
			release.countDown();
			closing.get();
			assertThat(reversing.get().getStatus()).isEqualTo(422);
			assertThat(reversing.get().getContentAsString()).contains("period-closed");
		}
		// The known Phase 1 race: a draft saved in the closed year cannot be posted, so it moves no money.
		long late = balancedDraft("maker", YEAR + "-05-01");
		postEntry(late, "checker").andExpect(problem(422, "period-closed"));
	}

	@Test
	void aDoubleCloseIsOneSuccessAndOneEntry() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<MockHttpServletResponse>> both = List.of(pool.submit(() -> {
				go.await();
				return closeYear(YEAR).andReturn().getResponse();
			}), pool.submit(() -> {
				go.await();
				return closeYear(YEAR).andReturn().getResponse();
			}));
			go.countDown();
			assertThat(List.of(both.get(0).get().getStatus(), both.get(1).get().getStatus())).containsExactlyInAnyOrder(200, 409);
		}
		Number closes = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.journal_entry where closing_year = " + YEAR).getSingleResult());
		assertThat(closes.longValue()).isEqualTo(1);
	}

	@Test
	void aCloseWaitingOnAReopenOfTheYearBeforeIsRefused() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(earlier + "-05-01", "40.00");
		sale(YEAR + "-05-01", "60.00");
		closeYear(earlier).andExpect(status().isOk());
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> reopening = holding(pool, CLOSER, () -> years.reopen(earlier, "late"), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> closing = pool.submit(() -> closeYear(YEAR).andReturn().getResponse());
			Thread.sleep(300);
			assertThat(closing.isDone()).as("the close waits on the settings row").isFalse();
			release.countDown();
			reopening.get();
			assertThat(closing.get().getStatus()).isEqualTo(409);
			assertThat(closing.get().getContentAsString()).contains("earlier-year-open");
		}
	}

	@Test
	void aReopenWaitingOnACloseOfTheYearAfterIsRefused() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(earlier + "-05-01", "40.00");
		sale(YEAR + "-05-01", "60.00");
		closeYear(earlier).andExpect(status().isOk());
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> closing = holding(pool, CLOSER, () -> years.close(YEAR, retained), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> reopening = pool.submit(() -> reopenYear(earlier, "late").andReturn().getResponse());
			Thread.sleep(300);
			assertThat(reopening.isDone()).as("the reopen waits on the settings row").isFalse();
			release.countDown();
			closing.get();
			assertThat(reopening.get().getStatus()).isEqualTo(409);
			assertThat(reopening.get().getContentAsString()).contains("later-year-closed");
		}
	}

}
```

- [ ] **Step 2: Add the Phase 1 migration test**

This test runs through raw connections on its own database, like the others in the class, so it closes the year in SQL in the order the service does. In `LegacyDataMigrationTests.java`, add above `assertMismatchesFound`:

```java
	@Test
	void aYearOfPhase1EntriesClosesAfterTheMigrationAndTheGuardsHold() throws Exception {
		String url = phase1Database("legacy_close");
		long[] fixture;
		long december;
		try (Connection legacy = connect(url); Statement statement = legacy.createStatement()) {
			fixture = accountsAndPeriod(statement);
			long original = posted(statement, fixture, "JE-1", null);
			markReversed(statement, original, posted(statement, fixture, "JE-2", String.valueOf(original)));
			posted(statement, fixture, "JE-3", null);
			statement.execute("insert into accounting.fiscal_period (tenant_id, year, month, start_date, end_date, status) select "
					+ TENANT + ", 2026, m, make_date(2026, m, 1), cast(make_date(2026, m, 1) + interval '1 month - 1 day' as date), 'OPEN'"
					+ " from generate_series(1, 12) m where m <> 3");
			december = id(statement, "select id from accounting.fiscal_period where month = 12");
		}

		flyway(url, MigrationVersion.LATEST).migrate();

		try (Connection legacy = connect(url); Statement statement = legacy.createStatement()) {
			assertThat(readmePreCheck(statement)).hasSize(6).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
			legacy.setAutoCommit(false);
			long closing = id(statement, """
					insert into accounting.journal_entry (tenant_id, entry_date, period_id, description, status, closing_year,
					    created_by_sub, created_by_name, created_at, last_modified_by_sub, last_modified_by_name,
					    last_modified_at, version)
					values (%s, '2026-12-31', %d, 'close', 'DRAFT', 2026, 's', 'n', now(), 's', 'n', now(), 0) returning id
					""".formatted(TENANT, december));
			statement.execute("insert into accounting.journal_line (tenant_id, entry_id, line_no, account_id, debit, credit) values ("
					+ TENANT + ", " + closing + ", 1, " + fixture[1] + ", 1, 0), (" + TENANT + ", " + closing + ", 2, " + fixture[0] + ", 0, 1)");
			statement.execute("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-4' where id = " + closing);
			statement.execute("update accounting.fiscal_period set status = 'CLOSED'");
			legacy.commit();
			assertThat(text(statement, "select status from accounting.journal_entry where id = " + closing)).isEqualTo("POSTED");

			statement.execute("update accounting.fiscal_period set status = 'OPEN' where id = " + december);
			assertThatThrownBy(legacy::commit).hasMessageContaining("has an active close");
		}
	}
```

- [ ] **Step 3: Extend the signed-token test**

The demo tenant can be set up only once in this class's database, so these go into the existing test instead of a new one. In `TokenToPermissionsTests.bookkeeperDraftsAccountantPostsAndTenantsStaySeparate`, add these lines immediately before the line `mvc.perform(post("/api/accounting/setup").header(HttpHeaders.AUTHORIZATION, acmeAdmin)).andExpect(status().isOk());`:

```java
		long retained = ((List<Number>) JsonPath.read(accounts, "$[?(@.code=='3200')].id")).get(0).longValue();
		String closePath = "/api/accounting/years/" + year + "/close";
		String closeBody = "{\"retainedEarningsAccountId\": " + retained + "}";
		String reopenPath = "/api/accounting/years/" + year + "/reopen";
		String reopenBody = "{\"reason\": \"late invoice\"}";
		mvc.perform(post(closePath).header(HttpHeaders.AUTHORIZATION, bookkeeper)
				.contentType(MediaType.APPLICATION_JSON).content(closeBody)).andExpect(status().isForbidden());
		mvc.perform(post(closePath).header(HttpHeaders.AUTHORIZATION, viewer)
				.contentType(MediaType.APPLICATION_JSON).content(closeBody)).andExpect(status().isForbidden());
		mvc.perform(post(closePath).header(HttpHeaders.AUTHORIZATION, accountant)
						.contentType(MediaType.APPLICATION_JSON).content(closeBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.netProfit").value("100.00"));
		mvc.perform(get("/api/accounting/years/" + year).header(HttpHeaders.AUTHORIZATION, viewer))
				.andExpect(jsonPath("$.closed").value(true));
		mvc.perform(get("/api/accounting/reports/income-statement").param("from", year + "-01-01").param("to", year + "-12-31")
						.header(HttpHeaders.AUTHORIZATION, viewer))
				.andExpect(jsonPath("$.netProfit").value("100.00"));
		mvc.perform(post(reopenPath).header(HttpHeaders.AUTHORIZATION, bookkeeper)
				.contentType(MediaType.APPLICATION_JSON).content(reopenBody)).andExpect(status().isForbidden());
		mvc.perform(post(reopenPath).header(HttpHeaders.AUTHORIZATION, demoAdmin)
						.contentType(MediaType.APPLICATION_JSON).content(reopenBody))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closed").value(false));

```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -pl backend/accounting -am test -Dtest='YearEndConcurrencyTests,LegacyDataMigrationTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

Run: `./mvnw -pl backend/app -am test -Dtest='TokenToPermissionsTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting/src/test/java/com/example/accounting/YearEndConcurrencyTests.java backend/accounting/src/test/java/com/example/accounting/LegacyDataMigrationTests.java backend/app/src/test/java/com/example/app/TokenToPermissionsTests.java
git commit -m "Test year-end races, a Phase 1 database and signed tokens"
```

---

### Task 6: Reports screens

**Files:**
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/StatementRowDto.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/IncomeStatementDto.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/BalanceSheetDto.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/ReportLine.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/ReportLines.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/Csv.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/AccountingApiClient.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/ReportsViewModel.java`
- Modify: `frontend/accounting/src/main/resources/web/accounting/reports.zul`
- Test: `frontend/accounting/src/test/java/com/example/ui/accounting/ReportLinesTests.java`
- Modify (test): `frontend/accounting/src/test/java/com/example/ui/accounting/CsvTests.java`
- Modify (test): `frontend/accounting/src/test/java/com/example/ui/accounting/AccountingApiClientTests.java`
- Modify (test): `frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java`
- Modify (test): `frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java`

**Interfaces:**
- Consumes: the JSON from Task 2, with the same field names.
- Produces:
  - Public records `StatementRowDto(Long accountId, String code, String name, int depth, BigDecimal amount)`, `IncomeStatementDto(LocalDate from, LocalDate to, List<StatementRowDto> revenue, List<StatementRowDto> expenses, BigDecimal totalRevenue, BigDecimal totalExpenses, BigDecimal netProfit)`, `BalanceSheetDto(LocalDate asOf, List<StatementRowDto> assets, List<StatementRowDto> liabilities, List<StatementRowDto> equity, BigDecimal earningsNotYetClosed, BigDecimal totalAssets, BigDecimal totalLiabilities, BigDecimal totalEquity, BigDecimal totalLiabilitiesAndEquity, BigDecimal difference)`.
  - `AccountingApiClient.incomeStatement(LocalDate from, LocalDate to): IncomeStatementDto`, `.balanceSheet(LocalDate asOf): BalanceSheetDto`. Task 7 uses `incomeStatement`.

- [ ] **Step 1: Write the failing plain-class tests**

Create `ReportLinesTests.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.tuple;

class ReportLinesTests {

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	static IncomeStatementDto income(String net) {
		return new IncomeStatementDto(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31),
				List.of(new StatementRowDto(1L, "4000", "Pendapatan", 0, money("1000.00")),
						new StatementRowDto(2L, "4100", "Penjualan", 1, money("1000.00"))),
				List.of(new StatementRowDto(3L, "6120", "Beban Sewa", 0, money("300.00"))), money("1000.00"), money("300.00"), money(net));
	}

	static BalanceSheetDto balance() {
		return new BalanceSheetDto(LocalDate.of(2026, 6, 30),
				List.of(new StatementRowDto(1L, "1290", "Akumulasi", 0, money("-5.00"))), List.of(),
				List.of(new StatementRowDto(2L, "3100", "Modal", 0, money("10.00"))), money("-15.00"), money("-5.00"), money("0.00"),
				money("-5.00"), money("-5.00"), money("0.00"));
	}

	@Test
	void theIncomeStatementHasSectionsTotalsAndANamedNet() {
		assertThat(ReportLines.of(income("700.00"))).extracting(ReportLine::name, ReportLine::depth, ReportLine::amount).containsExactly(
				tuple("Revenue", 0, null), tuple("Pendapatan", 0, money("1000.00")), tuple("Penjualan", 1, money("1000.00")),
				tuple("Total revenue", 0, money("1000.00")), tuple("Expenses", 0, null), tuple("Beban Sewa", 0, money("300.00")),
				tuple("Total expenses", 0, money("300.00")), tuple("Net profit", 0, money("700.00")));
	}

	@Test
	void aLossIsNamedAndShownAsAPositiveAmount() {
		ReportLine last = ReportLines.of(income("-200.00")).getLast();

		assertThat(last.name()).isEqualTo("Net loss");
		assertThat(last.getAmountText()).isEqualTo("200,00");
	}

	@Test
	void theBalanceSheetEndsEquityWithEarningsAndShowsTheDifference() {
		assertThat(ReportLines.of(balance())).extracting(ReportLine::name, ReportLine::amount).containsExactly(
				tuple("Assets", null), tuple("Akumulasi", money("-5.00")), tuple("Total assets", money("-5.00")),
				tuple("Liabilities", null), tuple("Total liabilities", money("0.00")),
				tuple("Equity", null), tuple("Modal", money("10.00")), tuple("Earnings not yet closed", money("-15.00")),
				tuple("Total equity", money("-5.00")), tuple("Total liabilities and equity", money("-5.00")),
				tuple("Difference", money("0.00")));
	}

	@Test
	void rowsIndentByDepthAndHeadingsAndTotalsAreBold() {
		List<ReportLine> lines = ReportLines.of(income("700.00"));

		assertThat(lines.get(2).getStyle()).isEqualTo("padding-left:16px");
		assertThat(lines.get(0).getStyle()).contains("font-weight:bold");
		assertThat(lines.get(3).getStyle()).contains("font-weight:bold");
		assertThat(lines.get(0).getAmountText()).isEmpty();
	}

}
```

In `CsvTests.java`, add:

```java
	@Test
	void theIncomeStatementListsEachAmountWithItsSectionAndLevel() {
		assertThat(text(Csv.incomeStatement(ReportLinesTests.income("700.00"))).substring(1).split("\r\n")).containsExactly(
				"Income statement,2026-01-01,2026-12-31", "Section,Code,Name,Level,Amount",
				"Revenue,4000,Pendapatan,0,1000.00", "Revenue,4100,Penjualan,1,1000.00", "Revenue,,Total revenue,,1000.00",
				"Expenses,6120,Beban Sewa,0,300.00", "Expenses,,Total expenses,,300.00", "Net,,Net profit,,700.00");
	}

	@Test
	void theBalanceSheetExportsAContraAccountAsNegativeAndTheDifference() {
		String csv = text(Csv.balanceSheet(ReportLinesTests.balance()));

		assertThat(csv).contains("Balance sheet as of,2026-06-30", "Assets,1290,Akumulasi,0,-5.00", "Equity,,Earnings not yet closed,,-15.00",
				"Total,,Difference,,0.00");
	}

	@Test
	void aStatementRowThatLooksLikeAFormulaCannotRun() {
		IncomeStatementDto report = new IncomeStatementDto(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 1, 2),
				List.of(new StatementRowDto(1L, "=1", "=2", 0, money("1.00"))), List.of(), money("1.00"), money("0.00"), money("1.00"));

		assertThat(text(Csv.incomeStatement(report))).contains("'=1,'=2");
	}
```

In `AccountingApiClientTests.java`, add:

```java
	@Test
	void statementsArriveWithTheirRowsAndAmounts() {
		server.expect(requestTo(BASE + "/reports/income-statement?from=2026-01-01&to=2026-12-31")).andRespond(withSuccess("""
				{"from": "2026-01-01", "to": "2026-12-31", "revenue": [{"accountId": 1, "code": "4000", "name": "P", "depth": 0, "amount": "10.00"}],
				 "expenses": [], "totalRevenue": "10.00", "totalExpenses": "0.00", "netProfit": "10.00"}
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/reports/balance-sheet?asOf=2026-06-30")).andRespond(withSuccess("""
				{"asOf": "2026-06-30", "assets": [], "liabilities": [], "equity": [], "earningsNotYetClosed": "0.00", "totalAssets": "0.00",
				 "totalLiabilities": "0.00", "totalEquity": "0.00", "totalLiabilitiesAndEquity": "0.00", "difference": "0.00"}
				""", MediaType.APPLICATION_JSON));

		IncomeStatementDto income = client.incomeStatement(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
		BalanceSheetDto balance = client.balanceSheet(LocalDate.of(2026, 6, 30));

		assertThat(income.revenue().get(0).amount()).isEqualByComparingTo("10.00");
		assertThat(balance.difference()).isEqualByComparingTo("0");
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl frontend/accounting -am test -Dtest='ReportLinesTests,CsvTests,AccountingApiClientTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at test compilation, because the DTOs do not exist.

- [ ] **Step 3: Add the DTOs and the lines**

Create `StatementRowDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;

public record StatementRowDto(Long accountId, String code, String name, int depth, BigDecimal amount) {
}
```

Create `IncomeStatementDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

/** {@code netProfit} is negative for a loss. */
public record IncomeStatementDto(LocalDate from, LocalDate to, List<StatementRowDto> revenue, List<StatementRowDto> expenses,
		BigDecimal totalRevenue, BigDecimal totalExpenses, BigDecimal netProfit) {
}
```

Create `BalanceSheetDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record BalanceSheetDto(LocalDate asOf, List<StatementRowDto> assets, List<StatementRowDto> liabilities,
		List<StatementRowDto> equity, BigDecimal earningsNotYetClosed, BigDecimal totalAssets, BigDecimal totalLiabilities,
		BigDecimal totalEquity, BigDecimal totalLiabilitiesAndEquity, BigDecimal difference) {
}
```

Create `ReportLine.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;

/** One row of a statement, on screen and in its CSV. A heading has no amount; a total has no account. */
public record ReportLine(String section, String code, String name, int depth, BigDecimal amount, boolean total) {

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getAmountText() {
		return Idr.format(amount);
	}

	public String getStyle() {
		String indent = "padding-left:" + depth * 16 + "px";
		return total || amount == null ? indent + ";font-weight:bold" : indent;
	}

}
```

Create `ReportLines.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;

/** Flattens a statement into the rows the screen and the CSV both show. */
final class ReportLines {

	private ReportLines() {
	}

	static List<ReportLine> of(IncomeStatementDto report) {
		List<ReportLine> lines = new ArrayList<>();
		section(lines, "Revenue", report.revenue());
		total(lines, "Revenue", "Total revenue", report.totalRevenue());
		section(lines, "Expenses", report.expenses());
		total(lines, "Expenses", "Total expenses", report.totalExpenses());
		total(lines, "Net", report.netProfit().signum() < 0 ? "Net loss" : "Net profit", report.netProfit().abs());
		return lines;
	}

	static List<ReportLine> of(BalanceSheetDto report) {
		List<ReportLine> lines = new ArrayList<>();
		section(lines, "Assets", report.assets());
		total(lines, "Assets", "Total assets", report.totalAssets());
		section(lines, "Liabilities", report.liabilities());
		total(lines, "Liabilities", "Total liabilities", report.totalLiabilities());
		section(lines, "Equity", report.equity());
		lines.add(new ReportLine("Equity", "", "Earnings not yet closed", 0, report.earningsNotYetClosed(), false));
		total(lines, "Equity", "Total equity", report.totalEquity());
		total(lines, "Total", "Total liabilities and equity", report.totalLiabilitiesAndEquity());
		total(lines, "Total", "Difference", report.difference());
		return lines;
	}

	private static void section(List<ReportLine> lines, String title, List<StatementRowDto> rows) {
		lines.add(new ReportLine(title, "", title, 0, null, false));
		rows.forEach(row -> lines.add(new ReportLine(title, row.code(), row.name(), row.depth(), row.amount(), false)));
	}

	private static void total(List<ReportLine> lines, String section, String name, BigDecimal amount) {
		lines.add(new ReportLine(section, "", name, 0, amount, true));
	}

}
```

- [ ] **Step 4: Add the CSV exports**

In `Csv.java`, add above the `generalLedgerFileName` comment:

```java
	static byte[] incomeStatement(IncomeStatementDto report) {
		return statement(line("Income statement", String.valueOf(report.from()), String.valueOf(report.to())), ReportLines.of(report));
	}

	static byte[] balanceSheet(BalanceSheetDto report) {
		return statement(line("Balance sheet as of", String.valueOf(report.asOf())), ReportLines.of(report));
	}

	/** A parent's amount includes its children, so Level lets a spreadsheet user sum one level only. */
	private static byte[] statement(String title, List<ReportLine> lines) {
		List<String> rows = new ArrayList<>();
		rows.add(title);
		rows.add("Section,Code,Name,Level,Amount");
		for (ReportLine row : lines) {
			if (row.amount() != null) {
				rows.add(line(row.section(), text(row.code()), text(row.name()), row.code().isEmpty() ? "" : String.valueOf(row.depth()),
						amount(row.amount())));
			}
		}
		return bytes(rows);
	}
```

- [ ] **Step 5: Add the client methods**

In `AccountingApiClient.java`, add after `generalLedger`:

```java
	public IncomeStatementDto incomeStatement(LocalDate from, LocalDate to) {
		return client.get().uri(PATH + "/reports/income-statement?from={from}&to={to}", from, to).retrieve()
				.body(IncomeStatementDto.class);
	}

	public BalanceSheetDto balanceSheet(LocalDate asOf) {
		return client.get().uri(PATH + "/reports/balance-sheet?asOf={asOf}", asOf).retrieve().body(BalanceSheetDto.class);
	}
```

- [ ] **Step 6: Run the plain-class tests**

Run: `./mvnw -pl frontend/accounting -am test -Dtest='ReportLinesTests,CsvTests,AccountingApiClientTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 7: Write the failing screen tests**

In `AccountingScreensRenderTests.java`, extend `reportsRender` so its assertion becomes:

```java
		assertThat(render("reports.zul")).contains("Kas Kecil", "1.000,00", "700,00", "Export CSV", "General ledger", "Income statement",
				"Balance sheet", "the closing entry has moved them");
```

In `AccountingClickPathTests.java`, add imports `com.example.ui.accounting.BalanceSheetDto`, `com.example.ui.accounting.IncomeStatementDto`, `com.example.ui.accounting.StatementRowDto`, and add these tests after `reportsSayWhatTheyShowFromTheResponse`:

```java
	private Button nthButton(String label, int index) {
		return zk.all(Button.class).stream().filter(button -> label.equals(button.getLabel())).toList().get(index);
	}

	@Test
	void anIncomeStatementRunRendersAndExportsWhatItHolds() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		when(api.incomeStatement(eq(TODAY.withDayOfYear(1)), eq(TODAY))).thenReturn(new IncomeStatementDto(LocalDate.of(2026, 1, 1),
				LocalDate.of(2026, 3, 31), List.of(new StatementRowDto(1L, "4100", "Penjualan", 0, new BigDecimal("1000.00"))), List.of(),
				new BigDecimal("1000.00"), BigDecimal.ZERO.setScale(2), new BigDecimal("1000.00")));
		page("reports.zul");

		zk.click(nthButton("Run", 2));

		assertThat(zk.all(Listcell.class)).extracting(Listcell::getLabel).contains("4100", "1.000,00");
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Penjualan", "Net profit", "2026-01-01 to 2026-03-31");

		zk.click(nthButton("Export CSV", 2));
		List<String> file = zk.download();
		assertThat(file.get(0)).contains("income-statement-2026-01-01-2026-03-31.csv");
		assertThat(file.get(1)).contains("Revenue,4100,Penjualan,0,1000.00");
	}

	@Test
	void aBalanceSheetRunShowsTheDifference() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		BigDecimal zero = BigDecimal.ZERO.setScale(2);
		when(api.balanceSheet(eq(TODAY))).thenReturn(new BalanceSheetDto(LocalDate.of(2026, 6, 30),
				List.of(new StatementRowDto(1L, "1290", "Akumulasi", 0, new BigDecimal("-5.00"))), List.of(), List.of(), new BigDecimal("-5.00"),
				new BigDecimal("-5.00"), zero, new BigDecimal("-5.00"), new BigDecimal("-5.00"), zero));
		page("reports.zul");

		zk.click(nthButton("Run", 3));

		assertThat(zk.all(Listcell.class)).extracting(Listcell::getLabel).contains("-5,00", "0,00");
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Earnings not yet closed", "Difference", "As of 2026-06-30");
	}

	@Test
	void aClearedReportDateWarnsAndCallsNoApi() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		page("reports.zul");
		List<Datebox> dates = zk.all(Datebox.class);

		zk.change(dates.get(3), "");
		zk.click(nthButton("Run", 2));
		assertThat(zk.response).contains("Choose a date range");
		verify(api, never()).incomeStatement(any(), any());

		zk.change(dates.get(5), "");
		zk.click(nthButton("Run", 3));
		assertThat(zk.response).contains("Choose a date");
		verify(api, never()).balanceSheet(any());
	}

	@Test
	void aRefusedStatementRunSaysWhyAndLeavesNothingToExport() throws Exception {
		when(api.trialBalance(any())).thenReturn(new TrialBalanceDto(TODAY, List.of(), BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO));
		when(api.incomeStatement(any(), any())).thenThrow(problem(HttpStatus.BAD_REQUEST, "validation", "from must not be after to"));
		page("reports.zul");

		zk.click(nthButton("Run", 2));

		assertThat(zk.response).contains("from must not be after to");
		assertThat(nthButton("Export CSV", 2).isDisabled()).isTrue();
	}
```

Confirm: the `Datebox` order on `reports.zul` is trial balance as-of (0), ledger from and to (1, 2), income from and to (3, 4), balance as-of (5); the `Run` and `Export CSV` order is trial balance (0), ledger (1), income (2), balance (3).

- [ ] **Step 8: Run the screen tests to verify they fail**

Run: `./mvnw -pl frontend/shell -am test -Dtest='AccountingScreensRenderTests,AccountingClickPathTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL, because the new tabs are missing.

- [ ] **Step 9: Add the commands to `ReportsViewModel`**

Add fields below `ledger`:

```java
	private LocalDate incomeFrom = today.withDayOfYear(1);

	private LocalDate incomeTo = today;

	private IncomeStatementDto income;

	private LocalDate balanceAsOf = today;

	private BalanceSheetDto balance;
```

Add commands after `exportLedger`:

```java
	@Command
	@NotifyChange({ "income", "incomeLines", "incomeHeading" })
	public void runIncomeStatement() {
		if (incomeFrom == null || incomeTo == null) {
			Clients.showNotification("Choose a date range", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			income = accountingApiClient.incomeStatement(incomeFrom, incomeTo);
		}
		catch (RuntimeException ex) {
			income = null;
			ApiErrors.show(ex);
		}
	}

	@Command
	public void exportIncomeStatement() {
		Filedownload.save(Csv.incomeStatement(income), CSV, "income-statement-" + income.from() + "-" + income.to() + ".csv");
	}

	@Command
	@NotifyChange({ "balance", "balanceLines", "balanceHeading" })
	public void runBalanceSheet() {
		if (balanceAsOf == null) {
			Clients.showNotification("Choose a date", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			balance = accountingApiClient.balanceSheet(balanceAsOf);
		}
		catch (RuntimeException ex) {
			balance = null;
			ApiErrors.show(ex);
		}
	}

	@Command
	public void exportBalanceSheet() {
		Filedownload.save(Csv.balanceSheet(balance), CSV, "balance-sheet-" + balance.asOf() + ".csv");
	}
```

Add getters and setters at the end of the class:

```java
	public LocalDate getIncomeFrom() {
		return incomeFrom;
	}

	public void setIncomeFrom(LocalDate incomeFrom) {
		this.incomeFrom = incomeFrom;
	}

	public LocalDate getIncomeTo() {
		return incomeTo;
	}

	public void setIncomeTo(LocalDate incomeTo) {
		this.incomeTo = incomeTo;
	}

	public IncomeStatementDto getIncome() {
		return income;
	}

	public List<ReportLine> getIncomeLines() {
		return income == null ? List.of() : ReportLines.of(income);
	}

	/** From the response, not the inputs: the input boxes may have changed since the report ran. */
	public String getIncomeHeading() {
		return income == null ? "" : income.from() + " to " + income.to();
	}

	public LocalDate getBalanceAsOf() {
		return balanceAsOf;
	}

	public void setBalanceAsOf(LocalDate balanceAsOf) {
		this.balanceAsOf = balanceAsOf;
	}

	public BalanceSheetDto getBalance() {
		return balance;
	}

	public List<ReportLine> getBalanceLines() {
		return balance == null ? List.of() : ReportLines.of(balance);
	}

	/** From the response, not the inputs: the input box may have changed since the report ran. */
	public String getBalanceHeading() {
		return balance == null ? "" : "As of " + balance.asOf();
	}
```

- [ ] **Step 10: Add the tabs to `reports.zul`**

Add two tabs after `<tab label="General ledger"/>`:

```xml
				<tab label="Income statement"/>
				<tab label="Balance sheet"/>
```

In the trial balance panel, add after `<label value="@load(vm.trialTotals)"/>`:

```xml
					<separator/>
					<label sclass="hint" value="After a year is closed, revenue and expense show zero at 31 December, because the closing entry has moved them to retained earnings. The Income statement tab shows that year's figures."/>
```

Add two panels before `</tabpanels>`:

```xml
				<tabpanel>
					<hlayout sclass="filters card" valign="middle">
						<label value="From"/>
						<datebox valueInLocalDate="@bind(vm.incomeFrom)" format="yyyy-MM-dd" width="130px"/>
						<label value="To"/>
						<datebox valueInLocalDate="@bind(vm.incomeTo)" format="yyyy-MM-dd" width="130px"/>
						<button label="Run" onClick="@command('runIncomeStatement')"/>
						<button label="Export CSV" iconSclass="z-icon-download" disabled="@load(vm.income eq null)" onClick="@command('exportIncomeStatement')"/>
					</hlayout>
					<label value="@load(vm.incomeHeading)"/>
					<listbox model="@load(vm.incomeLines)" emptyMessage="Choose the dates and press Run" sclass="card">
						<listhead>
							<listheader label="Code" width="90px"/>
							<listheader label="Account"/>
							<listheader label="Amount" width="170px" align="right"/>
						</listhead>
						<template name="model" var="line">
							<listitem>
								<listcell label="@load(line.code)"/>
								<listcell><label value="@load(line.name)" style="@load(line.style)"/></listcell>
								<listcell label="@load(line.amountText)"/>
							</listitem>
						</template>
					</listbox>
				</tabpanel>
				<tabpanel>
					<hlayout sclass="filters card" valign="middle">
						<label value="As of"/>
						<datebox valueInLocalDate="@bind(vm.balanceAsOf)" format="yyyy-MM-dd" width="130px"/>
						<button label="Run" onClick="@command('runBalanceSheet')"/>
						<button label="Export CSV" iconSclass="z-icon-download" disabled="@load(vm.balance eq null)" onClick="@command('exportBalanceSheet')"/>
					</hlayout>
					<label value="@load(vm.balanceHeading)"/>
					<listbox model="@load(vm.balanceLines)" emptyMessage="Choose a date and press Run" sclass="card">
						<listhead>
							<listheader label="Code" width="90px"/>
							<listheader label="Account"/>
							<listheader label="Amount" width="170px" align="right"/>
						</listhead>
						<template name="model" var="line">
							<listitem>
								<listcell label="@load(line.code)"/>
								<listcell><label value="@load(line.name)" style="@load(line.style)"/></listcell>
								<listcell label="@load(line.amountText)"/>
							</listitem>
						</template>
					</listbox>
				</tabpanel>
```

- [ ] **Step 11: Run the screen tests**

Run: `./mvnw -pl frontend/shell -am test -Dtest='AccountingScreensRenderTests,AccountingClickPathTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 12: Commit**

```bash
git add frontend/accounting/src/main/java/com/example/ui/accounting/StatementRowDto.java frontend/accounting/src/main/java/com/example/ui/accounting/IncomeStatementDto.java frontend/accounting/src/main/java/com/example/ui/accounting/BalanceSheetDto.java frontend/accounting/src/main/java/com/example/ui/accounting/ReportLine.java frontend/accounting/src/main/java/com/example/ui/accounting/ReportLines.java frontend/accounting/src/main/java/com/example/ui/accounting/Csv.java frontend/accounting/src/main/java/com/example/ui/accounting/AccountingApiClient.java frontend/accounting/src/main/java/com/example/ui/accounting/ReportsViewModel.java frontend/accounting/src/main/resources/web/accounting/reports.zul frontend/accounting/src/test/java/com/example/ui/accounting/ReportLinesTests.java frontend/accounting/src/test/java/com/example/ui/accounting/CsvTests.java frontend/accounting/src/test/java/com/example/ui/accounting/AccountingApiClientTests.java frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java
git commit -m "Add income statement and balance sheet screens"
```

---

### Task 7: Close year and Reopen year on the Periods screen

**Files:**
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/YearStatusDto.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/YearCloseResultDto.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/AccountingApiClient.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/PeriodsViewModel.java`
- Modify: `frontend/accounting/src/main/resources/web/accounting/periods.zul`
- Modify (test): `frontend/accounting/src/test/java/com/example/ui/accounting/AccountingApiClientTests.java`
- Modify (test): `frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java`
- Modify (test): `frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java`

**Interfaces:**
- Consumes: `AccountingApiClient.incomeStatement` (Task 6), `AccountMatcher.find(String, List<AccountDto>)`, the `/years` API (Tasks 3 and 4).
- Produces: `YearStatusDto(int year, boolean closed, Long entryId, String entryNo)`, `YearCloseResultDto(int year, Long entryId, String entryNo, BigDecimal netProfit)`; `AccountingApiClient.yearStatus(int): YearStatusDto`, `.closeYear(int year, long retainedEarningsAccountId): YearCloseResultDto`, `.reopenYear(int year, String reason): YearStatusDto`. Existing tests leave `yearStatus` unstubbed, so the view model must treat a null status as "not closed".

- [ ] **Step 1: Write the failing tests**

In `AccountingApiClientTests.java`, add:

```java
	@Test
	void closingAndReopeningAYearSendTheirBodies() {
		server.expect(requestTo(BASE + "/years/2026")).andRespond(withSuccess("""
				{"year": 2026, "closed": true, "entryId": 9, "entryNo": "JE-2026-000009"}
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/years/2026/close")).andExpect(method(HttpMethod.POST))
				.andExpect(content().json("{\"retainedEarningsAccountId\": 5}"))
				.andRespond(withSuccess("""
						{"year": 2026, "entryId": 9, "entryNo": "JE-2026-000009", "netProfit": "-20.00"}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/years/2026/reopen")).andExpect(method(HttpMethod.POST))
				.andExpect(content().json("{\"reason\": \"late\"}"))
				.andRespond(withSuccess("""
						{"year": 2026, "closed": false}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.yearStatus(2026).entryNo()).isEqualTo("JE-2026-000009");
		assertThat(client.closeYear(2026, 5).netProfit()).isEqualByComparingTo("-20.00");
		assertThat(client.reopenYear(2026, "late").closed()).isFalse();
	}
```

In `AccountingScreensRenderTests.java`, add import `com.example.ui.accounting.YearStatusDto` and:

```java
	@Test
	void aClosedYearRendersItsCloseAndTheReopenButton() throws Exception {
		when(accountingApiClient.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, 2026, 3, LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31), "CLOSED", "Ani Accountant")));
		when(accountingApiClient.yearStatus(anyInt())).thenReturn(new YearStatusDto(2026, true, 9L, "JE-2026-000009"));

		assertThat(render("periods.zul")).contains("Reopen year", "Closed by JE\\-2026\\-000009");
	}
```

In `AccountingClickPathTests.java`, add imports `com.example.ui.accounting.IncomeStatementDto` (already added in Task 6), `com.example.ui.accounting.YearStatusDto`, and add these tests after `aCloseRefusedWithNoDraftsToListShowsTheProblem`:

```java
	private static final AccountDto RETAINED = new AccountDto(9L, "3200", "Laba Ditahan", "EQUITY", null, true, true);

	private static final AccountDto CAPITAL = new AccountDto(10L, "3100", "Modal", "EQUITY", null, true, true);

	private static final int YEAR = TODAY.getYear();

	private void aYear(boolean closed, String net) {
		when(api.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, YEAR, 3, LocalDate.of(YEAR, 3, 1), LocalDate.of(YEAR, 3, 31),
				closed ? "CLOSED" : "OPEN", null)));
		when(api.yearStatus(anyInt())).thenReturn(new YearStatusDto(YEAR, closed, closed ? 9L : null, closed ? "JE-1" : null));
		when(api.accounts()).thenReturn(List.of(KAS, CAPITAL, RETAINED));
		when(api.incomeStatement(eq(LocalDate.of(YEAR, 1, 1)), eq(LocalDate.of(YEAR, 12, 31)))).thenReturn(new IncomeStatementDto(
				LocalDate.of(YEAR, 1, 1), LocalDate.of(YEAR, 12, 31), List.of(), List.of(), BigDecimal.ZERO, BigDecimal.ZERO, new BigDecimal(net)));
	}

	private Textbox shownTextbox() {
		return zk.all(Textbox.class).stream().filter(AccountingClickPathTests::shown).filter(box -> !(box instanceof Combobox)).findFirst().orElseThrow();
	}

	@Test
	void closingAYearStatesTheNetDefaultsTheAccountAndAsksFirst() throws Exception {
		aYear(false, "700.00");
		page("periods.zul");
		assertThat(shown(zk.button("Close year"))).isTrue();
		assertThat(shown(zk.button("Reopen year"))).isFalse();

		zk.click(zk.button("Close year"));
		verify(api, never()).closeYear(anyInt(), anyLong());
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Close " + YEAR + ": net profit 700,00 moves to the account below");
		Combobox account = zk.all(Combobox.class).stream().filter(AccountingClickPathTests::shown).findFirst().orElseThrow();
		assertThat(account.getValue()).isEqualTo("3200 Laba Ditahan");

		zk.click(zk.button("Confirm close"));
		verify(api).closeYear(YEAR, 9L);
		assertThat(shown(zk.button("Confirm close"))).isFalse();
	}

	@Test
	void aLossIsStatedAsALossAndAnotherAccountCanBeChosen() throws Exception {
		aYear(false, "-200.00");
		page("periods.zul");

		zk.click(zk.button("Close year"));
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Close " + YEAR + ": net loss 200,00 moves to the account below");
		Combobox account = zk.all(Combobox.class).stream().filter(AccountingClickPathTests::shown).findFirst().orElseThrow();
		assertThat(account.getModel().getSize()).as("only equity accounts are offered").isEqualTo(2);

		zk.change(account, CAPITAL.getLabel());
		zk.click(zk.button("Confirm close"));
		verify(api).closeYear(YEAR, 10L);
	}

	@Test
	void anAccountThatMatchesNothingWarnsAndDoesNotClose() throws Exception {
		aYear(false, "700.00");
		page("periods.zul");
		zk.click(zk.button("Close year"));

		zk.change(zk.all(Combobox.class).stream().filter(AccountingClickPathTests::shown).findFirst().orElseThrow(), "nope");
		zk.click(zk.button("Confirm close"));

		assertThat(zk.response).contains("Choose an equity account");
		verify(api, never()).closeYear(anyInt(), anyLong());
	}

	@Test
	void aRefusedCloseShowsTheProblemAndKeepsThePanel() throws Exception {
		aYear(false, "700.00");
		when(api.closeYear(anyInt(), anyLong())).thenThrow(problem(HttpStatus.CONFLICT, "year-has-drafts", "Post or delete the 2 draft entries"));
		page("periods.zul");
		zk.click(zk.button("Close year"));

		zk.click(zk.button("Confirm close"));

		assertThat(zk.response).contains("Post or delete the 2 draft entries");
		assertThat(shown(zk.button("Confirm close"))).isTrue();
	}

	@Test
	void changingTheYearDropsAnOpenClosePanel() throws Exception {
		aYear(false, "700.00");
		page("periods.zul");
		zk.click(zk.button("Close year"));
		assertThat(shown(zk.button("Confirm close"))).isTrue();

		zk.change(zk.all(Intbox.class).get(0), String.valueOf(YEAR - 1));

		assertThat(shown(zk.button("Confirm close"))).isFalse();
		verify(api, never()).closeYear(anyInt(), anyLong());
	}

	@Test
	void aFailedNetLoadShowsTheProblemAndOpensNoPanel() throws Exception {
		aYear(false, "700.00");
		when(api.incomeStatement(any(), any())).thenThrow(problem(HttpStatus.BAD_REQUEST, "validation", "Reports are down"));
		page("periods.zul");

		zk.click(zk.button("Close year"));

		assertThat(zk.response).contains("Reports are down");
		assertThat(shown(zk.button("Confirm close"))).isFalse();
	}

	@Test
	void reopeningAClosedYearNeedsAReasonAndHidesMonthReopen() throws Exception {
		aYear(true, "700.00");
		page("periods.zul");
		assertThat(shown(zk.button("Close year"))).isFalse();
		assertThat(rowButtons(zk.all(Listitem.class).get(0))).isEmpty();
		assertThat(zk.all(Label.class)).extracting(Label::getValue).contains("Closed by JE-1");

		zk.click(zk.button("Reopen year"));
		zk.click(zk.button("Confirm year reopen"));
		assertThat(zk.response).contains("Enter a reason");
		verify(api, never()).reopenYear(anyInt(), anyString());

		zk.change(shownTextbox(), "late invoice");
		zk.click(zk.button("Confirm year reopen"));
		verify(api).reopenYear(YEAR, "late invoice");
	}

	@Test
	void withoutThePermissionNoYearActionIsOffered() throws Exception {
		aYear(false, "700.00");
		can("accounting:read");
		page("periods.zul");

		assertThat(shown(zk.button("Close year"))).isFalse();
		assertThat(shown(zk.button("Reopen year"))).isFalse();
	}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl frontend/shell -am test -Dtest='AccountingScreensRenderTests,AccountingClickPathTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at test compilation, because `YearStatusDto` does not exist.

- [ ] **Step 3: Add the DTOs and client methods**

Create `YearStatusDto.java`:

```java
package com.example.ui.accounting;

public record YearStatusDto(int year, boolean closed, Long entryId, String entryNo) {
}
```

Create `YearCloseResultDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;

public record YearCloseResultDto(int year, Long entryId, String entryNo, BigDecimal netProfit) {
}
```

In `AccountingApiClient.java`, add after `reopenPeriod`:

```java
	public YearStatusDto yearStatus(int year) {
		return client.get().uri(PATH + "/years/{year}", year).retrieve().body(YearStatusDto.class);
	}

	public YearCloseResultDto closeYear(int year, long retainedEarningsAccountId) {
		return client.post().uri(PATH + "/years/{year}/close", year).body(Map.of("retainedEarningsAccountId", retainedEarningsAccountId))
				.retrieve().body(YearCloseResultDto.class);
	}

	public YearStatusDto reopenYear(int year, String reason) {
		return client.post().uri(PATH + "/years/{year}/reopen", year).body(Map.of("reason", reason)).retrieve().body(YearStatusDto.class);
	}
```

- [ ] **Step 4: Add the panels to `PeriodsViewModel`**

Add import `java.math.BigDecimal`. Update the class comment:

```java
/** MVVM view model behind periods.zul: a year's months, close and reopen of a month and of the whole year. */
```

Add fields below `reason`:

```java
	private YearStatusDto yearStatus;

	private boolean closingYear;

	private BigDecimal closeNet;

	private List<AccountDto> equityAccounts = List.of();

	private String retainedEarningsText = "";

	private boolean reopeningYear;
```

In `load()`, add after `reopening = null;`:

```java
		closingYear = false;
		reopeningYear = false;
```

In `startReopen`, add after `reason = "";`:

```java
		closingYear = false;
		reopeningYear = false;
```

Replace `fetch()` with:

```java
	private void fetch() {
		yearStatus = null;
		if (year == null) {
			periods = List.of();
			return;
		}
		try {
			periods = accountingApiClient.periods(year);
			yearStatus = periods.isEmpty() ? null : accountingApiClient.yearStatus(year);
		}
		catch (RuntimeException ex) {
			periods = List.of();
			ApiErrors.show(ex);
		}
	}
```

Add commands above `showBlockingDrafts`:

```java
	/** Shows what the close will move before anything happens. */
	@Command
	@NotifyChange("*")
	public void startCloseYear() {
		try {
			closeNet = accountingApiClient.incomeStatement(LocalDate.of(year, 1, 1), LocalDate.of(year, 12, 31)).netProfit();
			equityAccounts = accountingApiClient.accounts().stream()
					.filter(account -> "EQUITY".equals(account.type()) && account.postable() && account.active())
					.toList();
			retainedEarningsText = equityAccounts.stream().filter(account -> "3200".equals(account.code())).findFirst()
					.map(AccountDto::getLabel).orElse("");
			closingYear = true;
			reopening = null;
			reopeningYear = false;
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	@Command
	@NotifyChange("*")
	public void cancelCloseYear() {
		closingYear = false;
	}

	@Command
	@NotifyChange("*")
	public void confirmCloseYear() {
		AccountDto account = AccountMatcher.find(retainedEarningsText, equityAccounts).orElse(null);
		if (account == null) {
			Clients.showNotification("Choose an equity account", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			accountingApiClient.closeYear(year, account.id());
			load();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	@Command
	@NotifyChange("*")
	public void startReopenYear() {
		reopeningYear = true;
		reason = "";
		reopening = null;
		closingYear = false;
	}

	@Command
	@NotifyChange("*")
	public void cancelReopenYear() {
		reopeningYear = false;
	}

	@Command
	@NotifyChange("*")
	public void confirmReopenYear() {
		if (reason == null || reason.isBlank()) {
			Clients.showNotification("Enter a reason", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			accountingApiClient.reopenYear(year, reason.strip());
			load();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}
```

Add getters at the end of the class:

```java
	public boolean isYearClosed() {
		return yearStatus != null && yearStatus.closed();
	}

	public String getYearStatusText() {
		return isYearClosed() ? "Closed by " + yearStatus.entryNo() : "";
	}

	public boolean isCanCloseYear() {
		return canManage && !periods.isEmpty() && !isYearClosed();
	}

	public boolean isCanReopenYear() {
		return canManage && isYearClosed();
	}

	public boolean isClosingYear() {
		return closingYear;
	}

	public String getCloseYearText() {
		if (!closingYear) {
			return "";
		}
		String net = closeNet.signum() < 0 ? "net loss " : "net profit ";
		return "Close " + year + ": " + net + Idr.format(closeNet.abs()) + " moves to the account below";
	}

	public List<AccountDto> getEquityAccounts() {
		return equityAccounts;
	}

	public String getRetainedEarningsText() {
		return retainedEarningsText;
	}

	public void setRetainedEarningsText(String retainedEarningsText) {
		this.retainedEarningsText = retainedEarningsText;
	}

	public boolean isReopeningYear() {
		return reopeningYear;
	}

	public String getReopenYearText() {
		return reopeningYear ? "Reopen " + year + ": this reverses its closing entry and opens December" : "";
	}
```

`PeriodsViewModelTests.openYearWithABlankYearFieldCallsNoApi` still holds: `fetch()` returns before any API call when the year is null.

- [ ] **Step 5: Update `periods.zul`**

Replace the opening `hlayout` with:

```xml
		<hlayout sclass="filters card" valign="middle">
			<label value="Year"/>
			<intbox value="@load(vm.year)" width="80px" onChange="@command('changeYear', year=self.value)"/>
			<button label="@load(vm.openYearLabel)" visible="@load(vm.canManage and vm.year ne null)" onClick="@command('openYear')"/>
			<button label="Close year" visible="@load(vm.canCloseYear)" onClick="@command('startCloseYear')"/>
			<button label="Reopen year" visible="@load(vm.canReopenYear)" onClick="@command('startReopenYear')"/>
			<label value="@load(vm.yearStatusText)"/>
		</hlayout>

		<groupbox mold="3d" sclass="card" visible="@load(vm.closingYear)">
			<caption label="Close year"/>
			<label value="@load(vm.closeYearText)"/>
			<separator/>
			<combobox value="@bind(vm.retainedEarningsText)" model="@load(vm.equityAccounts)" autodrop="true" placeholder="Retained earnings account" width="260px">
				<template name="model"><comboitem label="@load(each.label)"/></template>
			</combobox>
			<hbox>
				<button label="Confirm close" autodisable="self" onClick="@command('confirmCloseYear')"/>
				<button label="Cancel" onClick="@command('cancelCloseYear')"/>
			</hbox>
		</groupbox>

		<groupbox mold="3d" sclass="card" visible="@load(vm.reopeningYear)">
			<caption label="Reopen year"/>
			<label value="@load(vm.reopenYearText)"/>
			<separator/>
			<textbox value="@bind(vm.reason)" maxlength="500" placeholder="Reason" hflex="1"/>
			<hbox>
				<button label="Confirm year reopen" autodisable="self" onClick="@command('confirmReopenYear')"/>
				<button label="Cancel" onClick="@command('cancelReopenYear')"/>
			</hbox>
		</groupbox>
```

Change the month's Reopen button, because a month of a closed year always gets `year-closed`:

```xml
						<button label="Reopen" visible="@load(vm.canManage and not period.open and not vm.yearClosed)" onClick="@command('startReopen', period=period)"/>
```

- [ ] **Step 6: Run the tests**

Run: `./mvnw -pl frontend/shell -am test -Dtest='AccountingScreensRenderTests,AccountingClickPathTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

Run: `./mvnw -pl frontend/accounting -am test -Dtest='AccountingApiClientTests,PeriodsViewModelTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 7: Commit**

```bash
git add frontend/accounting/src/main/java/com/example/ui/accounting/YearStatusDto.java frontend/accounting/src/main/java/com/example/ui/accounting/YearCloseResultDto.java frontend/accounting/src/main/java/com/example/ui/accounting/AccountingApiClient.java frontend/accounting/src/main/java/com/example/ui/accounting/PeriodsViewModel.java frontend/accounting/src/main/resources/web/accounting/periods.zul frontend/accounting/src/test/java/com/example/ui/accounting/AccountingApiClientTests.java frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java
git commit -m "Close and reopen a year from the Periods screen"
```

---

### Task 8: Closing entry label and audit filter

**Files:**
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/EntryDto.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/EntryViewModel.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/AuditViewModel.java`
- Modify: `frontend/accounting/src/main/resources/web/accounting/entry.zul`
- Modify (test): `frontend/accounting/src/test/java/com/example/ui/accounting/EntryFormTests.java:180`
- Modify (test): `frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java:154`
- Test: `frontend/accounting/src/test/java/com/example/ui/accounting/AuditViewModelTests.java`

**Interfaces:**
- Consumes: `EntryResponse.closingYear` from Task 3.
- Produces: `EntryDto.closingYear` (`Integer`, last record component); `EntryViewModel.isClosingEntry()`, `EntryViewModel.isReversible()`.

- [ ] **Step 1: Write the failing tests**

Create `AuditViewModelTests.java`:

```java
package com.example.ui.accounting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditViewModelTests {

	@Test
	void yearActionsCanBeFilteredOn() {
		assertThat(new AuditViewModel().getActions()).contains("YEAR_CLOSE", "YEAR_REOPEN");
	}

}
```

In `AccountingClickPathTests.java`, change the `entry(...)` helper so the constructor call ends with `false, null);`, and add:

```java
	@Test
	void aClosingEntryIsLabelledAndOffersNoReversal() throws Exception {
		EntryDto closing = new EntryDto(7L, "JE-2026-000009", LocalDate.of(2026, 12, 31), 1L, null, "Closing entry for 2026", "POSTED", null,
				null, "A", "B", 1L, new BigDecimal("100.00"), List.of(new LineDto(1, 2L, new BigDecimal("100.00"), null, ""),
						new LineDto(2, 4L, null, new BigDecimal("100.00"), "")),
				false, 2026);
		openEntry(closing);

		assertThat(zk.all(Label.class).stream().filter(AccountingClickPathTests::shown)).extracting(Label::getValue).contains("Closing entry");
		assertThat(shown(zk.button("Reverse"))).isFalse();
		assertThat(shown(zk.button("Reverse & copy to draft"))).isFalse();
	}

	@Test
	void anOrdinaryPostedEntryStillOffersReversalAndNoLabel() throws Exception {
		openEntry(entry(7L, "JE-1", "POSTED", 1L));

		assertThat(zk.all(Label.class).stream().filter(AccountingClickPathTests::shown)).extracting(Label::getValue).doesNotContain("Closing entry");
		assertThat(shown(zk.button("Reverse"))).isTrue();
	}
```

In `EntryFormTests.java`, change the constructor call near line 180 so it ends with `false, null);`.

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -pl frontend/shell -am test -Dtest='AccountingClickPathTests,AuditViewModelTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at test compilation, because `EntryDto` has no `closingYear`.

- [ ] **Step 3: Implement**

In `EntryDto.java`, add `Integer closingYear` as the last record component:

```java
public record EntryDto(Long id, String entryNo, LocalDate date, Long periodId, String reference, String description,
		String status, Long reversalOf, Long reversedBy, String createdByName, String postedByName, Long version,
		BigDecimal totalDebit, List<LineDto> lines, boolean posterBlocked, Integer closingYear) {
```

In `EntryViewModel.java`, add after `isPosted()`:

```java
	public boolean isClosingEntry() {
		return entry != null && entry.closingYear() != null;
	}

	/** Only reopening its year undoes a closing entry. */
	public boolean isReversible() {
		return isPosted() && !isClosingEntry();
	}
```

In `entry.zul`, change the three reversal controls to use `vm.reversible` in place of `vm.posted`, and add the label as the first child of that `hlayout`, before the `Save draft` button:

```xml
			<label value="Closing entry" sclass="hint" visible="@load(vm.closingEntry)"/>
```

```xml
			<datebox valueInLocalDate="@bind(vm.reverseDate)" format="yyyy-MM-dd" placeholder="Reversal date"
				visible="@load(vm.reversible and vm.canPost)" width="130px"/>
			<button label="Reverse" visible="@load(vm.reversible and vm.canPost)" autodisable="self"
				w:onClick="if (jq('.z-window input[class*=-invalid]').length) event.stop();" onClick="@command('reverse', copy=false)"/>
			<button label="Reverse &amp; copy to draft" visible="@load(vm.reversible and vm.canPost)" autodisable="self"
				w:onClick="if (jq('.z-window input[class*=-invalid]').length) event.stop();" onClick="@command('reverse', copy=true)"/>
```

In `AuditViewModel.java`:

```java
		return List.of("", "SETUP", "POST", "REVERSE", "PERIOD_CLOSE", "PERIOD_REOPEN", "SETTING_CHANGE", "ACCOUNT_CHANGE", "YEAR_CLOSE",
				"YEAR_REOPEN");
```

- [ ] **Step 4: Run the tests**

Run: `./mvnw -pl frontend/shell -am test -Dtest='AccountingClickPathTests,AccountingScreensRenderTests,EntryFormTests,AuditViewModelTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS, `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add frontend/accounting/src/main/java/com/example/ui/accounting/EntryDto.java frontend/accounting/src/main/java/com/example/ui/accounting/EntryViewModel.java frontend/accounting/src/main/java/com/example/ui/accounting/AuditViewModel.java frontend/accounting/src/main/resources/web/accounting/entry.zul frontend/accounting/src/test/java/com/example/ui/accounting/EntryFormTests.java frontend/accounting/src/test/java/com/example/ui/accounting/AuditViewModelTests.java frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java
git commit -m "Label closing entries and filter audit by year actions"
```

---

### Task 9: Documentation and the full build

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`

**Interfaces:**
- Consumes: everything above.
- Produces: the docs and a green `./mvnw verify`.

- [ ] **Step 1: Update the README's Accounting section**

Replace the first paragraph of `## Accounting` with:

```markdown
The accounting module (`backend/accounting`, schema `accounting`): a chart of accounts copied from a template at setup,
journal entries that go draft -> post -> reverse with maker/checker, gapless `JE-<year>-<6 digits>` numbers per tenant
and year, monthly periods, a trial balance, a general ledger, an income statement, a balance sheet, and a year-end close
into retained earnings. The API is also in Swagger UI (`/swagger-ui.html`).
```

Replace the second paragraph's sentence about **Periods** and **Reports** so it reads `**Periods** (close, reopen, open a year, close and reopen a whole year) and **Reports** (trial balance, general ledger, income statement and balance sheet, each with CSV export)`.

Add these rows to the API table, after the `general-ledger` row:

```markdown
| `GET /api/accounting/reports/income-statement?from&to` | `read` | Both dates required; leaves out closing entries and their reversals |
| `GET /api/accounting/reports/balance-sheet?asOf=` | `read` | `asOf` defaults to today; equity ends with the calculated "Earnings not yet closed" |
| `GET /api/accounting/years/{year}` | `read` | Whether the year has an active close, with that entry's id and number |
| `POST /api/accounting/years/{year}/close` | `period-manage` | Body `{retainedEarningsAccountId}`; posts a closing entry at once and closes every month. 422 `no-period`, `account-not-postable`, `bad-retained-earnings-account`; 409 `year-closed`, `year-has-drafts`, `earlier-year-open`, `nothing-to-close` |
| `POST /api/accounting/years/{year}/reopen` | `period-manage` | Body `{reason}`; reverses the closing entry and opens December. 409 `year-not-closed`, `later-year-closed`; 422 `account-not-postable` |
```

Add this paragraph after `Amounts are JSON strings ...`:

```markdown
A close is a real journal entry marked with `closing_year`, so the trial balance and general ledger show it as usual, and a
trial balance for 31 December after a close shows zero revenue and expense. Reversing a closing entry is refused with 409
`closing-entry`, and reopening a month of a closed year with 409 `year-closed`: only reopening the year undoes a close.
```

- [ ] **Step 2: Add the manual rows**

Add after row 40 in the manual check table:

```markdown
| 41 | As `accountant`, post a sale and an expense, then open **Reports > Income statement** and **Balance sheet**, and press Run on each | Revenue and expenses indent by the chart's hierarchy; net profit is right; the balance sheet shows a difference of 0,00, with the profit on "Earnings not yet closed" |
| 42 | Post a depreciation entry to `1290 Akumulasi Penyusutan`, then run the balance sheet and export its CSV | `1290` shows as a negative within assets, on screen and in the file; the file opens in Excel with one row per line |
| 43 | Clear a date box on **Income statement** and press Run | A warning asks for a date range; the previous figures stay |
| 44 | As `accountant`, on **Periods** press **Close year**, check the stated net and the default account `3200 Laba Ditahan`, then confirm | Every month shows CLOSED and "Closed by JE-..." appears; the income statement for the year still shows the same net; the balance sheet at 31 December shows it on `3200`, with a difference of 0,00; **Audit** has a `YEAR_CLOSE` row |
| 45 | Open the closing entry from **Journal**, then press **Reopen year** on **Periods** with a reason, post a late invoice dated in December and close the year again | The entry says "Closing entry" and offers no Reverse; reopening opens December only; the second close includes the invoice; the journal keeps both closes and the reversal; **Audit** has a `YEAR_REOPEN` row with the reason |
| 46 | As `bookkeeper`, open **Periods** | No **Close year** or **Reopen year** button |
```

- [ ] **Step 3: Update `CLAUDE.md`**

Add to `## Gotchas`, after the bullet on `accounting` grants and database triggers:

```markdown
- A closing entry takes revenue and expense to zero, so the income statement must leave out `closing_year` entries and the
  balance sheet must include them; a new report chooses deliberately. Close and reopen take the tenant's `settings` row
  first, so year actions run one at a time. Only `YearEndService.reopen` reverses a closing entry.
```

Do not change `## Status`; it records verification after it has happened, and the controller does that once the branch is reviewed.

- [ ] **Step 4: Run the whole build**

Check the socket first; if `docker ps` hangs or fails, stop and report instead of waiting:

Run: `perl -e 'alarm 15; exec @ARGV' docker ps -q`
Expected: exits 0.

Run: `caffeinate -i ./mvnw verify`
Expected: `BUILD SUCCESS`, with every module passing. A run with no `BUILD` line is not a pass.

- [ ] **Step 5: Commit**

```bash
git add README.md CLAUDE.md
git commit -m "Document statements and the year-end close"
```
