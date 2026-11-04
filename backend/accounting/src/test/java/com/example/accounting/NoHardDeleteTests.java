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
	void aRemovedLineCannotBeInsertedOnADeletedEntry() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		deleteDraft(id, versionOf(id)).andExpect(status().isNoContent());

		assertThatThrownBy(() -> inTransaction(() -> entityManager.createNativeQuery("""
				insert into accounting.journal_line (tenant_id, entry_id, line_no, account_id, debit, credit,
				                                     deleted_at, deleted_by_sub, deleted_by_name)
				values (cast(:tenant as uuid), :entry, 9, :account, 1, 0, now(), 's', 'n')
				""").setParameter("tenant", tenant.toString()).setParameter("entry", id)
				.setParameter("account", cash).executeUpdate()))
				.hasStackTraceContaining("is deleted and cannot take lines");
	}

	@Test
	void aLineCannotBeMovedToADeletedEntryEvenWhileBeingRemoved() throws Exception {
		long gone = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		deleteDraft(gone, versionOf(gone)).andExpect(status().isNoContent());
		long live = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));

		assertThatThrownBy(() -> inTransaction(() -> entityManager.createNativeQuery("""
				update accounting.journal_line set entry_id = :gone, deleted_at = now(), deleted_by_sub = 's',
				       deleted_by_name = 'n' where entry_id = :live and line_no = 1
				""").setParameter("gone", gone).setParameter("live", live).executeUpdate()))
				.hasStackTraceContaining("is deleted and cannot take lines");
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
