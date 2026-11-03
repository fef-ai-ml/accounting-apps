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
