package com.example.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import com.example.iam.DbPermissionResolver;
import com.example.security.PermissionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(TestcontainersConfiguration.class)
// Test classes here use different contexts and so start their own container; stop each before the next starts.
@DirtiesContext
class ApiApplicationTests {

	@Autowired
	MockMvc mvc;

	@Autowired
	JdbcTemplate jdbc;

	@Autowired
	PermissionResolver permissionResolver;

	@Test
	void permissionsComeFromIam() {
		assertThat(permissionResolver).isInstanceOf(DbPermissionResolver.class);
	}

	@Test
	void healthAndApiDocsArePublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

	@Test
	void apiNeedsAToken() throws Exception {
		mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/iam/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void connectsAsARoleThatRowLevelSecurityAppliesTo() {
		// Superusers, table owners and BYPASSRLS roles skip RLS: the tenant isolation would be silently off.
		assertThat(jdbc.queryForMap(
				"select current_user::text as name, rolsuper, rolbypassrls from pg_roles where rolname = current_user"))
				.containsEntry("name", "app_runtime")
				.containsEntry("rolsuper", false)
				.containsEntry("rolbypassrls", false);
		assertThat(jdbc.queryForObject("select count(*) from pg_tables where tableowner = current_user", Integer.class))
				.isZero();
	}

	@Test
	void everyTableTheApiCanWriteHasRowLevelSecurity() {
		// Default privileges grant DML on future module tables before any RLS exists, and an owner-owned view bypasses RLS.
		assertThat(jdbc.queryForList("""
				select n.nspname || '.' || c.relname from pg_class c join pg_namespace n on n.oid = c.relnamespace
				where c.relkind in ('r', 'p') and n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg_toast%'
				  and (has_table_privilege('app_runtime', c.oid, 'INSERT,UPDATE,DELETE')
				       or exists (select 1 from pg_attribute a where a.attrelid = c.oid and a.attname = 'tenant_id' and not a.attisdropped))
				  and not (c.relrowsecurity and exists (select 1 from pg_policy p where p.polrelid = c.oid))
				""", String.class)).isEmpty();
		assertThat(jdbc.queryForList("""
				select c.oid::regclass::text from pg_class c join pg_namespace n on n.oid = c.relnamespace
				where c.relkind in ('v', 'm') and n.nspname not in ('pg_catalog', 'information_schema') and n.nspname not like 'pg_toast%'
				  and has_table_privilege('app_runtime', c.oid, 'SELECT')
				  and (c.relkind = 'm' or not coalesce('security_invoker=true' = any(c.reloptions), false))
				""", String.class)).isEmpty();
	}

}
