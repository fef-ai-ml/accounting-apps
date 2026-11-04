package com.example.accounting;

import java.nio.file.Files;
import java.nio.file.Path;
import java.sql.Connection;
import java.sql.DriverManager;
import java.sql.ResultSet;
import java.sql.Statement;
import java.util.LinkedHashMap;
import java.util.Map;
import java.util.UUID;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.MigrationVersion;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

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

	/** Creates a database at the last Phase 1 version and returns its URL. */
	private String phase1Database(String name) throws Exception {
		try (Connection admin = connect(postgres.getJdbcUrl()); Statement statement = admin.createStatement()) {
			statement.execute("create database " + name);
		}
		String url = postgres.getJdbcUrl().replace("/" + postgres.getDatabaseName(), "/" + name);
		flyway(url, MigrationVersion.fromVersion("20261102.4")).migrate();
		return url;
	}

	private Connection connect(String url) throws Exception {
		return DriverManager.getConnection(url, postgres.getUsername(), postgres.getPassword());
	}

	/** The accounts and period that {@link #posted} needs, as {a, b, period}. */
	private static long[] accountsAndPeriod(Statement statement) throws Exception {
		return new long[] {
				id(statement, "insert into accounting.account (tenant_id, code, name, type, postable, active) values ("
						+ TENANT + ", 'A', 'A', 'ASSET', true, true) returning id"),
				id(statement, "insert into accounting.account (tenant_id, code, name, type, postable, active) values ("
						+ TENANT + ", 'B', 'B', 'ASSET', true, true) returning id"),
				id(statement, "insert into accounting.fiscal_period (tenant_id, year, month, start_date, end_date, status) values ("
						+ TENANT + ", 2026, 3, '2026-03-01', '2026-03-31', 'OPEN') returning id") };
	}

	/** A balanced posted entry; reversalOf is a SQL expression, or null for an ordinary entry. */
	private static long posted(Statement statement, long[] fixture, String entryNo, String reversalOf) throws Exception {
		long entry = id(statement, entry("DRAFT", null, "maker", "maker", fixture[2]));
		if (reversalOf != null) {
			statement.execute("update accounting.journal_entry set reversal_of = " + reversalOf + " where id = " + entry);
		}
		statement.execute("insert into accounting.journal_line (tenant_id, entry_id, line_no, account_id, debit, credit) values ("
				+ TENANT + ", " + entry + ", 1, " + fixture[0] + ", 1, 0), (" + TENANT + ", " + entry + ", 2, " + fixture[1] + ", 0, 1)");
		statement.execute("update accounting.journal_entry set status = 'POSTED', entry_no = '" + entryNo + "' where id = " + entry);
		return entry;
	}

	private static void markReversed(Statement statement, long entry, long reversedBy) throws Exception {
		statement.execute("update accounting.journal_entry set status = 'REVERSED', reversed_by = " + reversedBy + " where id = " + entry);
	}

	/** Runs the query the README tells an operator to run, so the two cannot drift apart. */
	private static Map<String, Long> readmePreCheck(Statement statement) throws Exception {
		String readme = Files.readString(Path.of("../../README.md"));
		String section = readme.substring(readme.indexOf("#### Before upgrading a database"));
		int start = section.indexOf("```sql") + "```sql".length();
		try (ResultSet row = statement.executeQuery(section.substring(start, section.indexOf("```", start)))) {
			row.next();
			Map<String, Long> counts = new LinkedHashMap<>();
			for (int column = 1; column <= row.getMetaData().getColumnCount(); column++) {
				counts.put(row.getMetaData().getColumnName(column), row.getLong(column));
			}
			return counts;
		}
	}

	@Test
	void aPhase1ReversalPairPassesThePreCheckAndCanStillBeReversed() throws Exception {
		String url = phase1Database("legacy_pair");
		long[] fixture;
		long original;
		long reversal;
		try (Connection legacy = connect(url); Statement statement = legacy.createStatement()) {
			fixture = accountsAndPeriod(statement);
			original = posted(statement, fixture, "JE-1", null);
			reversal = posted(statement, fixture, "JE-2", String.valueOf(original));
			markReversed(statement, original, reversal);
			assertThat(readmePreCheck(statement)).hasSize(6).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
		}

		flyway(url, MigrationVersion.LATEST).migrate();

		try (Connection legacy = connect(url); Statement statement = legacy.createStatement()) {
			assertThat(readmePreCheck(statement)).allSatisfy((name, count) -> assertThat(count).as(name).isZero());
			assertThat(id(statement, "select reversed_by from accounting.journal_entry where id = " + original)).isEqualTo(reversal);
			assertThat(id(statement, "select reversal_of from accounting.journal_entry where id = " + reversal)).isEqualTo(original);

			legacy.setAutoCommit(false);
			markReversed(statement, reversal, posted(statement, fixture, "JE-3", String.valueOf(reversal)));
			legacy.commit();
			assertThat(text(statement, "select status from accounting.journal_entry where id = " + reversal)).isEqualTo("REVERSED");
		}
	}

	@Test
	void thePreCheckFindsMismatchedLinksThatTheMigrationLeavesInPlace() throws Exception {
		String url = phase1Database("legacy_mismatch");
		long[] fixture;
		long selfLinked;
		long orphanReversal;
		try (Connection legacy = connect(url); Statement statement = legacy.createStatement()) {
			fixture = accountsAndPeriod(statement);
			selfLinked = posted(statement, fixture, "JE-1", null);
			markReversed(statement, selfLinked, selfLinked);
			long unrelated = posted(statement, fixture, "JE-2", null);
			markReversed(statement, posted(statement, fixture, "JE-3", null), unrelated);
			orphanReversal = posted(statement, fixture, "JE-4", String.valueOf(posted(statement, fixture, "JE-5", null)));
			posted(statement, fixture, "JE-6", "id");
			assertMismatchesFound(statement);
		}

		flyway(url, MigrationVersion.LATEST).migrate();

		try (Connection legacy = connect(url); Statement statement = legacy.createStatement()) {
			assertMismatchesFound(statement);
			assertThat(id(statement, "select reversed_by from accounting.journal_entry where id = " + selfLinked)).isEqualTo(selfLinked);

			legacy.setAutoCommit(false);
			markReversed(statement, orphanReversal, posted(statement, fixture, "JE-7", String.valueOf(orphanReversal)));
			assertThatThrownBy(legacy::commit).hasMessageContaining("does not name it");
		}
	}

	private static void assertMismatchesFound(Statement statement) throws Exception {
		assertThat(readmePreCheck(statement)).containsEntry("mismatched_reversed", 2L).containsEntry("mismatched_reversal_of", 2L)
				.containsEntry("number_mismatch", 0L).containsEntry("double_reversals", 0L)
				.containsEntry("bad_reversal_of", 0L).containsEntry("bad_reversed_by", 0L);
	}

	@Test
	void draftsGetTheirKnownPreparersAndPostedRowsStayFrozen() throws Exception {
		String url = phase1Database("legacy");

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
