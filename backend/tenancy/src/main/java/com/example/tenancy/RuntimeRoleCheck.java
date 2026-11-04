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
 * Superusers, table owners (including members of an owning role) and BYPASSRLS roles all skip it. There is no off switch.
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
		long owned = jdbc.queryForObject("select count(*) from pg_tables where pg_has_role(current_user, tableowner, 'USAGE')", Long.class);
		List<String> reasons = new ArrayList<>();
		if (Boolean.TRUE.equals(role.get("rolsuper"))) {
			reasons.add("is a superuser");
		}
		if (Boolean.TRUE.equals(role.get("rolbypassrls"))) {
			reasons.add("has BYPASSRLS");
		}
		if (owned > 0) {
			reasons.add("owns " + owned + " tables, directly or through a role it belongs to");
		}
		if (!reasons.isEmpty()) {
			throw new IllegalStateException("Database role " + role.get("name") + " skips row-level security: it "
					+ String.join(", ", reasons) + ". Connect the application as app_runtime.");
		}
	}

}
