package com.example.accounting;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.jdbc.core.JdbcTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class SchemaTests extends AccountingTestSupport {

	@Autowired
	JdbcTemplate jdbc;

	@Test
	void runsAsARoleThatRowLevelSecurityAppliesTo() {
		assertThat(jdbc.queryForObject("select current_user", String.class)).isEqualTo("app_runtime");
		assertThat(jdbc.queryForObject(
				"select rolsuper or rolbypassrls from pg_roles where rolname = current_user", Boolean.class)).isFalse();
		assertThat(jdbc.queryForObject("select count(*) from pg_tables where tableowner = current_user", Long.class))
				.isZero();
	}

	@Test
	void templateHoldsTheIndonesianChartOfAccounts() {
		assertThat(jdbc.queryForObject("select count(*) from accounting.coa_template", Long.class)).isEqualTo(39);
		assertThat(jdbc.queryForObject("select count(*) from accounting.coa_template where postable", Long.class))
				.isEqualTo(30);
		Map<String, Object> kas = jdbc.queryForMap(
				"select name, type, parent_code, postable from accounting.coa_template where code = '1110'");
		assertThat(kas).containsEntry("name", "Kas").containsEntry("type", "ASSET")
				.containsEntry("parent_code", "1100").containsEntry("postable", true);
	}

	@Test
	void everyParentCodeInTheTemplateExists() {
		List<String> orphans = jdbc.queryForList("""
				select t.code from accounting.coa_template t
				where t.parent_code is not null
				  and not exists (select 1 from accounting.coa_template p where p.code = t.parent_code)
				""", String.class);
		assertThat(orphans).isEmpty();
	}

	@Test
	void theApiCannotChangeTheTemplateOrTheAuditLog() {
		assertThatThrownBy(() -> jdbc.update("delete from accounting.coa_template"))
				.hasStackTraceContaining("permission denied");
		assertThatThrownBy(() -> jdbc.update("delete from accounting.audit_log"))
				.hasStackTraceContaining("permission denied");
		assertThatThrownBy(() -> jdbc.update("update accounting.audit_log set entity = 'x'"))
				.hasStackTraceContaining("permission denied");
	}

}
