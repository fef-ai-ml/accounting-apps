# Ledger Hardening Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Close the known gaps in the Phase 1 ledger: no journal line is ever hard deleted, maker/checker covers every editor, the database refuses states the services avoid, accountants and admins can read the audit log, and a misconfigured deployment or tenant-less token fails closed.

**Architecture:** One accounting migration, `V20261103_2__ledger_hardening.sql`, grows task by task under fixed section comments; it has not shipped, so editing it between tasks is fine. Removed rows stay in place with `deleted_at`; `@SQLRestriction` hides them from JPA and native SQL filters by hand. New audit endpoint and ZK screen follow the journal list's patterns.

**Tech Stack:** Java 25, Spring Boot 4.1.2, Hibernate 7.4, Jackson 3, Flyway, Postgres 17 with RLS, ZK 10.3 MVVM, Testcontainers, JUnit 5, AssertJ.

**Spec:** `docs/superpowers/specs/2026-11-03-ledger-hardening-design.md`

## Global Constraints

- Work on branch `feat/ledger-hardening`. Do not push.
- Do not run this migration against the home-lab database. Testcontainers only.
- Commits are signed through 1Password. Never bypass signing. Stage explicit paths; never stage `.bloop`, `.metals`, `graphify-out` or `.superpowers`.
- Backend tests need Docker (Testcontainers); on the maintainer's Mac that is the SSH-forwarded Podman socket. If the socket is gone, stop and report; do not touch home-lab containers.
- Tests run the application as `app_runtime`; never use `@ServiceConnection`.
- A rule enforced by a trigger must also be checked in the service first, because a trigger failure is a logged 500.
- Native SQL on `journal_line` or `journal_entry` must filter `deleted_at is null`, except where the row's existence is the point.
- ZK: any record bound from ZUL needs JavaBean getters. View models catch `RuntimeException` around API calls and pass it to `ApiErrors.show(...)`.
- Run single modules with `./mvnw -pl backend/accounting -am test -Dtest='<Class>' -Dsurefire.failIfNoSpecifiedTests=false`.
- A Maven run with no `BUILD` line is not a pass.

## Review Focus

These are pinned by tests in the owning tasks.

1. An entry edited before posting, then reversed or reversed with a copy to draft: only live lines are carried over (Task 1).
2. A draft saved repeatedly, shrunk to no lines, then filled again: line numbers restart and old rows stay (Task 1).
3. A deleted draft's id on every endpoint (GET, PUT, DELETE, post, reverse): 404, never a 500 (Task 1).
4. An account PUT that omits `active`, or moves an account to or from the top level: no false `active` change and no null failure in the audit details (Task 5).
5. Audit rows whose `details` hold large amounts, such as `totalDebit` of 10,000,000: shown as `10000000.00`, not `1.0E7`. A `Map` read by default Jackson turns them into doubles (Task 5).

## Decisions beyond the spec

- `JournalEntry.lines` keeps `CascadeType.MERGE` with `PERSIST`, because `saveAndFlush` merges. The spec's point stands: no `REMOVE`, no `orphanRemoval`.
- `JournalEntry.removeLines` marks and clears the list in one call; the service flushes after it. Same effect as the spec's order.
- `@SQLRestriction` goes on the collection from the start as well as on `JournalLine`, instead of waiting to see whether the entity one reaches it.
- `DbPermissionResolver` drops its tenant check, so the converter is the single gate. Its "no active tenant" test moves to the converter.
- The audit API sends decimal numbers in `details` as plain strings, for Review Focus 5.

---

### Task 1: No journal line is hard deleted

**Files:**
- Create: `backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalLine.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalEntry.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalService.java:80-95,104-109`
- Modify: `backend/accounting/src/main/java/com/example/accounting/ReportService.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/FiscalPeriodRepository.java:31-33`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AccountRepository.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AccountService.java:47,59-60`
- Create: `backend/accounting/src/test/java/com/example/accounting/NoHardDeleteTests.java`
- Modify: `backend/accounting/src/test/java/com/example/accounting/DatabaseGuardTests.java`
- Modify: `backend/accounting/src/test/java/com/example/accounting/ReportTests.java`
- Modify: `backend/accounting/src/test/java/com/example/accounting/PeriodTests.java`
- Modify: `backend/accounting/src/test/java/com/example/accounting/SetupAndAccountsTests.java`

**Interfaces:**
- Produces: `JournalEntry.removeLines(Actor, Instant)`, `JournalEntry.markDeleted(Actor, Instant)`, `AccountRepository.hasLiveLines(long)`, and migration section comments `-- Columns`, `-- Constraints and index`, `-- Guards`, `-- Revoke`, `-- Backfill` that later tasks add under.

- [ ] **Step 1: Write the failing test**

Create `NoHardDeleteTests.java`:

```java
package com.example.accounting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.SQLException;
import java.sql.Statement;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.ResultActions;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class NoHardDeleteTests extends AccountingTestSupport {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	JdbcTemplate jdbc;

	private long cash;

	private long sales;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
	}

	private long count(String sql) {
		return inTransaction(() -> ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
	}

	private ResultActions update(long id, long version, String... lines) throws Exception {
		return mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("editor", READ, WRITE))
				.contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, lines).replaceFirst("\\{", "{\"version\": " + version + ", ")));
	}

	private ResultActions deleteDraft(long id, long version) throws Exception {
		return mvc.perform(delete("/api/accounting/journal-entries/" + id).param("version", String.valueOf(version))
				.with(as("deleter", WRITE)));
	}

	private Connection owner() throws SQLException {
		return DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
	}

	@Test
	void savingADraftKeepsTheLinesItReplaced() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));

		update(id, versionOf(id), line(cash, "7", "0")).andExpect(status().isOk())
				.andExpect(jsonPath("$.lines", hasSize(1)))
				.andExpect(jsonPath("$.lines[0].lineNo").value(1));

		assertThat(count("select count(*) from accounting.journal_line where entry_id = " + id
				+ " and deleted_at is not null and deleted_by_sub = 'editor' and deleted_by_name = 'editor-name'")).isEqualTo(2);
		assertThat(count("select count(*) from accounting.journal_line where entry_id = " + id
				+ " and deleted_at is null")).isEqualTo(1);
	}

	@Test
	void aDraftCanBeSavedRepeatedlyDownToNoLinesAndFilledAgain() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		update(id, versionOf(id), line(cash, "2", "0"), line(sales, "0", "2")).andExpect(status().isOk());
		update(id, versionOf(id)).andExpect(status().isOk()).andExpect(jsonPath("$.lines", hasSize(0)));
		postEntry(id, "checker").andExpect(problem(422, "too-few-lines"));

		update(id, versionOf(id), line(cash, "3", "0"), line(sales, "0", "3")).andExpect(status().isOk())
				.andExpect(jsonPath("$.lines[0].lineNo").value(1))
				.andExpect(jsonPath("$.lines[1].lineNo").value(2));

		assertThat(count("select count(*) from accounting.journal_line where entry_id = " + id)).isEqualTo(6);
		postEntry(id, "checker").andExpect(status().isOk());
	}

	@Test
	void deletingADraftKeepsTheEntryAndItsLines() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));

		deleteDraft(id, versionOf(id)).andExpect(status().isNoContent());

		assertThat(count("select count(*) from accounting.journal_entry where id = " + id
				+ " and status = 'DRAFT' and deleted_at is not null and deleted_by_sub = 'deleter'")).isEqualTo(1);
		assertThat(count("select count(*) from accounting.journal_line where entry_id = " + id
				+ " and deleted_at is not null and deleted_by_sub = 'deleter'")).isEqualTo(2);
	}

	@Test
	void aDeletedDraftLooksMissingEverywhere() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		long version = versionOf(id);
		deleteDraft(id, version).andExpect(status().isNoContent());

		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andExpect(problem(404, "not-found"));
		update(id, version, line(cash, "1", "0")).andExpect(problem(404, "not-found"));
		deleteDraft(id, version).andExpect(problem(404, "not-found"));
		postEntry(id, version, "checker").andExpect(problem(404, "not-found"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", READ, POST))
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + DATE + "\"}"))
				.andExpect(problem(404, "not-found"));
		mvc.perform(get("/api/accounting/journal-entries").with(as("u", READ))).andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/accounting/journal-entries").param("accountId", String.valueOf(cash)).with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void removedLinesDoNotMatchTheAccountFilter() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		update(id, versionOf(id), line(sales, "0", "1")).andExpect(status().isOk());

		mvc.perform(get("/api/accounting/journal-entries").param("accountId", String.valueOf(cash)).with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/accounting/journal-entries").param("accountId", String.valueOf(sales)).with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(1));
	}

	@Test
	void aReversalAndACopyCarryOnlyLiveLines() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		update(id, versionOf(id), line(cash, "9", "0"), line(sales, "0", "9")).andExpect(status().isOk());
		postEntry(id, "checker").andExpect(status().isOk());

		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", READ, POST, WRITE))
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + DATE + "\", \"copyToDraft\": true}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reversal.lines", hasSize(2)))
				.andExpect(jsonPath("$.reversal.lines[0].credit").value("9.00"));
		mvc.perform(get("/api/accounting/journal-entries").param("status", "DRAFT").with(as("u", READ)))
				.andExpect(jsonPath("$.items[0].totalDebit").value("9.00"));
	}

	@Test
	void aDraftOnADeactivatedAccountCanStillBeEditedAndDeleted() throws Exception {
		long edited = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		long deleted = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		inTransaction(() -> sql("update accounting.account set active = false where id = :id", cash));

		update(edited, versionOf(edited), line(sales, "0", "1")).andExpect(status().isOk());
		deleteDraft(deleted, versionOf(deleted)).andExpect(status().isNoContent());
	}

	@Test
	void anEntryEditedThenPostedPassesTheBalanceGuardOnItsLiveLines() throws Exception {
		// Unbalanced on purpose: if the guard counted this removed line, the post would fail.
		long id = draft("maker", DATE, line(cash, "5", "0"));
		update(id, versionOf(id), line(cash, "3", "0"), line(sales, "0", "3")).andExpect(status().isOk());

		postEntry(id, "checker").andExpect(status().isOk());
	}

	@Test
	void theApiRoleHasNoDeleteGrant() {
		assertThat(jdbc.queryForObject("select has_table_privilege('app_runtime', 'accounting.journal_line', 'DELETE')",
				Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("select has_table_privilege('app_runtime', 'accounting.journal_entry', 'DELETE')",
				Boolean.class)).isFalse();
	}

	@Test
	void theApiRoleCannotDeleteADraftOrItsLines() {
		long entry = inTransaction(() -> {
			long id = insertEntry(insertPeriod(YEAR + 1, 1), "DRAFT");
			insertLine(id, 1, insertAccount("A", true, true), "1.00", "0");
			return id;
		});
		assertThatThrownBy(() -> inTransaction(() -> sql("delete from accounting.journal_line where entry_id = :id", entry)))
				.hasStackTraceContaining("permission denied");
		assertThatThrownBy(() -> inTransaction(() -> sql("delete from accounting.journal_entry where id = :id", entry)))
				.hasStackTraceContaining("permission denied");
	}

	@Test
	void theOwnerCannotDeleteOrTruncateEither() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		try (Connection owner = owner(); Statement statement = owner.createStatement()) {
			assertThatThrownBy(() -> statement.executeUpdate("delete from accounting.journal_line where entry_id = " + id))
					.hasMessageContaining("cannot be deleted");
			assertThatThrownBy(() -> statement.executeUpdate("delete from accounting.journal_entry where id = " + id))
					.hasMessageContaining("cannot be deleted");
			assertThatThrownBy(() -> statement.executeUpdate("truncate accounting.journal_line"))
					.hasMessageContaining("cannot be truncated");
			assertThatThrownBy(() -> statement.executeUpdate("truncate accounting.journal_entry cascade"))
					.hasMessageContaining("cannot be truncated");
		}
	}

	@Test
	void aRemovedRowCannotBeChangedOrRevived() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		deleteDraft(id, versionOf(id)).andExpect(status().isNoContent());

		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set deleted_at = null, deleted_by_sub = null, deleted_by_name = null where id = :id", id)))
				.hasStackTraceContaining("is deleted and cannot be changed");
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_line set memo = 'x' where entry_id = :id", id)))
				.hasStackTraceContaining("is deleted and cannot be changed");
	}

	@Test
	void aLiveLineCannotJoinADeletedEntry() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		deleteDraft(id, versionOf(id)).andExpect(status().isNoContent());

		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(id, 1, cash, "1.00", "0");
			return 0;
		})).hasStackTraceContaining("is deleted");
	}

	@Test
	void aDeletedEntryCannotBePosted() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		deleteDraft(id, versionOf(id)).andExpect(status().isNoContent());

		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-X' where id = :id", id)))
				.hasStackTraceContaining("is deleted and cannot be changed");
	}

	@Test
	void postedLinesAreNotSoftDeletableEither() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker").andExpect(status().isOk());

		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_line set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where entry_id = :id", id)))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", id)))
				.hasStackTraceContaining("cannot be changed");
	}

	@Test
	void whoAndWhenAreAllOrNothing() {
		long entry = inTransaction(() -> insertEntry(insertPeriod(YEAR + 1, 2), "DRAFT"));
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set deleted_at = now() where id = :id", entry)))
				.hasStackTraceContaining("check");
		assertThatCode(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", entry)))
				.doesNotThrowAnyException();
	}

}
```

Add to `ReportTests.java`, using its `cash`, `sales`, `trialBalance`, `ledger` helpers and adding `import org.springframework.http.MediaType` only if not present (it is):

```java
	private void edit(long id, String... lines) throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/accounting/journal-entries/" + id)
				.with(as("editor", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-01-10", lines).replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
	}

	@Test
	void anEntryEditedBeforePostingCountsOnlyItsLiveLines() throws Exception {
		long id = draft("maker", YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		edit(id, line(cash, "30.00", "0"), line(sales, "0", "30.00"));
		postEntry(id, "checker").andExpect(status().isOk());

		trialBalance(YEAR + "-12-31")
				.andExpect(jsonPath("$.rows[0].debit").value("30.00"))
				.andExpect(jsonPath("$.totalDebit").value("30.00"))
				.andExpect(jsonPath("$.totalCredit").value("30.00"));
		ledger(cash, YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(jsonPath("$.openingBalance").value("0.00"))
				.andExpect(jsonPath("$.lines", hasSize(1)))
				.andExpect(jsonPath("$.lines[0].debit").value("30.00"));
		ledger(cash, YEAR + "-02-01", YEAR + "-12-31")
				.andExpect(jsonPath("$.openingBalance").value("30.00"));
	}
```

Add to `PeriodTests.java` (it has `periodId(int)`; use `insertEntry` then a native soft delete):

```java
	@Test
	void aDeletedDraftDoesNotBlockClosing() throws Exception {
		long id = periodId(2);
		long entry = inTransaction(() -> insertEntry(id, "DRAFT"));
		inTransaction(() -> sql(
				"update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", entry));

		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE))).andExpect(status().isOk());
	}
```

Add to `SetupAndAccountsTests.java`:

```java
	@Test
	void anAccountUsedOnlyByADeletedDraftCanChangeTypeButCannotBeDeleted() throws Exception {
		setup();
		long cash = accountId("1110");
		inTransaction(() -> {
			long period = ((Number) entityManager.createNativeQuery(
					"select min(id) from accounting.fiscal_period").getSingleResult()).longValue();
			long entry = insertEntry(period, "DRAFT");
			insertLine(entry, 1, cash, "1.00", "0");
			sql("update accounting.journal_line set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where entry_id = :id", entry);
			return sql("update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", entry);
		});

		mvc.perform(put("/api/accounting/accounts/" + cash).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1110", "EXPENSE", accountId("1100"), true)))
				.andExpect(status().isOk());
		mvc.perform(delete("/api/accounting/accounts/" + cash).with(admin())).andExpect(problem(409, "account-in-use"))
				.andExpect(jsonPath("$.detail").value("An account used by journal lines, even in deleted drafts, or with child accounts cannot be deleted"));
	}
```

Change `DatabaseGuardTests.java`:
- In `postedLinesCannotBeChangedOrDeleted`, `postedEntryCannotBeChangedOrDeleted` and `reversedEntryAndItsLinesCannotBeChangedOrDeleted`, the four assertions that run `delete from` now expect `.hasStackTraceContaining("permission denied")`.
- Replace the last two lines of `draftsStayEditable`'s lambda with:

```java
			sql("update accounting.journal_line set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where entry_id = :id", entry);
			return sql("update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", entry);
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='NoHardDeleteTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. Native SQL reports `column "deleted_at" does not exist`.

- [ ] **Step 3: Write the migration**

Create `V20261103_2__ledger_hardening.sql`:

```sql
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
```

- [ ] **Step 4: Change the entities**

In `JournalLine.java`, add imports `java.time.Instant` and `org.hibernate.annotations.SQLRestriction`, annotate the class, and add the fields and method:

```java
@Entity
@Table(name = "journal_line", schema = "accounting")
@SQLRestriction("deleted_at is null")
class JournalLine {
```

```java
	@Column(name = "deleted_at")
	private Instant deletedAt;

	@Column(name = "deleted_by_sub", length = 100)
	private String deletedBySub;

	@Column(name = "deleted_by_name", length = 200)
	private String deletedByName;
```

```java
	void markDeleted(Actor actor, Instant at) {
		this.deletedAt = at;
		this.deletedBySub = actor.sub();
		this.deletedByName = actor.name();
	}
```

In `JournalEntry.java`, add import `org.hibernate.annotations.SQLRestriction`, annotate the class with `@SQLRestriction("deleted_at is null")`, add the same three columns after `postedAt`, replace the `lines` mapping, and add the two methods:

```java
	// No REMOVE and no orphanRemoval: Hibernate must have no path to deleting a line.
	@OneToMany(mappedBy = "entry", cascade = { CascadeType.PERSIST, CascadeType.MERGE })
	@OrderBy("lineNo")
	@BatchSize(size = 200)
	@SQLRestriction("deleted_at is null")
	private List<JournalLine> lines = new ArrayList<>();
```

```java
	/** Marks every live line removed and empties the list; flush before adding lines, Hibernate inserts before it updates. */
	void removeLines(Actor actor, Instant at) {
		lines.forEach(line -> line.markDeleted(actor, at));
		lines.clear();
	}

	void markDeleted(Actor actor, Instant at) {
		this.deletedAt = at;
		this.deletedBySub = actor.sub();
		this.deletedByName = actor.name();
		removeLines(actor, at);
	}
```

- [ ] **Step 5: Change the services and native queries**

`JournalService.update`, replacing lines 87-94:

```java
		List<ValidLine> lines = validated(request.lines());
		Actor actor = Actor.current();
		Instant now = clock.instant();
		entry.edit(request.date(), periodOf(request.date()), request.reference(), request.description(), actor, now);
		entry.removeLines(actor, now);
		// Hibernate inserts before it updates, which would trip the unique index on live line numbers.
		entries.flush();
		lines.forEach(line -> entry.addLine(line.accountId(), line.side(), line.memo()));
		return response(entries.saveAndFlush(entry));
```

`JournalService.delete`, replacing `entries.delete(entry);`:

```java
		entry.markDeleted(Actor.current(), clock.instant());
```

Add `import java.time.Instant;` to `JournalService.java`.

`ReportService.java`: add a filter in all three native queries.
- Trial balance: `where e.status in ('POSTED', 'REVERSED') and e.entry_date <= :asOf and l.deleted_at is null`
- Opening balance: `where l.account_id = :account and e.status in ('POSTED', 'REVERSED') and e.entry_date < :from and l.deleted_at is null`
- Lines: `where l.account_id = :account and e.status in ('POSTED', 'REVERSED') and l.deleted_at is null`

`FiscalPeriodRepository.countDrafts`:

```java
	@Query(nativeQuery = true, value = """
			select count(*) from accounting.journal_entry
			where period_id = :id and status = 'DRAFT' and deleted_at is null
			""")
	long countDrafts(@Param("id") long id);
```

`AccountRepository`:

```java
	/** Removed lines count: their foreign key still blocks deleting the account. */
	@Query(nativeQuery = true, value = "select exists (select 1 from accounting.journal_line where account_id = :id)")
	boolean hasLines(@Param("id") long id);

	@Query(nativeQuery = true, value = """
			select exists (select 1 from accounting.journal_line where account_id = :id and deleted_at is null)
			""")
	boolean hasLiveLines(@Param("id") long id);
```

`AccountService`: `if (structureChanges && accounts.hasLiveLines(id)) {`, and the delete refusal text becomes:

```java
			throw AccountingException.conflict("account-in-use",
					"An account used by journal lines, even in deleted drafts, or with child accounts cannot be deleted");
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -pl backend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`.

If `removedLinesDoNotMatchTheAccountFilter` fails, `@SQLRestriction` does not reach the Criteria subquery. In `JournalService.filter`, change the subquery's `where` to also hold `cb.isNull(line.get("deletedAt"))`, and rerun.

If the migration fails with "constraint ... does not exist", find the real name with `select conname from pg_constraint where conrelid = 'accounting.journal_line'::regclass` and correct `DROP CONSTRAINT`.

- [ ] **Step 7: Commit**

```bash
git add backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql backend/accounting/src/main/java/com/example/accounting/JournalLine.java backend/accounting/src/main/java/com/example/accounting/JournalEntry.java backend/accounting/src/main/java/com/example/accounting/JournalService.java backend/accounting/src/main/java/com/example/accounting/ReportService.java backend/accounting/src/main/java/com/example/accounting/FiscalPeriodRepository.java backend/accounting/src/main/java/com/example/accounting/AccountRepository.java backend/accounting/src/main/java/com/example/accounting/AccountService.java backend/accounting/src/test/java/com/example/accounting/NoHardDeleteTests.java backend/accounting/src/test/java/com/example/accounting/DatabaseGuardTests.java backend/accounting/src/test/java/com/example/accounting/ReportTests.java backend/accounting/src/test/java/com/example/accounting/PeriodTests.java backend/accounting/src/test/java/com/example/accounting/SetupAndAccountsTests.java
git commit -m "Keep every journal line: drafts soft delete, the database refuses hard deletes"
```

---

### Task 2: Maker/checker covers every editor

**Files:**
- Modify: `backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalEntry.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalRules.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/PostingService.java:52-56`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalService.java:97-102`
- Modify: `frontend/accounting/src/main/resources/web/accounting/entry.zul:72`
- Modify: `backend/accounting/src/test/java/com/example/accounting/JournalRulesTests.java:73-79`
- Modify: `backend/accounting/src/test/java/com/example/accounting/PostingTests.java`
- Create: `backend/accounting/src/test/java/com/example/accounting/LegacyDataMigrationTests.java`

**Interfaces:**
- Produces: `JournalEntry.getPreparedBySubs(): List<String>`, `JournalRules.mayPost(String posterSub, Collection<String> preparedBySubs, boolean requireSeparatePoster)`.

- [ ] **Step 1: Write the failing tests**

Replace `makerCheckerMatrix` in `JournalRulesTests.java`:

```java
	@Test
	void makerCheckerMatrix() {
		assertThat(JournalRules.mayPost("poster", List.of("maker", "editor"), true)).isTrue();
		assertThat(JournalRules.mayPost("maker", List.of("maker", "editor"), true)).isFalse();
		assertThat(JournalRules.mayPost("editor", List.of("maker", "editor"), true)).isFalse();
		assertThat(JournalRules.mayPost("maker", List.of("maker"), false)).isTrue();
	}
```

Add to `PostingTests.java`, which has `cash`, `sales`, `setSeparatePoster`:

```java
	private void editAs(String sub, long id) throws Exception {
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as(sub, READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, line(cash, "3", "0"), line(sales, "0", "3")).replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
	}

	@Test
	void anyoneWhoEverEditedADraftCannotPostIt() throws Exception {
		long id = balancedDraft("maker", DATE);
		editAs("early-editor", id);
		editAs("late-editor", id);

		postEntry(id, "maker").andExpect(problem(403, "separate-poster-required"))
				.andExpect(jsonPath("$.detail").value("You prepared or edited this entry; another user must post it"));
		postEntry(id, "early-editor").andExpect(problem(403, "separate-poster-required"));
		postEntry(id, "late-editor").andExpect(problem(403, "separate-poster-required"));
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("early-editor", READ)))
				.andExpect(jsonPath("$.posterBlocked").value(true));
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("checker", READ)))
				.andExpect(jsonPath("$.posterBlocked").value(false));

		postEntry(id, "checker").andExpect(status().isOk());
	}

	@Test
	void anEarlierEditorMayPostWhenTheSettingIsOff() throws Exception {
		long id = balancedDraft("maker", DATE);
		editAs("early-editor", id);
		editAs("late-editor", id);
		setSeparatePoster(false);

		postEntry(id, "early-editor").andExpect(status().isOk());
	}

	@Test
	void editingTwiceRecordsAnEditorOnce() throws Exception {
		long id = balancedDraft("maker", DATE);
		editAs("editor", id);
		editAs("editor", id);

		String subs = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(prepared_by_subs as text) from accounting.journal_entry where id = :id")
				.setParameter("id", id).getSingleResult());
		assertThat(subs).isEqualTo("{maker,editor}");
	}
```

Add the imports the class is missing from `assertThat`, `get`, `put`, `jsonPath`, `MediaType`.

Create `LegacyDataMigrationTests.java`:

```java
package com.example.accounting;

import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;

/** Migrates a database that already holds Phase 1 data, because the other tests start from an empty one. */
class LegacyDataMigrationTests extends AccountingTestSupport {

	private static final String TENANT = "'" + UUID.randomUUID() + "'";

	@Autowired
	PostgreSQLContainer postgres;

	private Flyway flyway(String url, MigrationVersion target) {
		return Flyway.configure().dataSource(url, postgres.getUsername(), postgres.getPassword())
				.locations("classpath:db/migration/accounting").target(target).load();
	}

	private static String entry(String status, String entryNo, String maker, String editor, long period) {
		return """
				insert into accounting.journal_entry (tenant_id, entry_no, entry_date, period_id, description, status,
				    created_by_sub, created_by_name, created_at, last_modified_by_sub, last_modified_by_name,
				    last_modified_at, version)
				values (%s, %s, '2026-03-15', %d, 'legacy', '%s', '%s', 'n', now(), '%s', 'n', now(), 0) returning id
				""".formatted(TENANT, entryNo == null ? "null" : "'" + entryNo + "'", period, status, maker, editor);
	}

	private static long id(Statement statement, String sql) throws Exception {
		try (ResultSet found = statement.executeQuery(sql)) {
			found.next();
			return found.getLong(1);
		}
	}

	private static String text(Statement statement, String sql) throws Exception {
		try (ResultSet found = statement.executeQuery(sql)) {
			found.next();
			return found.getString(1);
		}
	}

	@Test
	void draftsGetTheirKnownPreparersAndPostedRowsStayFrozen() throws Exception {
		try (Connection admin = DriverManager.getConnection(postgres.getJdbcUrl(), postgres.getUsername(), postgres.getPassword());
				Statement statement = admin.createStatement()) {
			statement.execute("create database legacy");
		}
		String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/legacy");
		flyway(url, MigrationVersion.fromVersion("20261102.4")).migrate();

		long edited;
		long own;
		long posted;
		try (Connection legacy = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
				Statement statement = legacy.createStatement()) {
			long a = id(statement, "insert into accounting.account (tenant_id, code, name, type, postable, active) values ("
					+ TENANT + ", 'A', 'A', 'ASSET', true, true) returning id");
			long b = id(statement, "insert into accounting.account (tenant_id, code, name, type, postable, active) values ("
					+ TENANT + ", 'B', 'B', 'ASSET', true, true) returning id");
			long period = id(statement, "insert into accounting.fiscal_period (tenant_id, year, month, start_date, end_date, status) values ("
					+ TENANT + ", 2026, 3, '2026-03-01', '2026-03-31', 'OPEN') returning id");
			edited = id(statement, entry("DRAFT", null, "maker", "editor", period));
			own = id(statement, entry("DRAFT", null, "maker", "maker", period));
			posted = id(statement, entry("DRAFT", null, "maker", "maker", period));
			statement.execute("insert into accounting.journal_line (tenant_id, entry_id, line_no, account_id, debit, credit) values ("
					+ TENANT + ", " + posted + ", 1, " + a + ", 1, 0), (" + TENANT + ", " + posted + ", 2, " + b + ", 0, 1)");
			statement.execute("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = " + posted);
		}

		flyway(url, MigrationVersion.LATEST).migrate();

		try (Connection legacy = DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
				Statement statement = legacy.createStatement()) {
			assertThat(text(statement, "select cast(prepared_by_subs as text) from accounting.journal_entry where id = " + edited))
					.isEqualTo("{maker,editor}");
			assertThat(text(statement, "select cast(prepared_by_subs as text) from accounting.journal_entry where id = " + own))
					.isEqualTo("{maker}");
			assertThat(text(statement, "select cast(prepared_by_subs as text) from accounting.journal_entry where id = " + posted))
					.isEqualTo("{}");
			assertThat(text(statement, "select status from accounting.journal_entry where id = " + posted)).isEqualTo("POSTED");
		}
	}

}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='JournalRulesTests,PostingTests,LegacyDataMigrationTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at test compilation, because `mayPost` has no such signature.

- [ ] **Step 3: Implement**

In the migration, under `-- Columns`, add:

```sql
ALTER TABLE accounting.journal_entry
    ADD COLUMN prepared_by_subs VARCHAR(100)[] NOT NULL DEFAULT '{}';
```

Under `-- Backfill`, add:

```sql
-- Drafts only: updating a posted row would trip entry_immutable, and nothing reads this column after posting.
-- Earlier editors were never recorded, so they cannot be recovered.
UPDATE accounting.journal_entry
SET prepared_by_subs = CASE WHEN created_by_sub = last_modified_by_sub THEN ARRAY[created_by_sub]
                            ELSE ARRAY[created_by_sub, last_modified_by_sub] END
WHERE status = 'DRAFT';
```

In `JournalEntry.java`, add imports `org.hibernate.annotations.JdbcTypeCode` and `org.hibernate.type.SqlTypes`, the field, and change `edit` and add a getter:

```java
	@JdbcTypeCode(SqlTypes.ARRAY)
	@Column(name = "prepared_by_subs", nullable = false)
	private List<String> preparedBySubs = new ArrayList<>();
```

At the end of `edit(...)`:

```java
		if (!preparedBySubs.contains(actor.sub())) {
			preparedBySubs.add(actor.sub());
		}
```

```java
	List<String> getPreparedBySubs() {
		return preparedBySubs;
	}
```

If `ddl-auto=validate` refuses the mapping, change the field to `String[]` with `@Column(columnDefinition = "varchar(100)[]")`, keep `@JdbcTypeCode(SqlTypes.ARRAY)`, and have `getPreparedBySubs` return `List.of(preparedBySubs)`.

`JournalRules.java`, adding `import java.util.Collection;`:

```java
	static boolean mayPost(String posterSub, Collection<String> preparedBySubs, boolean requireSeparatePoster) {
		return !requireSeparatePoster || !preparedBySubs.contains(posterSub);
	}
```

`PostingService.post`:

```java
		if (!JournalRules.mayPost(actor.sub(), entry.getPreparedBySubs(), current.isRequireSeparatePoster())) {
			throw AccountingException.forbidden("separate-poster-required",
					"You prepared or edited this entry; another user must post it");
		}
```

`JournalService.response`:

```java
		boolean blocked = entry.getStatus() == EntryStatus.DRAFT
				&& !JournalRules.mayPost(Actor.current().sub(), entry.getPreparedBySubs(), separate);
```

`entry.zul:72`: `value="You prepared or edited this entry; another user must post it"`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl backend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql backend/accounting/src/main/java/com/example/accounting/JournalEntry.java backend/accounting/src/main/java/com/example/accounting/JournalRules.java backend/accounting/src/main/java/com/example/accounting/PostingService.java backend/accounting/src/main/java/com/example/accounting/JournalService.java frontend/accounting/src/main/resources/web/accounting/entry.zul backend/accounting/src/test/java/com/example/accounting/JournalRulesTests.java backend/accounting/src/test/java/com/example/accounting/PostingTests.java backend/accounting/src/test/java/com/example/accounting/LegacyDataMigrationTests.java
git commit -m "Refuse posting by anyone who ever edited the draft"
```

---

### Task 3: Database rules for numbers, reversals and posting periods

**Files:**
- Modify: `backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql`
- Modify: `backend/accounting/src/test/java/com/example/accounting/DatabaseGuardTests.java`
- Modify: `backend/accounting/src/test/java/com/example/accounting/PostingTests.java`

- [ ] **Step 1: Write the failing tests**

Add to `DatabaseGuardTests.java`:

```java
	@Test
	void onlyADraftHasNoNumber() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			return sql("update accounting.journal_entry set entry_no = 'JE-9' where id = :id", entry);
		})).hasStackTraceContaining("check");
		assertThatThrownBy(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 2), "DRAFT");
			insertLine(entry, 1, insertAccount("D", true, true), "1.00", "0");
			insertLine(entry, 2, insertAccount("E", true, true), "0", "1.00");
			return sql("update accounting.journal_entry set status = 'POSTED' where id = :id", entry);
		})).hasStackTraceContaining("check");
	}

	@Test
	void anEntryIsReversedAtMostOnce() {
		long[] posted = postedEntry();
		long period = inTransaction(() -> ((Number) entityManager.createNativeQuery(
				"select period_id from accounting.journal_entry where id = :id").setParameter("id", posted[0]).getSingleResult()).longValue());
		assertThatThrownBy(() -> inTransaction(() -> {
			long first = insertEntry(period, "DRAFT");
			long second = insertEntry(period, "DRAFT");
			sql("update accounting.journal_entry set reversal_of = " + posted[0] + " where id = :id", first);
			return sql("update accounting.journal_entry set reversal_of = " + posted[0] + " where id = :id", second);
		})).hasStackTraceContaining("duplicate key");
	}

	@Test
	void reversalColumnsMustPointAtAnEntryOfThisTenant() {
		long[] foreign = new long[] { inTenantTransaction(UUID.randomUUID(), "other",
				() -> insertEntry(insertPeriod(YEAR, 1), "DRAFT")) };
		long[] posted = postedEntry();
		long period = inTransaction(() -> ((Number) entityManager.createNativeQuery(
				"select period_id from accounting.journal_entry where id = :id").setParameter("id", posted[0]).getSingleResult()).longValue());
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set reversal_of = " + foreign[0] + " where id = :id", insertEntry(period, "DRAFT"))))
				.hasStackTraceContaining("foreign key");
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'REVERSED', reversed_by = " + foreign[0] + ", version = version + 1 where id = :id", posted[0])))
				.hasStackTraceContaining("foreign key");
	}

	@Test
	void postingIntoAClosedPeriodIsRefused() {
		long[] draft = inTransaction(() -> {
			long period = insertPeriod(YEAR, 1);
			long entry = insertEntry(period, "DRAFT");
			insertLine(entry, 1, insertAccount("A", true, true), "1.00", "0");
			insertLine(entry, 2, insertAccount("B", true, true), "0", "1.00");
			sql("update accounting.fiscal_period set status = 'CLOSED' where id = :id", period);
			return new long[] { entry, period };
		});

		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id", draft[0])))
				.hasStackTraceContaining("is not open");
		assertThatThrownBy(() -> inTransaction(() -> insertEntry(draft[1], "POSTED")))
				.hasStackTraceContaining("is not open");
	}

	@Test
	void aPostedEntryInAClosedPeriodCanStillBeMarkedReversed() {
		long[] posted = postedEntry();
		inTransaction(() -> sql(
				"update accounting.fiscal_period set status = 'CLOSED' where id = (select period_id from accounting.journal_entry where id = :id)", posted[0]));

		assertThatCode(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'REVERSED', reversed_by = id, version = version + 1 where id = :id", posted[0])))
				.doesNotThrowAnyException();
	}
```

Add to `PostingTests.java`:

```java
	@Test
	void aReversalCanItselfBeReversed() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker").andExpect(status().isOk());
		String reversed = mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", READ, POST))
						.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + DATE + "\"}"))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long reversal = ((Number) JsonPath.read(reversed, "$.reversal.id")).longValue();

		mvc.perform(post("/api/accounting/journal-entries/" + reversal + "/reverse").with(as("checker", READ, POST))
						.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + DATE + "\"}"))
				.andExpect(status().isOk());
	}
```

Skip adding this one if a test in `PostingTests` already reverses a reversal; confirm with `grep -n 'reversal' PostingTests.java`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='DatabaseGuardTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the new tests, with no exception thrown where one is expected.

- [ ] **Step 3: Write the rules**

Under `-- Constraints and index`, add:

```sql
ALTER TABLE accounting.journal_entry
    ADD CHECK ((status = 'DRAFT') = (entry_no IS NULL)),
    -- A reversal of a reversal has a different reversal_of; NULLs do not collide.
    ADD UNIQUE (tenant_id, reversal_of),
    ADD FOREIGN KEY (tenant_id, reversal_of) REFERENCES accounting.journal_entry (tenant_id, id),
    ADD FOREIGN KEY (tenant_id, reversed_by) REFERENCES accounting.journal_entry (tenant_id, id);
```

Under `-- Guards`, add:

```sql
CREATE FUNCTION accounting.guard_posting_period() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    period_status VARCHAR;
BEGIN
    IF NEW.status = 'POSTED' AND (TG_OP = 'INSERT' OR OLD.status = 'DRAFT') THEN
        SELECT status INTO period_status FROM accounting.fiscal_period
        WHERE tenant_id = NEW.tenant_id AND id = NEW.period_id FOR SHARE;
        -- No such period in this tenant: the composite foreign key rejects it.
        IF FOUND AND period_status <> 'OPEN' THEN
            RAISE EXCEPTION 'period % is not open, so journal entry % cannot be posted', NEW.period_id, NEW.id;
        END IF;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER posting_period
    BEFORE INSERT OR UPDATE ON accounting.journal_entry
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_posting_period();
```

- [ ] **Step 4: Fix the fixtures the new rules refuse**

In `DatabaseGuardTests.java`:
- `postedUnbalancedEntryIsRejectedAtCommit`: `set status = 'POSTED', entry_no = 'JE-1' where id = :id`.
- `postedEntryWithFewerThanTwoLinesIsRejectedAtCommit`, replacing its body:

```java
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id",
				insertEntry(insertPeriod(YEAR, 1), "DRAFT")))).hasStackTraceContaining("is not balanced");
```

- Every `reversed_by = 1` becomes `reversed_by = id`. Self-reference satisfies the foreign key.
- `aLineRacingAPostWaitsAndIsRejected`: `set status = 'POSTED', entry_no = 'JE-1' where id = :id`.

Then check nothing else writes such rows: `grep -rn "status = 'POSTED'\|\"POSTED\"\|reversed_by = " backend/accounting/src/test/java`.

- [ ] **Step 5: Run tests to verify they pass**

Run: `./mvnw -pl backend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`, including `LegacyDataMigrationTests`, whose rows satisfy the new rules.

- [ ] **Step 6: Commit**

```bash
git add backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql backend/accounting/src/test/java/com/example/accounting/DatabaseGuardTests.java backend/accounting/src/test/java/com/example/accounting/PostingTests.java
git commit -m "Refuse unnumbered posts, double reversals and posts into closed periods in the database"
```

---

### Task 4: One row lock for changes

**Files:**
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalEntryRepository.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalService.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/PostingService.java:46,66`
- Modify: `backend/accounting/src/test/java/com/example/accounting/PostingConcurrencyTests.java`

**Interfaces:**
- Produces: `JournalEntryRepository.lockForChange(long): Optional<JournalEntry>`, `JournalService.loadForChange(long): JournalEntry`.

- [ ] **Step 1: Write the failing tests**

Add to `PostingConcurrencyTests.java`, with `import java.util.concurrent.TimeUnit;`, and `delete` from `MockMvcRequestBuilders`:

```java
	private MockHttpServletResponse deleteDraft(long id, long version) throws Exception {
		return mvc.perform(delete("/api/accounting/journal-entries/" + id).param("version", String.valueOf(version))
				.with(as("deleter", WRITE))).andReturn().getResponse();
	}

	/** Holds the entry's row until {@code release}, then commits; {@code held} says the lock is taken. */
	private Future<Integer> holding(ExecutorService pool, long id, String statement, CountDownLatch held, CountDownLatch release) {
		return pool.submit(() -> inTransaction(() -> {
			int changed = sql(statement, id);
			held.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return changed;
		}));
	}

	@Test
	void aPostWaitingOnADeleteGetsNotFound() throws Exception {
		long id = balancedDraft("maker", DATE);
		long version = versionOf(id);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Integer> deleter = holding(pool, id,
					"update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", held, release);
			held.await();
			Future<MockHttpServletResponse> poster = pool.submit(() -> postEntry(id, version, "checker").andReturn().getResponse());
			Thread.sleep(300);
			assertThat(poster.isDone()).as("the post waits on the row lock").isFalse();
			release.countDown();
			assertThat(deleter.get()).isEqualTo(1);
			assertThat(poster.get().getStatus()).isEqualTo(404);
		}
	}

	@Test
	void aDeleteWaitingOnAPostGetsNotDraft() throws Exception {
		long id = balancedDraft("maker", DATE);
		long version = versionOf(id);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Integer> poster = holding(pool, id,
					"update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-RACE' where id = :id", held, release);
			held.await();
			Future<MockHttpServletResponse> deleter = pool.submit(() -> deleteDraft(id, version));
			Thread.sleep(300);
			assertThat(deleter.isDone()).as("the delete waits on the row lock").isFalse();
			release.countDown();
			assertThat(poster.get()).isEqualTo(1);
			assertThat(deleter.get().getStatus()).isEqualTo(409);
			assertThat(deleter.get().getContentAsString()).contains("not-draft");
		}
	}

	@Test
	void aDeleteRacingAPostNeverAnswers500() throws Exception {
		for (int i = 0; i < 10; i++) {
			long id = balancedDraft("maker", DATE);
			long version = versionOf(id);
			try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
				CountDownLatch go = new CountDownLatch(1);
				Future<MockHttpServletResponse> poster = pool.submit(() -> {
					go.await();
					return postEntry(id, version, "checker").andReturn().getResponse();
				});
				Future<MockHttpServletResponse> deleter = pool.submit(() -> {
					go.await();
					return deleteDraft(id, version);
				});
				go.countDown();
				assertThat(List.of(poster.get().getStatus(), deleter.get().getStatus()))
						.isIn(List.of(200, 409), List.of(404, 204));
			}
		}
	}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='PostingConcurrencyTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `the post waits on the row lock` does not hold, because `load` takes no lock.

- [ ] **Step 3: Implement**

`JournalEntryRepository.java`:

```java
package com.example.accounting;

import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {

	/** A racing change waits here, then sees the committed row: Postgres re-checks the WHERE, so a deleted entry is empty. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select e from JournalEntry e where e.id = :id")
	Optional<JournalEntry> lockForChange(@Param("id") long id);

}
```

`JournalService.java`, adding beside `load`:

```java
	/** Lock order everywhere is entry, then period, then sequence; closing a period takes no entry locks, so no cycle. */
	public JournalEntry loadForChange(long id) {
		return entries.lockForChange(id).orElseThrow(() -> new AccountingNotFoundException("Journal entry " + id));
	}
```

Use it in `update` and `delete` (`JournalEntry entry = loadForChange(id);`). `get` keeps `load`.

`PostingService.java`: `journal.loadForChange(id)` in both `post` and `reverse`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl backend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`, with no `invariant-violated` in the concurrency tests' logs.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting/src/main/java/com/example/accounting/JournalEntryRepository.java backend/accounting/src/main/java/com/example/accounting/JournalService.java backend/accounting/src/main/java/com/example/accounting/PostingService.java backend/accounting/src/test/java/com/example/accounting/PostingConcurrencyTests.java
git commit -m "Lock the entry row for post, update, delete and reverse"
```

---

### Task 5: Record account changes and serve the audit log

**Files:**
- Create: `backend/iam/src/main/resources/db/migration/iam/V20261103_1__add_audit_read_permission.sql`
- Modify: `backend/iam/src/test/java/com/example/iam/IamApiTests.java:68-72,84-87,209-211`
- Modify: `backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AuditAction.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AuditLog.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AuditLogRepository.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/AccountService.java`
- Modify: `backend/accounting/src/main/java/com/example/accounting/JournalService.java:50-60`
- Create: `backend/accounting/src/main/java/com/example/accounting/AuditRow.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/AuditPage.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/AuditService.java`
- Create: `backend/accounting/src/main/java/com/example/accounting/AuditController.java`
- Modify: `backend/accounting/src/test/java/com/example/accounting/AccountingTestSupport.java:66-74`
- Create: `backend/accounting/src/test/java/com/example/accounting/AuditApiTests.java`

**Interfaces:**
- Produces: `GET /api/accounting/audit?from&to&action&page&size` answering `{items: [{at, actorName, action, entity, entityId, details}], total, page, size}`; permission `accounting:audit-read`; `AuditAction.ACCOUNT_CHANGE`; `details` for it is `{"<field>": {"from": ..., "to": ...}}` for `code`, `name`, `parent`, `active`.
- Consumes: `AuditTrail.record(AuditAction, String, Long, Map<String, ?>)`.

- [ ] **Step 1: Write the failing tests**

In `AccountingTestSupport.java`, add `static final String AUDIT_READ = "accounting:audit-read";` and include it in `ALL`.

Create `AuditApiTests.java`:

```java
package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditApiTests extends AccountingTestSupport {

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
	}

	private ResultActions changes() throws Exception {
		return mvc.perform(get("/api/accounting/audit").param("action", "ACCOUNT_CHANGE").with(as("auditor", AUDIT_READ)));
	}

	private static String body(String code, String name, Long parentId, Boolean active) {
		String activeField = active == null ? "" : ", \"active\": " + active;
		return """
				{"code": "%s", "name": "%s", "type": "ASSET", "parentId": %s, "postable": true%s}
				""".formatted(code, name, parentId, activeField);
	}

	private ResultActions putAccount(long id, String json) throws Exception {
		return mvc.perform(put("/api/accounting/accounts/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Inserts a row with this test tenant's id; call with the JSON text for details. */
	private long insertAudit(String at, String details) {
		return inTransaction(() -> ((Number) entityManager.createNativeQuery("""
				insert into accounting.audit_log (tenant_id, action, entity, entity_id, actor_sub, actor_name, at, details)
				values (cast(:tenant as uuid), 'POST', 'journal_entry', 1, 's', 'Akuntan', cast(:at as timestamptz), cast(:details as jsonb))
				returning id
				""").setParameter("tenant", tenant.toString()).setParameter("at", at).setParameter("details", details)
				.getSingleResult()).longValue());
	}

	@Test
	void aRenameRecordsOnlyWhatChanged() throws Exception {
		long cash = account("1110");
		long parent = account("1100");
		putAccount(cash, body("1110", "Kas Kecil", parent, null)).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].action").value("ACCOUNT_CHANGE"))
				.andExpect(jsonPath("$.items[0].entity").value("account"))
				.andExpect(jsonPath("$.items[0].entityId").value((int) cash))
				.andExpect(jsonPath("$.items[0].actorName").value("admin-sub-name"))
				.andExpect(jsonPath("$.items[0].details.name.from").value("Kas"))
				.andExpect(jsonPath("$.items[0].details.name.to").value("Kas Kecil"))
				.andExpect(jsonPath("$.items[0].details.code").doesNotExist())
				.andExpect(jsonPath("$.items[0].details.parent").doesNotExist())
				.andExpect(jsonPath("$.items[0].details.active").doesNotExist());
	}

	@Test
	void aPutThatChangesNothingRecordsNothing() throws Exception {
		long cash = account("1110");
		putAccount(cash, body("1110", "Kas", account("1100"), null)).andExpect(status().isOk());
		putAccount(cash, body("1110", "Kas", account("1100"), true)).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void movingToTheTopAndBackRecordsTheParentCodes() throws Exception {
		long cash = account("1110");
		long parent = account("1100");
		putAccount(cash, body("1110", "Kas", null, null)).andExpect(status().isOk());
		changes().andExpect(jsonPath("$.items[0].details.parent.from").value("1100"))
				.andExpect(jsonPath("$.items[0].details.parent.to").value(nullValue()));

		putAccount(cash, body("1110", "Kas", parent, null)).andExpect(status().isOk());
		changes().andExpect(jsonPath("$.items[0].details.parent.from").value(nullValue()))
				.andExpect(jsonPath("$.items[0].details.parent.to").value("1100"));
	}

	@Test
	void deactivatingAndChangingTheCodeAreRecorded() throws Exception {
		long cash = account("1110");
		putAccount(cash, body("1119", "Kas", account("1100"), false)).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.items[0].details.code.from").value("1110"))
				.andExpect(jsonPath("$.items[0].details.code.to").value("1119"))
				.andExpect(jsonPath("$.items[0].details.active.from").value(true))
				.andExpect(jsonPath("$.items[0].details.active.to").value(false));
	}

	@Test
	void aRefusedChangeRecordsNothing() throws Exception {
		putAccount(account("1110"), body("1120", "Kas", account("1100"), null)).andExpect(status().is4xxClientError());

		changes().andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void largeAmountsStayPlainDecimals() throws Exception {
		insertAudit("2026-03-15T01:00:00Z", "{\"totalDebit\": 10000000.00, \"entryNo\": \"JE-1\", \"version\": 3}");

		mvc.perform(get("/api/accounting/audit").param("action", "POST").with(as("auditor", AUDIT_READ)))
				.andExpect(jsonPath("$.items[0].details.totalDebit").value("10000000.00"))
				.andExpect(jsonPath("$.items[0].details.entryNo").value("JE-1"))
				.andExpect(jsonPath("$.items[0].details.version").value(3));
	}

	@Test
	void datesAreJakartaDaysAndNewestComesFirst() throws Exception {
		insertAudit("2026-03-14T16:59:59Z", "{\"n\": \"last of the 14th\"}");
		insertAudit("2026-03-14T17:00:00Z", "{\"n\": \"first of the 15th\"}");
		insertAudit("2026-03-15T16:59:59Z", "{\"n\": \"last of the 15th\"}");
		insertAudit("2026-03-15T17:00:00Z", "{\"n\": \"first of the 16th\"}");

		mvc.perform(get("/api/accounting/audit").param("from", "2026-03-15").param("to", "2026-03-15").with(as("auditor", AUDIT_READ)))
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[0].details.n").value("last of the 15th"))
				.andExpect(jsonPath("$.items[1].details.n").value("first of the 15th"));
	}

	@Test
	void pages() throws Exception {
		for (int i = 0; i < 3; i++) {
			insertAudit("2026-03-1" + i + "T01:00:00Z", "{\"n\": " + i + "}");
		}
		mvc.perform(get("/api/accounting/audit").param("action", "POST").param("page", "1").param("size", "2").with(as("auditor", AUDIT_READ)))
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].details.n").value(0));
	}

	@Test
	void badParametersAreValidationProblems() throws Exception {
		String[][] bad = { { "size", "0" }, { "size", "201" }, { "page", "-1" }, { "action", "NOPE" }, { "from", "not-a-date" } };
		for (String[] parameter : bad) {
			mvc.perform(get("/api/accounting/audit").param(parameter[0], parameter[1]).with(as("auditor", AUDIT_READ)))
					.andExpect(problem(400, "validation"));
		}
		mvc.perform(get("/api/accounting/audit").param("from", "2026-03-16").param("to", "2026-03-15").with(as("auditor", AUDIT_READ)))
				.andExpect(problem(400, "validation"));
	}

	@Test
	void readingNeedsTheAuditPermission() throws Exception {
		mvc.perform(get("/api/accounting/audit").with(as("bookkeeper", READ, WRITE))).andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/audit").with(as("viewer", READ))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void anotherTenantSeesNothingEvenWithoutHibernatesFilter() throws Exception {
		long row = insertAudit("2026-03-15T01:00:00Z", "{}");
		UUID other = UUID.randomUUID();

		mvc.perform(get("/api/accounting/audit").with(asTenant(other, "auditor", AUDIT_READ))).andExpect(jsonPath("$.total").value(0));
		long seen = inTenantTransaction(other, "auditor", () -> ((Number) entityManager
				.createNativeQuery("select count(*) from accounting.audit_log where id = :id").setParameter("id", row)
				.getSingleResult()).longValue());
		assertThat(seen).isZero();
	}

}
```

In `IamApiTests.java`:
- `seededRolesHaveTheirPermissions`: add `"accounting:audit-read"` to the ADMIN list.
- `accountingRolesHaveTheirPermissions`: add `"accounting:audit-read"` to the ACCOUNTANT list; BOOKKEEPER unchanged.
- `rolesNeedIamRead`: the ADMIN `contains(...)` list becomes `"accounting:account-manage", "accounting:audit-read", "accounting:period-manage", "accounting:post", "accounting:read", "accounting:write", "iam:read", "task:delete", "task:read", "task:write"`.

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl backend/accounting -am test -Dtest='AuditApiTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. The endpoint answers 404 or 500 for an unknown path.

- [ ] **Step 3: Write the permission and the CHECK**

Create `iam/V20261103_1__add_audit_read_permission.sql`:

```sql
INSERT INTO iam.permission (code, description) VALUES
    ('accounting:audit-read', 'Read the accounting audit log');

INSERT INTO iam.role_permission (role, permission_code) VALUES
    ('ACCOUNTANT', 'accounting:audit-read'),
    ('ADMIN',      'accounting:audit-read');
```

In the accounting migration, under `-- Constraints and index`, add:

```sql
ALTER TABLE accounting.audit_log DROP CONSTRAINT audit_log_action_check;
ALTER TABLE accounting.audit_log ADD CONSTRAINT audit_log_action_check
    CHECK (action IN ('SETUP', 'POST', 'REVERSE', 'PERIOD_CLOSE', 'PERIOD_REOPEN', 'SETTING_CHANGE', 'ACCOUNT_CHANGE'));
```

- [ ] **Step 4: Record account changes**

`AuditAction.java`:

```java
package com.example.accounting;

public enum AuditAction {

	SETUP, POST, REVERSE, PERIOD_CLOSE, PERIOD_REOPEN, SETTING_CHANGE, ACCOUNT_CHANGE

}
```

`AccountService.java`: add `AuditTrail audit` to the fields and constructor, imports `java.util.LinkedHashMap`, `java.util.Map`, `java.util.Objects`. Replace the end of `update`:

```java
		// Unlike create, a PUT without active keeps the current value rather than reactivating the account.
		boolean active = request.active() != null ? request.active() : account.isActive();
		Map<String, Map<String, Object>> changes = changes(account, request, active);
		account.update(request.code(), request.name(), request.type(), request.parentId(), request.postable(), active);
		if (!changes.isEmpty()) {
			audit.record(AuditAction.ACCOUNT_CHANGE, "account", id, changes);
		}
		return AccountResponse.from(account);
```

Add below `delete`:

```java
	private Map<String, Map<String, Object>> changes(Account account, AccountRequest request, boolean active) {
		Map<String, Map<String, Object>> changes = new LinkedHashMap<>();
		note(changes, "code", account.getCode(), request.code());
		note(changes, "name", account.getName(), request.name());
		if (!Objects.equals(account.getParentId(), request.parentId())) {
			note(changes, "parent", codeOf(account.getParentId()), codeOf(request.parentId()));
		}
		note(changes, "active", account.isActive(), active);
		return changes;
	}

	private static void note(Map<String, Map<String, Object>> changes, String field, Object from, Object to) {
		if (!Objects.equals(from, to)) {
			// Not Map.of: a parent is null at the top level.
			Map<String, Object> change = new LinkedHashMap<>();
			change.put("from", from);
			change.put("to", to);
			changes.put(field, change);
		}
	}

	private String codeOf(Long id) {
		return id == null ? null : get(id).getCode();
	}
```

- [ ] **Step 5: Serve the log**

`AuditLog.java`, adding getters:

```java
	Long getId() {
		return id;
	}

	AuditAction getAction() {
		return action;
	}

	String getEntity() {
		return entity;
	}

	Long getEntityId() {
		return entityId;
	}

	String getActorName() {
		return actorName;
	}

	Instant getAt() {
		return at;
	}

	String getDetails() {
		return details;
	}
```

`AuditLogRepository.java`:

```java
package com.example.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface AuditLogRepository extends JpaRepository<AuditLog, Long>, JpaSpecificationExecutor<AuditLog> {
}
```

`AuditRow.java`:

```java
package com.example.accounting;

import java.time.Instant;
import java.util.Map;

public record AuditRow(Instant at, String actorName, AuditAction action, String entity, Long entityId,
		Map<String, Object> details) {
}
```

`AuditPage.java`:

```java
package com.example.accounting;

import java.util.List;

public record AuditPage(List<AuditRow> items, long total, int page, int size) {
}
```

In `JournalService.java`, extract the two checks from `list` so both lists refuse the same values, and call them from `list`:

```java
	static void checkPaging(int page, int size) {
		if (size < 1 || size > MAX_PAGE_SIZE || page < 0) {
			throw AccountingException.badRequest("validation",
					"page must not be negative and size must be between 1 and " + MAX_PAGE_SIZE);
		}
		if ((long) page * size > Integer.MAX_VALUE) {
			throw AccountingException.badRequest("validation", "page is too large");
		}
	}

	static void checkDates(LocalDate from, LocalDate to) {
		if (from != null && to != null && from.isAfter(to)) {
			throw AccountingException.badRequest("validation", "from must not be after to");
		}
	}
```

`AuditService.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import jakarta.persistence.criteria.Predicate;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;
import tools.jackson.core.type.TypeReference;
import tools.jackson.databind.DeserializationFeature;
import tools.jackson.databind.json.JsonMapper;

@Service
@Transactional(readOnly = true)
public class AuditService {

	private final AuditLogRepository repository;

	private final JsonMapper json;

	private final Clock clock;

	AuditService(AuditLogRepository repository, JsonMapper json, Clock clock) {
		this.repository = repository;
		// A double would show 10000000.00 as 1.0E7.
		this.json = json.rebuild().enable(DeserializationFeature.USE_BIG_DECIMAL_FOR_FLOATS).build();
		this.clock = clock;
	}

	public AuditPage list(LocalDate from, LocalDate to, AuditAction action, int page, int size) {
		JournalService.checkPaging(page, size);
		JournalService.checkDates(from, to);
		Page<AuditLog> result = repository.findAll(filter(from, to, action),
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "at", "id")));
		return new AuditPage(result.stream().map(this::row).toList(), result.getTotalElements(), page, size);
	}

	private AuditRow row(AuditLog log) {
		return new AuditRow(log.getAt(), log.getActorName(), log.getAction(), log.getEntity(), log.getEntityId(),
				details(log.getDetails()));
	}

	private Map<String, Object> details(String text) {
		if (text == null) {
			return Map.of();
		}
		Map<String, Object> parsed = json.readValue(text, new TypeReference<Map<String, Object>>() {
		});
		parsed.replaceAll((key, value) -> value instanceof BigDecimal amount ? amount.toPlainString() : value);
		return parsed;
	}

	private Specification<AuditLog> filter(LocalDate from, LocalDate to, AuditAction action) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.<Instant>get("at"), from.atStartOfDay(clock.getZone()).toInstant()));
			}
			if (to != null) {
				predicates.add(cb.lessThan(root.<Instant>get("at"), to.plusDays(1).atStartOfDay(clock.getZone()).toInstant()));
			}
			if (action != null) {
				predicates.add(cb.equal(root.get("action"), action));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

}
```

`AuditController.java`:

```java
package com.example.accounting;

import java.time.LocalDate;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/audit")
public class AuditController {

	private final AuditService service;

	public AuditController(AuditService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('accounting:audit-read')")
	public AuditPage list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) AuditAction action,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return service.list(from, to, action, page, size);
	}

}
```

- [ ] **Step 6: Run tests to verify they pass**

Run: `./mvnw -pl backend/iam,backend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`.

If the `DROP CONSTRAINT` fails, find the name with `select conname from pg_constraint where conrelid = 'accounting.audit_log'::regclass` and correct the migration.

- [ ] **Step 7: Commit**

```bash
git add backend/iam/src/main/resources/db/migration/iam/V20261103_1__add_audit_read_permission.sql backend/iam/src/test/java/com/example/iam/IamApiTests.java backend/accounting/src/main/resources/db/migration/accounting/V20261103_2__ledger_hardening.sql backend/accounting/src/main/java/com/example/accounting/AuditAction.java backend/accounting/src/main/java/com/example/accounting/AuditLog.java backend/accounting/src/main/java/com/example/accounting/AuditLogRepository.java backend/accounting/src/main/java/com/example/accounting/AccountService.java backend/accounting/src/main/java/com/example/accounting/JournalService.java backend/accounting/src/main/java/com/example/accounting/AuditRow.java backend/accounting/src/main/java/com/example/accounting/AuditPage.java backend/accounting/src/main/java/com/example/accounting/AuditService.java backend/accounting/src/main/java/com/example/accounting/AuditController.java backend/accounting/src/test/java/com/example/accounting/AccountingTestSupport.java backend/accounting/src/test/java/com/example/accounting/AuditApiTests.java
git commit -m "Audit account changes and serve the audit log to accountants and admins"
```

---

### Task 6: Audit screen

**Files:**
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/AuditDetails.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/AuditRowDto.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/AuditPageDto.java`
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/AuditViewModel.java`
- Create: `frontend/accounting/src/main/resources/web/accounting/audit.zul`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/AccountingApiClient.java`
- Modify: `frontend/accounting/src/main/java/com/example/ui/accounting/AccountingUiModule.java`
- Create: `frontend/accounting/src/test/java/com/example/ui/accounting/AuditDetailsTests.java`
- Modify: `frontend/accounting/src/test/java/com/example/ui/accounting/AccountingApiClientTests.java`
- Modify: `frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java`
- Modify: `frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java`

**Interfaces:**
- Consumes: the Task 5 endpoint and its JSON shape.
- Produces: `AccountingApiClient.audit(LocalDate from, LocalDate to, String action, int page, int size): AuditPageDto`.

- [ ] **Step 1: Write the failing tests**

Create `AuditDetailsTests.java`:

```java
package com.example.ui.accounting;

import java.util.HashMap;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AuditDetailsTests {

	@Test
	void aChangeShowsOldAndNew() {
		assertThat(AuditDetails.format(Map.of("name", Map.of("from", "Kas", "to", "Kas Kecil"))))
				.isEqualTo("name: Kas → Kas Kecil");
	}

	@Test
	void aMissingSideShowsAsNone() {
		Map<String, Object> change = new HashMap<>();
		change.put("from", null);
		change.put("to", "1100");

		assertThat(AuditDetails.format(Map.of("parent", change))).isEqualTo("parent: (none) → 1100");
	}

	@Test
	void plainValuesAndSeveralFieldsAreFlatAndOrdered() {
		assertThat(AuditDetails.format(Map.of("version", 3, "entryNo", "JE-1", "totalDebit", "10000000.00")))
				.isEqualTo("entryNo: JE-1, totalDebit: 10000000.00, version: 3");
	}

	@Test
	void noDetailsShowNothing() {
		assertThat(AuditDetails.format(null)).isEmpty();
		assertThat(AuditDetails.format(Map.of())).isEmpty();
	}

}
```

Add to `AccountingScreensRenderTests.java`, with imports `java.time.Instant`, `java.util.Map`, `com.example.ui.accounting.AuditPageDto`, `com.example.ui.accounting.AuditRowDto`:

```java
	@Test
	void auditRendersRowsInJakartaTime() throws Exception {
		when(accountingApiClient.audit(any(), any(), any(), anyInt(), anyInt())).thenReturn(new AuditPageDto(List.of(new AuditRowDto(
				Instant.parse("2026-03-14T17:30:00Z"), "Akuntan", "ACCOUNT_CHANGE", "account", 5L,
				Map.of("name", Map.of("from", "Kas", "to", "Kas Kecil")))), 1, 0, 50));

		assertThat(render("audit.zul")).contains("Akuntan", "2026-03-15 00:30:00", "ACCOUNT_CHANGE", "account 5", "Kas Kecil");
	}
```

Add to `AccountingClickPathTests.java`, with imports `java.time.Instant`, `java.util.Map`, `com.example.ui.accounting.AuditPageDto`, `com.example.ui.accounting.AuditRowDto`, and `isNull` from `ArgumentMatchers`:

```java
	private static AuditPageDto auditPage(long total) {
		return new AuditPageDto(List.of(new AuditRowDto(Instant.parse("2026-03-14T17:30:00Z"), "Akuntan", "ACCOUNT_CHANGE",
				"account", 5L, Map.of())), total, 0, 50);
	}

	private void stubAudit(long total) {
		when(api.audit(any(), any(), any(), anyInt(), anyInt())).thenAnswer(call -> {
			zk.captureDesktop();
			return auditPage(total);
		});
	}

	@Test
	void auditFiltersAndKeepsTheFilterWhilePaging() throws Exception {
		stubAudit(120);
		page("audit.zul");
		verify(api).audit(isNull(), isNull(), eq(""), eq(0), eq(50));

		zk.change(zk.all(Combobox.class).get(0), "POST");
		zk.click(zk.button("Search"));
		verify(api).audit(isNull(), isNull(), eq("POST"), eq(0), eq(50));

		zk.au("onPaging", zk.all(Paging.class).get(0).getUuid(), "{\"\":1}");
		verify(api).audit(isNull(), isNull(), eq("POST"), eq(1), eq(50));
	}

	@Test
	void aRefusedAuditSearchEmptiesTheListAndSaysWhy() throws Exception {
		stubAudit(1);
		page("audit.zul");
		assertThat(zk.all(Listitem.class)).hasSize(1);

		when(api.audit(any(), any(), any(), anyInt(), anyInt())).thenThrow(HttpClientErrorException.create(HttpStatus.FORBIDDEN,
				"Forbidden", HttpHeaders.EMPTY, new byte[0], StandardCharsets.UTF_8));
		zk.click(zk.button("Search"));

		assertThat(zk.all(Listitem.class)).isEmpty();
		assertThat(zk.response).contains("The server could not complete the request.");
	}
```

Add to `AccountingApiClientTests.java`. It proves the real JSON binds, which the mocked screen tests cannot:

```java
	@Test
	void auditSendsOnlyTheFiltersInUseAndReadsTheRow() {
		server.expect(requestTo(BASE + "/audit?from=2026-03-01&action=POST&page=1&size=50")).andRespond(withSuccess("""
				{"items": [{"at": "2026-03-14T17:30:00Z", "actorName": "Akuntan", "action": "POST", "entity": "journal_entry",
				 "entityId": 5, "details": {"name": {"from": "Kas", "to": "Kas Kecil"}, "totalDebit": "10000000.00"}}],
				 "total": 51, "page": 1, "size": 50}
				""", MediaType.APPLICATION_JSON));

		AuditPageDto page = client.audit(LocalDate.of(2026, 3, 1), null, "POST", 1, 50);

		assertThat(page.total()).isEqualTo(51);
		AuditRowDto row = page.items().get(0);
		assertThat(row.getAtText()).isEqualTo("2026-03-15 00:30:00");
		assertThat(row.getRecord()).isEqualTo("journal_entry 5");
		assertThat(row.getDetailsText()).isEqualTo("name: Kas → Kas Kecil, totalDebit: 10000000.00");
		server.verify();
	}

	@Test
	void auditWithNoFiltersSendsOnlyThePage() {
		server.expect(requestTo(BASE + "/audit?page=0&size=50")).andRespond(withSuccess("""
				{"items": [], "total": 0, "page": 0, "size": 50}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.audit(null, null, "", 0, 50).items()).isEmpty();
		server.verify();
	}
```

- [ ] **Step 2: Run tests to verify they fail**

Run: `./mvnw -pl frontend/shell -am test -Dtest='AuditDetailsTests,AccountingScreensRenderTests,AccountingClickPathTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at test compilation, because `AuditDetails` and `AuditPageDto` do not exist.

- [ ] **Step 3: Implement**

`AuditDetails.java`:

```java
package com.example.ui.accounting;

import java.util.Map;
import java.util.TreeMap;
import java.util.stream.Collectors;

/** Flat text for an audit row's details, such as {@code name: Kas → Kas Kecil}. */
final class AuditDetails {

	private AuditDetails() {
	}

	static String format(Map<String, Object> details) {
		if (details == null) {
			return "";
		}
		return new TreeMap<>(details).entrySet().stream()
				.map(field -> field.getKey() + ": " + text(field.getValue()))
				.collect(Collectors.joining(", "));
	}

	private static String text(Object value) {
		if (value instanceof Map<?, ?> change && change.containsKey("from") && change.containsKey("to")) {
			return plain(change.get("from")) + " → " + plain(change.get("to"));
		}
		return plain(value);
	}

	private static String plain(Object value) {
		return value == null ? "(none)" : value.toString();
	}

}
```

`AuditRowDto.java`:

```java
package com.example.ui.accounting;

import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Map;

public record AuditRowDto(Instant at, String actorName, String action, String entity, Long entityId,
		Map<String, Object> details) {

	private static final DateTimeFormatter TIME = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss")
			.withZone(ZoneId.of("Asia/Jakarta"));

	public String getAtText() {
		return at == null ? "" : TIME.format(at);
	}

	public String getActorName() {
		return actorName;
	}

	public String getAction() {
		return action;
	}

	public String getRecord() {
		return entityId == null ? entity : entity + " " + entityId;
	}

	public String getDetailsText() {
		return AuditDetails.format(details);
	}

}
```

`AuditPageDto.java`:

```java
package com.example.ui.accounting;

import java.util.List;

public record AuditPageDto(List<AuditRowDto> items, long total, int page, int size) {
}
```

In `AccountingApiClient.java`, after `entries`:

```java
	public AuditPageDto audit(LocalDate from, LocalDate to, String action, int page, int size) {
		return client.get().uri(uri -> uri.path(PATH + "/audit")
				.queryParamIfPresent("from", Optional.ofNullable(from))
				.queryParamIfPresent("to", Optional.ofNullable(to))
				.queryParamIfPresent("action", Optional.ofNullable(action).filter(a -> !a.isBlank()))
				.queryParam("page", page)
				.queryParam("size", size)
				.build()).retrieve().body(AuditPageDto.class);
	}
```

`AuditViewModel.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind audit.zul: the read-only, server-side paged audit log. */
@VariableResolver(SpringBeanVariableResolver.class)
public class AuditViewModel {

	static final int PAGE_SIZE = 50;

	@WireVariable
	private AccountingApiClient accountingApiClient;

	private LocalDate from;

	private LocalDate to;

	private String action = "";

	private int page;

	private long total;

	private List<AuditRowDto> rows = List.of();

	@Init
	public void init() {
		load();
	}

	@Command
	@NotifyChange({ "rows", "total", "page" })
	public void search() {
		page = 0;
		load();
	}

	@Command
	@NotifyChange({ "rows", "total", "page" })
	public void goToPage(@BindingParam("page") int page) {
		this.page = page;
		load();
	}

	private void load() {
		try {
			AuditPageDto result = accountingApiClient.audit(from, to, action, page, PAGE_SIZE);
			rows = result.items();
			total = result.total();
		}
		catch (RuntimeException ex) {
			page = 0;
			rows = List.of();
			total = 0;
			ApiErrors.show(ex);
		}
	}

	public LocalDate getFrom() {
		return from;
	}

	public void setFrom(LocalDate from) {
		this.from = from;
	}

	public LocalDate getTo() {
		return to;
	}

	public void setTo(LocalDate to) {
		this.to = to;
	}

	public String getAction() {
		return action;
	}

	public void setAction(String action) {
		this.action = action;
	}

	public List<String> getActions() {
		return List.of("", "SETUP", "POST", "REVERSE", "PERIOD_CLOSE", "PERIOD_REOPEN", "SETTING_CHANGE", "ACCOUNT_CHANGE");
	}

	public int getPage() {
		return page;
	}

	public int getPageSize() {
		return PAGE_SIZE;
	}

	public int getTotal() {
		return (int) total;
	}

	public List<AuditRowDto> getRows() {
		return rows;
	}

}
```

`audit.zul`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<zk>
	<style src="~./accounting/accounting.css"/>
	<div viewModel="@id('vm') @init('com.example.ui.accounting.AuditViewModel')" sclass="accounting">
		<hlayout sclass="filters card">
			<datebox valueInLocalDate="@bind(vm.from)" format="yyyy-MM-dd" placeholder="From" width="120px"/>
			<datebox valueInLocalDate="@bind(vm.to)" format="yyyy-MM-dd" placeholder="To" width="120px"/>
			<combobox value="@bind(vm.action)" model="@load(vm.actions)" readonly="true" placeholder="Any action" width="170px">
				<template name="model"><comboitem label="@load(each)"/></template>
			</combobox>
			<button label="Search" iconSclass="z-icon-search" onClick="@command('search')"/>
		</hlayout>

		<listbox model="@load(vm.rows)" emptyMessage="No audit records" sclass="card">
			<listhead>
				<listheader label="Time" width="170px"/>
				<listheader label="Who" width="160px"/>
				<listheader label="Action" width="150px"/>
				<listheader label="Record" width="160px"/>
				<listheader label="Details"/>
			</listhead>
			<template name="model" var="row">
				<listitem>
					<listcell label="@load(row.atText)"/>
					<listcell label="@load(row.actorName)"/>
					<listcell label="@load(row.action)"/>
					<listcell label="@load(row.record)"/>
					<listcell label="@load(row.detailsText)"/>
				</listitem>
			</template>
		</listbox>
		<paging pageSize="@load(vm.pageSize)" totalSize="@load(vm.total)" activePage="@load(vm.page)" onPaging="@command('goToPage', page=self.activePage)"/>
	</div>
</zk>
```

`AccountingUiModule.java`, after `accountingReportsUi`:

```java
	@Bean
	UiModule accountingAuditUi() {
		return UiModule.embedded("accounting-audit", "Audit", 24, "~./accounting/audit.zul").withPermission("accounting:audit-read");
	}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl frontend/shell -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add frontend/accounting/src/main/java/com/example/ui/accounting/AuditDetails.java frontend/accounting/src/main/java/com/example/ui/accounting/AuditRowDto.java frontend/accounting/src/main/java/com/example/ui/accounting/AuditPageDto.java frontend/accounting/src/main/java/com/example/ui/accounting/AuditViewModel.java frontend/accounting/src/main/resources/web/accounting/audit.zul frontend/accounting/src/main/java/com/example/ui/accounting/AccountingApiClient.java frontend/accounting/src/main/java/com/example/ui/accounting/AccountingUiModule.java frontend/accounting/src/test/java/com/example/ui/accounting/AuditDetailsTests.java frontend/accounting/src/test/java/com/example/ui/accounting/AccountingApiClientTests.java frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java frontend/shell/src/test/java/com/example/shell/AccountingClickPathTests.java
git commit -m "Add the audit log screen"
```

---

### Task 7: Startup check for the runtime role

**Files:**
- Create: `backend/tenancy/src/main/java/com/example/tenancy/RuntimeRoleCheck.java`
- Modify: `backend/tenancy/src/main/java/com/example/tenancy/TenancyModule.java`
- Create: `backend/tenancy/src/test/java/com/example/tenancy/RuntimeRoleCheckTests.java`

**Interfaces:**
- Produces: bean `RuntimeRoleCheck implements ApplicationRunner`, throwing `IllegalStateException` that names the role and reason.

- [ ] **Step 1: Write the failing test**

```java
package com.example.tenancy;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

@SpringBootTest(classes = TenancyTests.TestApp.class,
		properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test")
// A second Testcontainers Postgres in one JVM fails on a remote Docker; stop each before the next starts.
@DirtiesContext
class RuntimeRoleCheckTests {

	@Autowired
	PostgreSQLContainer postgres;

	@Autowired
	DataSource applicationDataSource;

	private DataSource as(String user, String password) {
		return new DriverManagerDataSource(postgres.getJdbcUrl(), user, password);
	}

	private JdbcTemplate superuser() {
		return new JdbcTemplate(as(postgres.getUsername(), postgres.getPassword()));
	}

	@Test
	void passesForTheRuntimeRole() {
		assertThatCode(() -> new RuntimeRoleCheck(applicationDataSource).run(null)).doesNotThrowAnyException();
	}

	@Test
	void refusesASuperuser() {
		assertThatThrownBy(() -> new RuntimeRoleCheck(as(postgres.getUsername(), postgres.getPassword())).run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining(postgres.getUsername())
				.hasMessageContaining("is a superuser");
	}

	@Test
	void refusesARoleWithBypassRls() {
		superuser().execute("create role rls_skipper login password 'x' bypassrls");

		assertThatThrownBy(() -> new RuntimeRoleCheck(as("rls_skipper", "x")).run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("rls_skipper")
				.hasMessageContaining("has BYPASSRLS");
	}

	@Test
	void refusesARoleThatOwnsATable() {
		superuser().execute("create role table_owner login password 'x'");
		superuser().execute("create table public.owned_by_role (id int)");
		superuser().execute("alter table public.owned_by_role owner to table_owner");

		assertThatThrownBy(() -> new RuntimeRoleCheck(as("table_owner", "x")).run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("table_owner")
				.hasMessageContaining("owns 1 tables");
	}

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl backend/tenancy -am test -Dtest='RuntimeRoleCheckTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL at test compilation, because `RuntimeRoleCheck` does not exist.

- [ ] **Step 3: Implement**

`RuntimeRoleCheck.java`:

```java
package com.example.tenancy;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;

import javax.sql.DataSource;

import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Stops the app when its database role skips row-level security, which would silently turn tenant isolation off.
 * Superusers, table owners and BYPASSRLS roles all skip it. There is no off switch.
 */
class RuntimeRoleCheck implements ApplicationRunner {

	private final JdbcTemplate jdbc;

	RuntimeRoleCheck(DataSource dataSource) {
		this.jdbc = new JdbcTemplate(dataSource);
	}

	@Override
	public void run(ApplicationArguments args) {
		Map<String, Object> role = jdbc.queryForMap(
				"select current_user::text as name, rolsuper, rolbypassrls from pg_roles where rolname = current_user");
		long owned = jdbc.queryForObject("select count(*) from pg_tables where tableowner = current_user", Long.class);
		List<String> reasons = new ArrayList<>();
		if (Boolean.TRUE.equals(role.get("rolsuper"))) {
			reasons.add("is a superuser");
		}
		if (Boolean.TRUE.equals(role.get("rolbypassrls"))) {
			reasons.add("has BYPASSRLS");
		}
		if (owned > 0) {
			reasons.add("owns " + owned + " tables");
		}
		if (!reasons.isEmpty()) {
			throw new IllegalStateException("Database role " + role.get("name") + " skips row-level security: it "
					+ String.join(", ", reasons) + ". Connect the application as app_runtime.");
		}
	}

}
```

In `TenancyModule.java`, add:

```java
	@Bean
	RuntimeRoleCheck runtimeRoleCheck(DataSource dataSource) {
		return new RuntimeRoleCheck(dataSource);
	}
```

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl backend/tenancy,backend/task,backend/iam,backend/accounting,backend/app -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`. Every module's context starts as `app_runtime`, so the runner passes everywhere.

If `backend/app` or another context fails at startup on this check, its test connects as the wrong role. Fix that test's configuration, not the check.

- [ ] **Step 5: Commit**

```bash
git add backend/tenancy/src/main/java/com/example/tenancy/RuntimeRoleCheck.java backend/tenancy/src/main/java/com/example/tenancy/TenancyModule.java backend/tenancy/src/test/java/com/example/tenancy/RuntimeRoleCheckTests.java
git commit -m "Stop at startup when the database role skips row-level security"
```

---

### Task 8: No active tenant means no authorities

**Files:**
- Modify: `backend/security/src/main/java/com/example/security/KeycloakJwtAuthenticationConverter.java`
- Modify: `backend/security/src/test/java/com/example/security/KeycloakJwtAuthenticationConverterTests.java`
- Modify: `backend/iam/src/main/java/com/example/iam/DbPermissionResolver.java`
- Modify: `backend/iam/src/test/java/com/example/iam/IamApiTests.java:121-146`

- [ ] **Step 1: Write the failing test**

Add to `KeycloakJwtAuthenticationConverterTests.java`:

```java
	private static final UUID DEMO = UUID.fromString("7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b");

	private static Map<String, Object> demoTenant() {
		return Map.of("demo", Map.of("id", DEMO.toString()));
	}

	@Test
	void noActiveTenantGetsNoAuthoritiesAndTheResolverIsNotAsked() {
		AtomicReference<Jwt> asked = new AtomicReference<>();
		var converter = new KeycloakJwtAuthenticationConverter(token -> {
			asked.set(token);
			return Set.of("task:read");
		}, DEMO::equals);

		var noClaim = converter.convert(jwt(Map.of("preferred_username", "u")));
		var unknown = converter.convert(jwt(Map.of("tenant", Map.of("other", Map.of("id", UUID.randomUUID().toString())))));

		assertThat(noClaim.getAuthorities()).isEmpty();
		assertThat(unknown.getAuthorities()).isEmpty();
		assertThat(noClaim.isAuthenticated()).isTrue();
		assertThat(asked.get()).isNull();
	}
```

Change `authoritiesAreThePermissionsFromTheResolver`, so the token has an active tenant:

```java
		var converter = new KeycloakJwtAuthenticationConverter(token -> {
			seen.set(token);
			return Set.of("task:write", "task:read");
		}, DEMO::equals);
		Jwt token = jwt(Map.of("preferred_username", "editor", "tenant", demoTenant()));
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl backend/security -am test -Dtest='KeycloakJwtAuthenticationConverterTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. The authorities hold `task:read`, and the resolver was asked.

- [ ] **Step 3: Implement**

`KeycloakJwtAuthenticationConverter.convert`:

```java
	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		UUID tenantId = KeycloakTenant.activeTenant(jwt, tenantDirectory).orElse(null);
		// Roles act inside the user's tenant only; without an active tenant they grant nothing.
		List<GrantedAuthority> authorities = tenantId == null ? List.of() : permissionResolver.permissionsFor(jwt).stream()
				.sorted()
				.<GrantedAuthority>map(SimpleGrantedAuthority::new)
				.toList();
		String username = jwt.getClaimAsString("preferred_username");
		return new KeycloakAuthentication(jwt, authorities, username != null ? username : jwt.getSubject(), tenantId);
	}
```

`DbPermissionResolver.java`: remove the `tenants` field, the constructor parameter, and the imports of `KeycloakTenant` and `TenantDirectory`. Update the class comment to say the converter gates on the tenant. `permissionsFor` becomes:

```java
	@Override
	public Set<String> permissionsFor(Jwt jwt) {
		return permissionsForRoles(KeycloakRoles.of(jwt));
	}
```

In `IamApiTests.java`, the assertions in `tokenWithoutActiveTenantHasNoPermissions`, and any later assertion expecting empty for a token with no active tenant (about line 142), now go through the converter. Read lines 121-146 first; any positive assertions in them stay as they are. Add a helper, with imports for `KeycloakJwtAuthenticationConverter` and `GrantedAuthority`:

```java
	private java.util.Collection<? extends GrantedAuthority> authorities(Jwt jwt) {
		return new KeycloakJwtAuthenticationConverter(resolver, tenants).convert(jwt).getAuthorities();
	}
```

and replace `resolver.permissionsFor(token(...))` with `authorities(token(...))` in those assertions.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl backend/security,backend/iam,backend/app -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`, including `TokenToPermissionsTests`, which proves suspended and unknown organizations with real signed tokens.

- [ ] **Step 5: Commit**

```bash
git add backend/security/src/main/java/com/example/security/KeycloakJwtAuthenticationConverter.java backend/security/src/test/java/com/example/security/KeycloakJwtAuthenticationConverterTests.java backend/iam/src/main/java/com/example/iam/DbPermissionResolver.java backend/iam/src/test/java/com/example/iam/IamApiTests.java
git commit -m "Gate permissions on the active tenant in one place"
```

---

### Task 9: Log duplicate and bad-value database refusals

**Files:**
- Modify: `backend/accounting/src/main/java/com/example/accounting/AccountingExceptionHandler.java:66-82`
- Create: `backend/accounting/src/test/java/com/example/accounting/AccountingExceptionHandlerTests.java`

- [ ] **Step 1: Write the failing test**

This test needs no Docker and no Spring context.

```java
package com.example.accounting;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AccountingExceptionHandlerTests {

	private final AccountingExceptionHandler handler = new AccountingExceptionHandler();

	private ProblemDetail problemFor(String sqlState) {
		return handler.database(new DataIntegrityViolationException("x", new SQLException("reason", sqlState)));
	}

	private static String code(ProblemDetail problem) {
		return (String) problem.getProperties().get("code");
	}

	@Test
	void triggersAndChecksAreInvariantBugs(CapturedOutput output) {
		assertThat(code(problemFor("P0001"))).isEqualTo("invariant-violated");
		assertThat(problemFor("P0001").getStatus()).isEqualTo(500);
		assertThat(code(problemFor("23514"))).isEqualTo("invariant-violated");
		assertThat(output).contains("ERROR").contains("A database guard rejected");
	}

	@Test
	void aDuplicateIsAConflictLoggedWithItsConstraint(CapturedOutput output) {
		ProblemDetail problem = handler.database(new DataIntegrityViolationException("x", new ConstraintViolationException(
				"duplicate", new SQLException("reason", "23505"), "journal_entry_tenant_id_entry_no_key")));

		assertThat(problem.getStatus()).isEqualTo(409);
		assertThat(code(problem)).isEqualTo("conflict");
		assertThat(output).contains("WARN").contains("23505").contains("journal_entry_tenant_id_entry_no_key");
	}

	@Test
	void aValueTheDatabaseRefusesIsValidationLoggedWithItsState(CapturedOutput output) {
		ProblemDetail problem = problemFor("22P02");

		assertThat(problem.getStatus()).isEqualTo(400);
		assertThat(code(problem)).isEqualTo("validation");
		assertThat(output).contains("WARN").contains("22P02");
	}

	@Test
	void anythingElseIsADatabaseError(CapturedOutput output) {
		assertThat(code(problemFor("42501"))).isEqualTo("database-error");
		assertThat(code(handler.database(new DataAccessResourceFailureException("no SQLException")))).isEqualTo("database-error");
		assertThat(output).contains("ERROR").contains("Database operation failed");
	}

}
```

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -pl backend/accounting -am test -Dtest='AccountingExceptionHandlerTests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on the two `WARN` assertions; the other two pass.

- [ ] **Step 3: Implement**

In `AccountingExceptionHandler.database`:

```java
		if ("23505".equals(state)) {
			// Usually two users at once, but it can also hide a numbering bug.
			log.warn("Database refused a duplicate: SQLState {}, constraint {}", state, constraintName(ex));
			return problem(HttpStatus.CONFLICT, "conflict", "Someone else made the same change; reload and try again");
		}
		if (state != null && state.startsWith("22")) {
			log.warn("Database refused a value: SQLState {}, constraint {}", state, constraintName(ex));
			return problem(HttpStatus.BAD_REQUEST, "validation", "The request contains a value the database cannot store");
		}
```

Add below `sqlState`:

```java
	private static String constraintName(Throwable ex) {
		for (Throwable cause = ex; cause != null; cause = cause.getCause() == cause ? null : cause.getCause()) {
			if (cause instanceof ConstraintViolationException violation) {
				return violation.getConstraintName();
			}
		}
		return null;
	}
```

Add `import org.hibernate.exception.ConstraintViolationException;`.

- [ ] **Step 4: Run tests to verify they pass**

Run: `./mvnw -pl backend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: `BUILD SUCCESS`.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting/src/main/java/com/example/accounting/AccountingExceptionHandler.java backend/accounting/src/test/java/com/example/accounting/AccountingExceptionHandlerTests.java
git commit -m "Log duplicates and refused values with their SQLState and constraint"
```

---

### Task 10: Docs and final verify

**Files:**
- Modify: `docs/superpowers/specs/2026-10-29-accounting-core-design.md`
- Modify: `README.md`
- Modify: `CLAUDE.md`

- [ ] **Step 1: Update the accounting core spec**

Make these edits, so the older spec stops describing the old behaviour:

- Line 21: `- With maker/checker on, nobody posts an entry they created or ever edited.`
- Line 166: `- Trigger: a `POSTED` or `REVERSED` entry and its lines cannot be updated, except the single transition `POSTED → REVERSED` with `reversed_by` set. No entry or line can be deleted, draft or not; see `2026-11-03-ledger-hardening-design.md`.`
- Line 255: `4. With `require_separate_poster` on, the poster's `sub` is not in `prepared_by_subs`.`
- Lines 68 and 277: the hint text becomes "You prepared or edited this entry; another user must post it".

- [ ] **Step 2: Update the README**

Under the "Manual end-to-end check" table, add rows after row 36:

```markdown
| 37 | Log in as an accountant and an admin: open **Audit**. Log in as a bookkeeper and a viewer | Accountant and admin see the menu item and the log. Bookkeeper and viewer do not see it |
| 38 | As an admin, rename an account, then open **Audit** and filter by `ACCOUNT_CHANGE` | One row, with your name, Jakarta time and `name: old → new` |
| 39 | Create a draft as one user, save it as a second user, then save it as a third. Open it as each | All three see "You prepared or edited this entry; another user must post it" and cannot post; a fourth user can |
| 40 | Save a draft with changed lines, delete another draft, then query `select count(*) from accounting.journal_line where deleted_at is not null` as `app` | The count grew; neither draft's old lines show on any screen |
```

Add this section directly before "Manual end-to-end check":

````markdown
### Before upgrading a database that holds accounting data

The ledger hardening migration checks existing rows, so bad data would stop the backend from starting. None is expected. To check first, run this read-only query as `app`; every count must be 0:

```sql
select
  (select count(*) from accounting.journal_entry where (status = 'DRAFT') <> (entry_no is null)) as number_mismatch,
  (select count(*) from (select 1 from accounting.journal_entry where reversal_of is not null
                         group by tenant_id, reversal_of having count(*) > 1) d) as double_reversals,
  (select count(*) from accounting.journal_entry e where e.reversal_of is not null and not exists
     (select 1 from accounting.journal_entry o where o.tenant_id = e.tenant_id and o.id = e.reversal_of)) as bad_reversal_of,
  (select count(*) from accounting.journal_entry e where e.reversed_by is not null and not exists
     (select 1 from accounting.journal_entry o where o.tenant_id = e.tenant_id and o.id = e.reversed_by)) as bad_reversed_by;
```
````

- [ ] **Step 3: Update `CLAUDE.md`**

In "Gotchas", replace the line "To replace an entry's lines, clear the collection and flush before adding the new ones: ..." with:

```markdown
- Journal lines and entries are never hard deleted: `app_runtime` has no `DELETE` on them and triggers refuse it even for the owner. Saving a draft marks its old lines `deleted_at`, then flushes, then adds new ones (Hibernate inserts before it updates, which would trip the unique index on live line numbers). `@SQLRestriction` hides removed rows from JPA, but every native query on `journal_line` or `journal_entry` must filter `deleted_at is null` by hand: a posted entry keeps the lines removed while it was a draft, so a missed filter makes a report wrong.
```

In "Status", add:

```markdown
- Verified 2026-11-03 (`./mvnw verify`): ledger hardening, including no hard deletes as `app_runtime` and as the owner, a migration over existing Phase 1 data, and the delete-versus-post race. README rows 37-40 are still to be done by hand.
```

Write the Status line only after Step 4 passes.

- [ ] **Step 4: Run the whole build**

A tool call dies at 10 minutes, so run it detached and watch it:

```bash
perl -MPOSIX -e 'exit if fork; POSIX::setsid(); open(STDIN,"</dev/null"); open(STDOUT,">",$ARGV[0]); open(STDERR,">&STDOUT"); exec("/bin/sh","-c","./mvnw verify; echo MVN_EXIT=\$?")' /private/tmp/ledger-verify.log
```

Expected: `BUILD SUCCESS` and `MVN_EXIT=0` in the log. A run with no `BUILD` line is not a pass.

- [ ] **Step 5: Commit**

```bash
git add docs/superpowers/specs/2026-10-29-accounting-core-design.md README.md CLAUDE.md
git commit -m "Document the ledger hardening and its manual checks"
```
