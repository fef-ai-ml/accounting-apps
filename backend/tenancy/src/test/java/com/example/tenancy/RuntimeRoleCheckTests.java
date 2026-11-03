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
