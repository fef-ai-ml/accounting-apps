package com.example.tenancy;

import javax.sql.DataSource;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.datasource.DriverManagerDataSource;
import org.springframework.test.annotation.DirtiesContext;
import org.testcontainers.postgresql.PostgreSQLContainer;

import static org.assertj.core.api.Assertions.assertThat;
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

	@Autowired
	RuntimeRoleCheck registeredCheck;

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
	void isRegisteredAndPassesForTheApplicationRole() {
		assertThatCode(() -> registeredCheck.run(null)).doesNotThrowAnyException();
	}

	@Test
	void refusesARoleThatOwnsATableThroughMembership() {
		superuser().execute("create role owning_group nologin");
		superuser().execute("create role owner_member login password 'x'");
		superuser().execute("grant owning_group to owner_member");
		superuser().execute("create table public.rls_probe (id int)");
		superuser().execute("alter table public.rls_probe owner to owning_group");
		superuser().execute("alter table public.rls_probe enable row level security");
		superuser().execute("insert into public.rls_probe values (1)");

		// Premise: with no policy, a role without owner rights would see 0 rows; the member sees the row.
		assertThat(new JdbcTemplate(as("owner_member", "x")).queryForObject("select count(*) from public.rls_probe",
				Long.class)).isEqualTo(1L);
		assertThatThrownBy(() -> new RuntimeRoleCheck(as("owner_member", "x")).run(null))
				.isInstanceOf(IllegalStateException.class)
				.hasMessageContaining("owner_member")
				.hasMessageContaining("owns 1 tables, directly or through a role it belongs to");
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
				.hasMessageContaining("owns 1 tables, directly or through a role it belongs to");
	}

}
