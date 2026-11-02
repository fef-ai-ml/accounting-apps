# Accounting Core (Phase 1) Backend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** A tenant-aware `backend/accounting` module: chart of accounts, fiscal periods, journal drafts, posting with maker/checker and gapless numbering, reversals, trial balance and general ledger, over REST.

**Architecture:** One new module entered through `AccountingModule`, owning Postgres schema `accounting`. Controllers call services that enforce the rules; database triggers guard the same invariants as defence in depth. Every tenant table has `tenant_id`, `@TenantId` and row-level security, using the `backend/tenancy` module. Permissions live in `iam`; the module only checks `accounting:*` authorities.

**Tech Stack:** Spring Boot 4.1.2, Spring Data JPA, Hibernate 7.4, Jackson 3, PostgreSQL 17, Flyway, Testcontainers, JUnit 5, AssertJ, MockMvc.

**Spec:** `docs/superpowers/specs/2026-10-29-accounting-core-design.md`. The frontend is a separate plan and pull request.

## Global Constraints

- Currency is IDR only; amounts are `NUMERIC(19,2)` / `BigDecimal`, sent as JSON strings with two decimals (`"1665000.00"`).
- Posted entries are numbered `JE-<year>-<6 digits>`, assigned at posting, gapless per tenant and year; the year is the entry date's year.
- `*_sub` columns hold the Keycloak `sub` (identity for checks); `*_name` columns hold the username at the time of the action (display only).
- Every failure is an RFC 9457 `ProblemDetail` with a stable `code` property, using the codes in the spec's "Error handling" table.
- Every tenant table: `tenant_id UUID NOT NULL`, `@TenantId`, `ENABLE ROW LEVEL SECURITY`, policy `tenant_isolation` with `tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid` in `USING` and `WITH CHECK`, grants to `app_runtime`, indexes and uniques starting with `tenant_id`.
- `app_runtime` gets per-table grants; never `ALTER DEFAULT PRIVILEGES` in this module. `coa_template` is `SELECT` only, `audit_log` is `SELECT, INSERT` only.
- Flyway versions are global: this plan uses `V20261102_1` (iam) and `V20261102_2`, `_3`, `_4` (accounting).
- The module is entered through `AccountingModule` + `@Import`; no cross-module scanning; it never imports `iam`; its tests boot it alone.
- Never declare a `clientScopes` array in `infra/keycloak/zk-spring-realm.json`.
- Code style: tabs, existing import grouping, comments only where they explain why; tests use AssertJ and MockMvc.
- Backend tests need Docker. On the maintainer's Mac: the home-lab SSH tunnels up, `TESTCONTAINERS_*` already set. Never touch the home-lab Postgres or Keycloak from a task; only Testcontainers.
- Run module tests with `./mvnw -q -pl backend/accounting -am test`; the whole build with `./mvnw verify`.
- Commits: explicit paths only (never `.bloop`, `.metals`, `graphify-out`, `.superpowers`); messages end with a blank line and `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`. Commits are signed through 1Password; never bypass signing.

## Decisions this plan settles (the spec is silent or differs)

- `settings` has a surrogate `id` plus `UNIQUE (tenant_id)` instead of `tenant_id` as primary key: `@TenantId` is not used on an `@Id` anywhere in this codebase. One row per tenant still holds.
- `entry_sequence` is locked by an atomic `INSERT ... ON CONFLICT DO UPDATE ... RETURNING`, not a separate `SELECT ... FOR UPDATE`: the upsert holds the same row lock until commit and also creates the row on first use.
- `GET /settings` before setup returns 200 with `initialized: false`, so the UI can show its empty state. Writes before setup return 422 `not-initialized`.
- New error code `account-code-exists` (409) for a duplicate account code; the spec's table has no code for it.
- Changing an account to non-postable while it has lines is `account-in-use`, and a parent with children cannot become postable (`validation`).
- "Today" is `Asia/Jakarta`, through one `Clock` bean.
- Closing an already closed period, or reopening an open one, returns the period unchanged and writes no audit row.
- A line naming an unknown or another tenant's account is `account-not-postable` (422), so ids of other tenants stay indistinguishable from missing ones.

## Review Focus

- Reversing an entry whose account was deactivated since posting: a 422 `account-not-postable`, not a 500 from the database guard. Pinned in Task 6.
- Editing a draft's date into another period: posting follows the new period, so a move into a closed period is refused. Pinned in Task 6.
- Odd amounts (`"1.500"`, `"1.555"`, `"abc"`, `"1E+1000000000"`, more than 17 integer digits): normalized or `invalid-amount`, never a rounding, a hang or a 500. Pinned in Task 5.
- Search text containing `%` or `_`, and paging values outside the limits (`size=0`, `size=201`, `page=-1`, `from` after `to`): literal matching and 400 `validation`. Pinned in Tasks 5 and 7.
- A draft line naming another tenant's account: 422 with no sign the account exists, and a database rejection if the service is bypassed. Pinned in Tasks 2 and 5.

---

### Task 1: Module, schema, permissions and demo users

**Files:**
- Modify: `pom.xml` (dependencyManagement), `backend/pom.xml` (module), `backend/app/pom.xml`
- Create: `backend/accounting/pom.xml`
- Create: `backend/accounting/src/main/java/com/example/accounting/AccountingModule.java`
- Create: `backend/accounting/src/main/resources/db/migration/accounting/V20261102_2__create_accounting_schema.sql`, `V20261102_3__seed_coa_template.sql`
- Create: `backend/iam/src/main/resources/db/migration/iam/V20261102_1__add_accounting_permissions.sql`
- Modify: `backend/app/src/main/java/com/example/app/ApiApplication.java`
- Modify: `infra/keycloak/zk-spring-realm.json`
- Modify: `backend/iam/src/test/java/com/example/iam/IamApiTests.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/AccountingModuleTests.java`, `AccountingTestSupport.java`, `TestcontainersConfiguration.java`, `SchemaTests.java`
- Test resources: `backend/accounting/src/test/resources/app-runtime-role.sql`

**Interfaces:**
- Consumes: `CommonModule`, `SecurityModule`, `TenancyModule`, `KeycloakAuthentication(Jwt, Collection<? extends GrantedAuthority>, String, UUID)`.
- Produces: `AccountingModule` with bean `Clock accountingClock()`; test base class `AccountingTestSupport` (see Step 2) used by every later task.

- [ ] **Step 1: Wire the module into the build**

In `pom.xml`, after the `backend-task` entry in `dependencyManagement`:

```xml
			<dependency>
				<groupId>com.example</groupId>
				<artifactId>backend-accounting</artifactId>
				<version>${project.version}</version>
			</dependency>
```

In `backend/pom.xml`, add `<module>accounting</module>` between `task` and `app`. In `backend/app/pom.xml`, after the `backend-iam` dependency:

```xml
		<dependency>
			<groupId>com.example</groupId>
			<artifactId>backend-accounting</artifactId>
		</dependency>
```

Create `backend/accounting/pom.xml` as an exact copy of `backend/task/pom.xml` with these three lines changed:

```xml
	<artifactId>backend-accounting</artifactId>
	<name>backend-accounting</name>
	<description>Accounting bounded context: chart of accounts, journal, periods, reports</description>
```

- [ ] **Step 2: Write the test infrastructure and the failing tests**

Copy `backend/task/src/test/resources/app-runtime-role.sql` to `backend/accounting/src/test/resources/`. Copy `backend/task/src/test/java/com/example/task/TestcontainersConfiguration.java` to `backend/accounting/src/test/java/com/example/accounting/`, changing only the package.

`AccountingTestSupport.java`:

```java
package com.example.accounting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultMatcher;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.security.KeycloakAuthentication;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * Boots the module alone. Each test instance gets its own random tenant, so tests need no cleanup, which matters
 * because posted entries can never be deleted. All subclasses share one Spring context and one Postgres container.
 */
@SpringBootTest(classes = AccountingTestSupport.TestApp.class,
		properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test",
				"spring.jpa.hibernate.ddl-auto=validate" })
@AutoConfigureMockMvc
abstract class AccountingTestSupport {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ AccountingModule.class, TestcontainersConfiguration.class })
	static class TestApp {
	}

	static final int YEAR = LocalDate.now(ZoneId.of("Asia/Jakarta")).getYear();

	static final String DATE = YEAR + "-03-15";

	static final String READ = "accounting:read";

	static final String WRITE = "accounting:write";

	static final String POST = "accounting:post";

	static final String PERIOD_MANAGE = "accounting:period-manage";

	static final String ACCOUNT_MANAGE = "accounting:account-manage";

	static final String[] ALL = { READ, WRITE, POST, PERIOD_MANAGE, ACCOUNT_MANAGE };

	@Autowired
	MockMvc mvc;

	@Autowired
	PlatformTransactionManager transactionManager;

	@PersistenceContext
	EntityManager entityManager;

	final UUID tenant = UUID.randomUUID();

	/** A request of this test's tenant by the user {@code sub}, holding exactly these permissions. */
	RequestPostProcessor as(String sub, String... permissions) {
		return asTenant(tenant, sub, permissions);
	}

	RequestPostProcessor admin() {
		return as("admin-sub", ALL);
	}

	static RequestPostProcessor asTenant(UUID tenant, String sub, String... permissions) {
		List<GrantedAuthority> authorities = Arrays.stream(permissions)
				.<GrantedAuthority>map(SimpleGrantedAuthority::new)
				.toList();
		return authentication(new KeycloakAuthentication(jwt(sub), authorities, sub + "-name", tenant));
	}

	private static Jwt jwt(String sub) {
		return Jwt.withTokenValue("t").header("alg", "none").subject(sub).build();
	}

	/** Runs work in a transaction as this tenant; the connection gets app.tenant_id from the tenancy module. */
	<T> T inTenantTransaction(UUID tenant, String sub, Supplier<T> work) {
		SecurityContextHolder.getContext()
				.setAuthentication(new KeycloakAuthentication(jwt(sub), List.of(), sub + "-name", tenant));
		try {
			return new TransactionTemplate(transactionManager).execute(status -> work.get());
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	<T> T inTransaction(Supplier<T> work) {
		return inTenantTransaction(tenant, "test-sub", work);
	}

	/** Asserts an RFC 9457 problem with this status and stable code. */
	static ResultMatcher problem(int httpStatus, String code) {
		return ResultMatcher.matchAll(status().is(httpStatus), jsonPath("$.code").value(code));
	}

}
```

`AccountingModuleTests.java`:

```java
package com.example.accounting;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.Arrays;

import org.flywaydb.core.Flyway;
import org.flywaydb.core.api.Location;
import org.flywaydb.core.api.configuration.FluentConfiguration;
import org.junit.jupiter.api.Test;

class AccountingModuleTests {

	private static String[] locationsAfter(String... configured) {
		FluentConfiguration configuration = Flyway.configure();
		if (configured.length > 0) {
			configuration.locations(configured);
		}
		new AccountingModule().accountingMigrations().customize(configuration);
		return Arrays.stream(configuration.getLocations()).map(Location::getDescriptor).toArray(String[]::new);
	}

	@Test
	void replacesTheDefaultLocationWithItsOwnFolder() {
		assertThat(locationsAfter()).containsExactly("classpath:db/migration/accounting");
	}

	@Test
	void keepsOtherConfiguredLocations() {
		assertThat(locationsAfter("classpath:db/test/accounting"))
				.containsExactlyInAnyOrder("classpath:db/test/accounting", "classpath:db/migration/accounting");
	}

}
```

`SchemaTests.java`:

```java
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

	@Test
	void withoutATenantTenantTablesLookEmpty() {
		assertThat(jdbc.queryForObject("select count(*) from accounting.account", Long.class)).isZero();
	}

}
```

In `backend/iam/src/test/java/com/example/iam/IamApiTests.java`, replace `seededRolesHaveTheirPermissions` and `severalRolesAreUnioned`, and add one test after them:

```java
	@Test
	void seededRolesHaveTheirPermissions() {
		assertThat(resolver.permissionsForRoles(Set.of("VIEWER"))).containsExactlyInAnyOrder("task:read", "accounting:read");
		assertThat(resolver.permissionsForRoles(Set.of("EDITOR"))).containsExactlyInAnyOrder("task:read", "task:write");
		assertThat(resolver.permissionsForRoles(Set.of("ADMIN"))).containsExactlyInAnyOrder("task:read", "task:write",
				"task:delete", "iam:read", "accounting:read", "accounting:write", "accounting:post",
				"accounting:period-manage", "accounting:account-manage");
	}

	@Test
	void severalRolesAreUnioned() {
		assertThat(resolver.permissionsForRoles(Set.of("VIEWER", "EDITOR")))
				.containsExactlyInAnyOrder("task:read", "task:write", "accounting:read");
	}

	@Test
	void accountingRolesHaveTheirPermissions() {
		assertThat(resolver.permissionsForRoles(Set.of("BOOKKEEPER")))
				.containsExactlyInAnyOrder("accounting:read", "accounting:write");
		assertThat(resolver.permissionsForRoles(Set.of("ACCOUNTANT"))).containsExactlyInAnyOrder("accounting:read",
				"accounting:write", "accounting:post", "accounting:period-manage");
	}
```

In the same file, the line `assertThat(resolver.permissionsForRoles(Set.of("default-roles-zk-spring", "VIEWER"))).containsExactly("task:read");` also pins `VIEWER`: change it to end with `.containsExactlyInAnyOrder("task:read", "accounting:read");`.

The `/api/iam/roles` test also pins the role list, which is sorted by role name. Replace its last four expectations with:

```java
				.andExpect(jsonPath("$[0].role").value("ACCOUNTANT"))
				.andExpect(jsonPath("$[1].role").value("ADMIN"))
				.andExpect(jsonPath("$[1].permissions", contains("accounting:account-manage", "accounting:period-manage",
						"accounting:post", "accounting:read", "accounting:write", "iam:read", "task:delete", "task:read",
						"task:write")))
				.andExpect(jsonPath("$[2].role").value("BOOKKEEPER"))
				.andExpect(jsonPath("$[3].role").value("EDITOR"))
				.andExpect(jsonPath("$[4].role").value("VIEWER"));
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: compile error, `cannot find symbol: class AccountingModule`.

- [ ] **Step 4: Write the module and the migrations**

`AccountingModule.java`:

```java
package com.example.accounting;

import java.time.Clock;
import java.time.ZoneId;
import java.util.Arrays;
import java.util.stream.Stream;

import org.flywaydb.core.api.Location;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.boot.persistence.autoconfigure.EntityScan;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;
import org.springframework.data.jpa.repository.config.EnableJpaRepositories;

import com.example.common.CommonModule;
import com.example.security.SecurityModule;
import com.example.tenancy.TenancyModule;

/**
 * Entry point of the accounting bounded context. A deployable app includes it with
 * {@code @Import(AccountingModule.class)}. The module owns Postgres schema {@code accounting} and its migrations
 * ({@code db/migration/accounting}); every tenant table is isolated as described in {@code TenancyModule}.
 */
@Configuration(proxyBeanMethods = false)
@Import({ CommonModule.class, SecurityModule.class, TenancyModule.class })
@ComponentScan(basePackageClasses = AccountingModule.class)
@EntityScan(basePackageClasses = AccountingModule.class)
@EnableJpaRepositories(basePackageClasses = AccountingModule.class)
public class AccountingModule {

	@Bean
	FlywayConfigurationCustomizer accountingMigrations() {
		// Boot's default db/migration scans recursively, so Flyway would discard this folder and run every module's.
		return configuration -> configuration.locations(Stream.concat(
				Arrays.stream(configuration.getLocations()).filter(l -> !l.getDescriptor().equals("classpath:db/migration")),
				Stream.of(new Location("classpath:db/migration/accounting"))).toArray(Location[]::new));
	}

	/** "Today" for periods and default dates: the companies this is sold to work on Indonesian time. */
	@Bean
	Clock accountingClock() {
		return Clock.system(ZoneId.of("Asia/Jakarta"));
	}

}
```

`V20261102_2__create_accounting_schema.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS accounting;

-- Global and read-only for the API: the starting chart of accounts every tenant copies at setup.
CREATE TABLE accounting.coa_template (
    code        VARCHAR(20)  PRIMARY KEY,
    name        VARCHAR(200) NOT NULL,
    type        VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    parent_code VARCHAR(20),
    postable    BOOLEAN      NOT NULL
);

CREATE TABLE accounting.settings (
    id                      BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id               UUID         NOT NULL UNIQUE,
    require_separate_poster BOOLEAN      NOT NULL DEFAULT TRUE,
    initialized_at          TIMESTAMPTZ,
    updated_by_sub          VARCHAR(100),
    updated_by_name         VARCHAR(200),
    updated_at              TIMESTAMPTZ
);

CREATE TABLE accounting.account (
    id        BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id UUID         NOT NULL,
    code      VARCHAR(20)  NOT NULL,
    name      VARCHAR(200) NOT NULL,
    type      VARCHAR(20)  NOT NULL CHECK (type IN ('ASSET', 'LIABILITY', 'EQUITY', 'REVENUE', 'EXPENSE')),
    parent_id BIGINT,
    postable  BOOLEAN      NOT NULL,
    active    BOOLEAN      NOT NULL DEFAULT TRUE,
    UNIQUE (tenant_id, code),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, parent_id) REFERENCES accounting.account (tenant_id, id)
);

CREATE TABLE accounting.fiscal_period (
    id             BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id      UUID        NOT NULL,
    year           INT         NOT NULL,
    month          INT         NOT NULL CHECK (month BETWEEN 1 AND 12),
    start_date     DATE        NOT NULL,
    end_date       DATE        NOT NULL,
    status         VARCHAR(10) NOT NULL CHECK (status IN ('OPEN', 'CLOSED')),
    closed_by_sub  VARCHAR(100),
    closed_by_name VARCHAR(200),
    closed_at      TIMESTAMPTZ,
    UNIQUE (tenant_id, year, month),
    UNIQUE (tenant_id, id)
);

CREATE TABLE accounting.entry_sequence (
    tenant_id  UUID   NOT NULL,
    year       INT    NOT NULL,
    next_value BIGINT NOT NULL,
    PRIMARY KEY (tenant_id, year)
);

CREATE TABLE accounting.journal_entry (
    id                    BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id             UUID         NOT NULL,
    entry_no              VARCHAR(20),
    entry_date            DATE         NOT NULL,
    period_id             BIGINT       NOT NULL,
    reference             VARCHAR(100),
    description           VARCHAR(500) NOT NULL,
    status                VARCHAR(10)  NOT NULL CHECK (status IN ('DRAFT', 'POSTED', 'REVERSED')),
    reversal_of           BIGINT,
    reversed_by           BIGINT,
    created_by_sub        VARCHAR(100) NOT NULL,
    created_by_name       VARCHAR(200) NOT NULL,
    created_at            TIMESTAMPTZ  NOT NULL,
    last_modified_by_sub  VARCHAR(100) NOT NULL,
    last_modified_by_name VARCHAR(200) NOT NULL,
    last_modified_at      TIMESTAMPTZ  NOT NULL,
    posted_by_sub         VARCHAR(100),
    posted_by_name        VARCHAR(200),
    posted_at             TIMESTAMPTZ,
    version               BIGINT       NOT NULL,
    UNIQUE (tenant_id, entry_no),
    UNIQUE (tenant_id, id),
    FOREIGN KEY (tenant_id, period_id) REFERENCES accounting.fiscal_period (tenant_id, id)
);
CREATE INDEX journal_entry_date_status_idx ON accounting.journal_entry (tenant_id, entry_date, status);

CREATE TABLE accounting.journal_line (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  UUID           NOT NULL,
    entry_id   BIGINT         NOT NULL,
    line_no    INT            NOT NULL,
    account_id BIGINT         NOT NULL,
    debit      NUMERIC(19, 2) NOT NULL DEFAULT 0,
    credit     NUMERIC(19, 2) NOT NULL DEFAULT 0,
    memo       VARCHAR(200),
    CHECK (debit >= 0 AND credit >= 0 AND (debit = 0) <> (credit = 0)),
    UNIQUE (tenant_id, entry_id, line_no),
    FOREIGN KEY (tenant_id, entry_id) REFERENCES accounting.journal_entry (tenant_id, id),
    FOREIGN KEY (tenant_id, account_id) REFERENCES accounting.account (tenant_id, id)
);
CREATE INDEX journal_line_account_idx ON accounting.journal_line (tenant_id, account_id);

CREATE TABLE accounting.audit_log (
    id         BIGINT GENERATED ALWAYS AS IDENTITY PRIMARY KEY,
    tenant_id  UUID         NOT NULL,
    action     VARCHAR(30)  NOT NULL CHECK (action IN ('SETUP', 'POST', 'REVERSE', 'PERIOD_CLOSE', 'PERIOD_REOPEN',
                                                       'SETTING_CHANGE')),
    entity     VARCHAR(30)  NOT NULL,
    entity_id  BIGINT,
    actor_sub  VARCHAR(100) NOT NULL,
    actor_name VARCHAR(200) NOT NULL,
    at         TIMESTAMPTZ  NOT NULL,
    details    JSONB
);
CREATE INDEX audit_log_tenant_at_idx ON accounting.audit_log (tenant_id, at);

ALTER TABLE accounting.settings ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.settings
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.account ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.account
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.fiscal_period ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.fiscal_period
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.entry_sequence ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.entry_sequence
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.journal_entry ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.journal_entry
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.journal_line ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.journal_line
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

ALTER TABLE accounting.audit_log ENABLE ROW LEVEL SECURITY;
CREATE POLICY tenant_isolation ON accounting.audit_log
    USING      (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid)
    WITH CHECK (tenant_id = nullif(current_setting('app.tenant_id', true), '')::uuid);

-- The API connects as app_runtime (created outside Flyway). Grants are per table on purpose: no default privileges,
-- so a future table gets no access until its migration has also enabled row-level security.
GRANT USAGE ON SCHEMA accounting TO app_runtime;
GRANT SELECT ON accounting.coa_template TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON accounting.settings TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounting.account TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON accounting.fiscal_period TO app_runtime;
GRANT SELECT, INSERT, UPDATE ON accounting.entry_sequence TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounting.journal_entry TO app_runtime;
GRANT SELECT, INSERT, UPDATE, DELETE ON accounting.journal_line TO app_runtime;
GRANT SELECT, INSERT ON accounting.audit_log TO app_runtime;
GRANT USAGE ON ALL SEQUENCES IN SCHEMA accounting TO app_runtime;
```

`V20261102_3__seed_coa_template.sql`:

```sql
INSERT INTO accounting.coa_template (code, name, type, parent_code, postable) VALUES
    ('1000', 'Aset',                            'ASSET',     NULL,   FALSE),
    ('1100', 'Aset Lancar',                     'ASSET',     '1000', FALSE),
    ('1110', 'Kas',                             'ASSET',     '1100', TRUE),
    ('1120', 'Bank',                            'ASSET',     '1100', TRUE),
    ('1130', 'Piutang Usaha',                   'ASSET',     '1100', TRUE),
    ('1140', 'Persediaan',                      'ASSET',     '1100', TRUE),
    ('1150', 'PPN Masukan',                     'ASSET',     '1100', TRUE),
    ('1160', 'Biaya Dibayar di Muka',           'ASSET',     '1100', TRUE),
    ('1200', 'Aset Tetap',                      'ASSET',     '1000', FALSE),
    ('1210', 'Peralatan',                       'ASSET',     '1200', TRUE),
    ('1220', 'Kendaraan',                       'ASSET',     '1200', TRUE),
    ('1290', 'Akumulasi Penyusutan',            'ASSET',     '1200', TRUE),
    ('2000', 'Liabilitas',                      'LIABILITY', NULL,   FALSE),
    ('2100', 'Utang Usaha',                     'LIABILITY', '2000', TRUE),
    ('2110', 'PPN Keluaran',                    'LIABILITY', '2000', TRUE),
    ('2120', 'Utang PPh 21',                    'LIABILITY', '2000', TRUE),
    ('2130', 'Utang PPh 23',                    'LIABILITY', '2000', TRUE),
    ('2140', 'Biaya yang Masih Harus Dibayar',  'LIABILITY', '2000', TRUE),
    ('2200', 'Utang Bank Jangka Panjang',       'LIABILITY', '2000', TRUE),
    ('3000', 'Ekuitas',                         'EQUITY',    NULL,   FALSE),
    ('3100', 'Modal Disetor',                   'EQUITY',    '3000', TRUE),
    ('3200', 'Laba Ditahan',                    'EQUITY',    '3000', TRUE),
    ('3300', 'Laba Tahun Berjalan',             'EQUITY',    '3000', TRUE),
    ('4000', 'Pendapatan',                      'REVENUE',   NULL,   FALSE),
    ('4100', 'Penjualan',                       'REVENUE',   '4000', TRUE),
    ('4200', 'Pendapatan Jasa',                 'REVENUE',   '4000', TRUE),
    ('4900', 'Pendapatan Lain-lain',            'REVENUE',   '4000', TRUE),
    ('5000', 'Beban Pokok Penjualan',           'EXPENSE',   NULL,   FALSE),
    ('5100', 'Harga Pokok Penjualan',           'EXPENSE',   '5000', TRUE),
    ('6000', 'Beban Operasional',               'EXPENSE',   NULL,   FALSE),
    ('6100', 'Beban Gaji',                      'EXPENSE',   '6000', TRUE),
    ('6110', 'Beban Perlengkapan Kantor',       'EXPENSE',   '6000', TRUE),
    ('6120', 'Beban Sewa',                      'EXPENSE',   '6000', TRUE),
    ('6130', 'Beban Listrik, Air dan Internet', 'EXPENSE',   '6000', TRUE),
    ('6140', 'Beban Penyusutan',                'EXPENSE',   '6000', TRUE),
    ('6190', 'Beban Lain-lain',                 'EXPENSE',   '6000', TRUE),
    ('7000', 'Beban Non-Operasional',           'EXPENSE',   NULL,   FALSE),
    ('7100', 'Beban Bunga',                     'EXPENSE',   '7000', TRUE),
    ('7200', 'Beban Administrasi Bank',         'EXPENSE',   '7000', TRUE);
```

`backend/iam/src/main/resources/db/migration/iam/V20261102_1__add_accounting_permissions.sql`:

```sql
INSERT INTO iam.permission (code, description) VALUES
    ('accounting:read',           'View accounts, periods, journal entries and reports'),
    ('accounting:write',          'Create, edit and delete draft journal entries'),
    ('accounting:post',           'Post and reverse journal entries'),
    ('accounting:period-manage',  'Open, close and reopen fiscal periods'),
    ('accounting:account-manage', 'Set up accounting, manage accounts and accounting settings');

INSERT INTO iam.role_permission (role, permission_code) VALUES
    ('VIEWER',     'accounting:read'),
    ('BOOKKEEPER', 'accounting:read'),
    ('BOOKKEEPER', 'accounting:write'),
    ('ACCOUNTANT', 'accounting:read'),
    ('ACCOUNTANT', 'accounting:write'),
    ('ACCOUNTANT', 'accounting:post'),
    ('ACCOUNTANT', 'accounting:period-manage'),
    ('ADMIN',      'accounting:read'),
    ('ADMIN',      'accounting:write'),
    ('ADMIN',      'accounting:post'),
    ('ADMIN',      'accounting:period-manage'),
    ('ADMIN',      'accounting:account-manage');
```

In `ApiApplication.java`, add `import com.example.accounting.AccountingModule;` above the `iam` import, and change the annotation to `@Import({ TaskModule.class, IamModule.class, AccountingModule.class })`.

- [ ] **Step 5: Add the realm roles and demo users**

In `infra/keycloak/zk-spring-realm.json`, add to `roles.realm`:

```json
{"name": "BOOKKEEPER", "description": "Accounting: read and prepare draft entries"},
{"name": "ACCOUNTANT", "description": "Accounting: post and reverse entries, manage periods"}
```

Add to `users`:

```json
{"username": "bookkeeper", "enabled": true, "email": "bookkeeper@example.com", "emailVerified": true, "firstName": "Budi", "lastName": "Bookkeeper", "credentials": [{"type": "password", "value": "bookkeeper", "temporary": false}], "realmRoles": ["BOOKKEEPER", "default-roles-zk-spring"]},
{"username": "accountant", "enabled": true, "email": "accountant@example.com", "emailVerified": true, "firstName": "Ani", "lastName": "Accountant", "credentials": [{"type": "password", "value": "accountant", "temporary": false}], "realmRoles": ["ACCOUNTANT", "default-roles-zk-spring"]}
```

Add `{"username": "bookkeeper"}` and `{"username": "accountant"}` to the `members` of organization `demo`. Verify: `python3 -m json.tool infra/keycloak/zk-spring-realm.json > /dev/null`, and that there is still no top-level `clientScopes` key.

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting,backend/iam -am test`
Expected: BUILD SUCCESS. Then run `./mvnw -q -pl backend/app -am test`: the boundary test `ApiApplicationTests.everyTableTheApiCanWriteHasRowLevelSecurity` now also checks every accounting table and must pass.

- [ ] **Step 7: Commit**

```bash
git add pom.xml backend/pom.xml backend/app/pom.xml backend/app/src/main/java/com/example/app/ApiApplication.java backend/accounting backend/iam/src/main/resources/db/migration/iam/V20261102_1__add_accounting_permissions.sql backend/iam/src/test/java/com/example/iam/IamApiTests.java infra/keycloak/zk-spring-realm.json
git commit -m "Add the accounting module: schema, chart template, permissions, demo users

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Database guards

**Files:**
- Create: `backend/accounting/src/main/resources/db/migration/accounting/V20261102_4__accounting_guards.sql`
- Modify: `backend/accounting/src/test/java/com/example/accounting/AccountingTestSupport.java` (native insert helpers)
- Test: `backend/accounting/src/test/java/com/example/accounting/DatabaseGuardTests.java`

**Interfaces:**
- Consumes: the schema from Task 1.
- Produces: helpers on `AccountingTestSupport`, valid inside a tenant transaction: `long insertAccount(String code, boolean postable, boolean active)`, `long insertPeriod(int year, int month)`, `long insertEntry(long periodId, String status)`, `void insertLine(long entryId, int lineNo, long accountId, String debit, String credit)`. Trigger messages later tasks may assert: `is not balanced`, `cannot be changed`, `is not postable and active`.

- [ ] **Step 1: Add the native helpers**

Add to `AccountingTestSupport`:

```java
	/** Native inserts that bypass the services; call inside a transaction of this test's tenant. */
	long insertAccount(String code, boolean postable, boolean active) {
		return ((Number) entityManager.createNativeQuery("""
				insert into accounting.account (tenant_id, code, name, type, postable, active)
				values (cast(:tenant as uuid), :code, :code, 'ASSET', :postable, :active) returning id
				""").setParameter("tenant", tenantOfTransaction().toString()).setParameter("code", code)
				.setParameter("postable", postable).setParameter("active", active).getSingleResult()).longValue();
	}

	long insertPeriod(int year, int month) {
		return ((Number) entityManager.createNativeQuery("""
				insert into accounting.fiscal_period (tenant_id, year, month, start_date, end_date, status)
				values (cast(:tenant as uuid), :year, :month, make_date(:year, :month, 1),
				        cast(make_date(:year, :month, 1) + interval '1 month - 1 day' as date), 'OPEN') returning id
				""").setParameter("tenant", tenantOfTransaction().toString())
				.setParameter("year", year).setParameter("month", month).getSingleResult()).longValue();
	}

	long insertEntry(long periodId, String status) {
		return ((Number) entityManager.createNativeQuery("""
				insert into accounting.journal_entry (tenant_id, entry_date, period_id, description, status,
				    created_by_sub, created_by_name, created_at, last_modified_by_sub, last_modified_by_name,
				    last_modified_at, version)
				select cast(:tenant as uuid), p.start_date, p.id, 'native', :status, 's', 'n', now(), 's', 'n', now(), 0
				from accounting.fiscal_period p where p.id = :period returning id
				""").setParameter("tenant", tenantOfTransaction().toString()).setParameter("status", status)
				.setParameter("period", periodId).getSingleResult()).longValue();
	}

	void insertLine(long entryId, int lineNo, long accountId, String debit, String credit) {
		entityManager.createNativeQuery("""
				insert into accounting.journal_line (tenant_id, entry_id, line_no, account_id, debit, credit)
				values (cast(:tenant as uuid), :entry, :lineNo, :account, cast(:debit as numeric), cast(:credit as numeric))
				""").setParameter("tenant", tenantOfTransaction().toString()).setParameter("entry", entryId)
				.setParameter("lineNo", lineNo).setParameter("account", accountId).setParameter("debit", debit)
				.setParameter("credit", credit).executeUpdate();
	}

	/** Runs native SQL with no result in the current transaction. */
	int sql(String statement, long id) {
		return entityManager.createNativeQuery(statement).setParameter("id", id).executeUpdate();
	}

	private static UUID tenantOfTransaction() {
		return ((KeycloakAuthentication) SecurityContextHolder.getContext().getAuthentication()).getTenantId().orElseThrow();
	}
```

- [ ] **Step 2: Write the failing tests**

`DatabaseGuardTests.java`:

```java
package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bypasses the services with native SQL as app_runtime: these invariants must hold even if a service is wrong. */
class DatabaseGuardTests extends AccountingTestSupport {

	/** Creates a balanced posted entry and returns {entryId, firstLineId}. */
	private long[] postedEntry() {
		return inTransaction(() -> {
			long cash = insertAccount("A", true, true);
			long sales = insertAccount("B", true, true);
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, cash, "100.00", "0");
			insertLine(entry, 2, sales, "0", "100.00");
			sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id", entry);
			long line = ((Number) entityManager
					.createNativeQuery("select min(id) from accounting.journal_line where entry_id = :id")
					.setParameter("id", entry).getSingleResult()).longValue();
			return new long[] { entry, line };
		});
	}

	@Test
	void postedUnbalancedEntryIsRejectedAtCommit() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long cash = insertAccount("A", true, true);
			long sales = insertAccount("B", true, true);
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, cash, "100.00", "0");
			insertLine(entry, 2, sales, "0", "50.00");
			return sql("update accounting.journal_entry set status = 'POSTED' where id = :id", entry);
		})).hasStackTraceContaining("is not balanced");
	}

	@Test
	void postedEntryWithFewerThanTwoLinesIsRejectedAtCommit() {
		assertThatThrownBy(() -> inTransaction(() -> insertEntry(insertPeriod(YEAR, 1), "POSTED")))
				.hasStackTraceContaining("is not balanced");
	}

	@Test
	void balancedPostedEntryIsAccepted() {
		assertThatCode(this::postedEntry).doesNotThrowAnyException();
	}

	@Test
	void postedLinesCannotBeChangedOrDeleted() {
		long[] posted = postedEntry();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_line set memo = 'x' where id = :id", posted[1])))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(
				() -> sql("delete from accounting.journal_line where id = :id", posted[1])))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(posted[0], 3, insertAccount("C", true, true), "1.00", "0");
			return 0;
		})).hasStackTraceContaining("cannot be changed");
	}

	@Test
	void postedEntryCannotBeChangedOrDeleted() {
		long[] posted = postedEntry();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_entry set description = 'x' where id = :id", posted[0])))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(
				() -> sql("delete from accounting.journal_entry where id = :id", posted[0])))
				.hasStackTraceContaining("cannot be changed");
	}

	@Test
	void onlyPostedToReversedWithAReversingEntryIsAllowed() {
		long[] posted = postedEntry();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_entry set status = 'REVERSED' where id = :id", posted[0])))
				.hasStackTraceContaining("cannot be changed");
		inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'REVERSED', reversed_by = 1, version = version + 1 where id = :id",
				posted[0]));
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_entry set status = 'POSTED' where id = :id", posted[0])))
				.hasStackTraceContaining("cannot be changed");
	}

	@Test
	void draftsStayEditable() {
		assertThatCode(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, insertAccount("A", true, true), "1.00", "0");
			sql("update accounting.journal_line set memo = 'x' where entry_id = :id", entry);
			sql("delete from accounting.journal_line where entry_id = :id", entry);
			return sql("delete from accounting.journal_entry where id = :id", entry);
		})).doesNotThrowAnyException();
	}

	@Test
	void linesNeedAPostableActiveAccount() {
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(insertEntry(insertPeriod(YEAR, 1), "DRAFT"), 1, insertAccount("H", false, true), "1.00", "0");
			return 0;
		})).hasStackTraceContaining("is not postable and active");
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(insertEntry(insertPeriod(YEAR, 2), "DRAFT"), 1, insertAccount("I", true, false), "1.00", "0");
			return 0;
		})).hasStackTraceContaining("is not postable and active");
	}

	@Test
	void aLineCannotUseAnotherTenantsAccount() {
		UUID other = UUID.randomUUID();
		long foreignAccount = inTenantTransaction(other, "other", () -> insertAccount("X", true, true));
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(insertEntry(insertPeriod(YEAR, 1), "DRAFT"), 1, foreignAccount, "1.00", "0");
			return 0;
		})).hasStackTraceContaining("foreign key");
	}

	@Test
	void anotherTenantCannotSeeOrTouchAPostedEntry() {
		long[] posted = postedEntry();
		UUID other = UUID.randomUUID();
		int changed = inTenantTransaction(other, "other",
				() -> sql("update accounting.journal_entry set description = 'x' where id = :id", posted[0]));
		assertThat(changed).isZero();
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/accounting -am test -Dtest=DatabaseGuardTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL. `postedUnbalancedEntryIsRejectedAtCommit` and others: no exception thrown, because no guard exists. `aLineCannotUseAnotherTenantsAccount`, `draftsStayEditable`, `balancedPostedEntryIsAccepted`, and `anotherTenantCannotSeeOrTouchAPostedEntry` already pass; they pin behaviour the guards must not break.

- [ ] **Step 4: Write the guards**

`V20261102_4__accounting_guards.sql`:

```sql
-- Defence in depth behind the service checks: a violation here is a bug in the service, not a user error.
-- These run as the caller (app_runtime), so row-level security also applies to what they read.

CREATE FUNCTION accounting.guard_posted_entry_balances() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    line_count BIGINT;
    debits     NUMERIC;
    credits    NUMERIC;
BEGIN
    IF NEW.status <> 'POSTED' THEN
        RETURN NULL;
    END IF;
    SELECT count(*), coalesce(sum(debit), 0), coalesce(sum(credit), 0)
    INTO line_count, debits, credits
    FROM accounting.journal_line
    WHERE tenant_id = NEW.tenant_id AND entry_id = NEW.id;
    IF line_count < 2 OR debits <> credits OR debits = 0 THEN
        RAISE EXCEPTION 'posted journal entry % is not balanced: % lines, debit %, credit %',
            NEW.id, line_count, debits, credits;
    END IF;
    RETURN NULL;
END $$;

CREATE CONSTRAINT TRIGGER posted_entry_balances
    AFTER INSERT OR UPDATE ON accounting.journal_entry
    DEFERRABLE INITIALLY DEFERRED
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_posted_entry_balances();

CREATE FUNCTION accounting.guard_entry_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
BEGIN
    IF OLD.status = 'DRAFT' THEN
        IF TG_OP = 'DELETE' THEN
            RETURN OLD;
        END IF;
        RETURN NEW;
    END IF;
    -- The single allowed change; version may move because JPA increments it on every update.
    IF TG_OP = 'UPDATE' AND OLD.status = 'POSTED' AND NEW.status = 'REVERSED'
       AND OLD.reversed_by IS NULL AND NEW.reversed_by IS NOT NULL
       AND to_jsonb(NEW) - 'status' - 'reversed_by' - 'version'
           = to_jsonb(OLD) - 'status' - 'reversed_by' - 'version' THEN
        RETURN NEW;
    END IF;
    RAISE EXCEPTION 'journal entry % is % and cannot be changed', OLD.id, OLD.status;
END $$;

CREATE TRIGGER entry_immutable
    BEFORE UPDATE OR DELETE ON accounting.journal_entry
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_entry_immutable();

CREATE FUNCTION accounting.guard_line_immutable() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    parent_status VARCHAR;
BEGIN
    IF TG_OP <> 'INSERT' THEN
        SELECT status INTO parent_status FROM accounting.journal_entry
        WHERE tenant_id = OLD.tenant_id AND id = OLD.entry_id;
        IF parent_status IN ('POSTED', 'REVERSED') THEN
            RAISE EXCEPTION 'lines of journal entry % are % and cannot be changed', OLD.entry_id, parent_status;
        END IF;
    END IF;
    IF TG_OP = 'DELETE' THEN
        RETURN OLD;
    END IF;
    SELECT status INTO parent_status FROM accounting.journal_entry
    WHERE tenant_id = NEW.tenant_id AND id = NEW.entry_id;
    IF parent_status IN ('POSTED', 'REVERSED') THEN
        RAISE EXCEPTION 'lines of journal entry % are % and cannot be changed', NEW.entry_id, parent_status;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER line_immutable
    BEFORE INSERT OR UPDATE OR DELETE ON accounting.journal_line
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_line_immutable();

CREATE FUNCTION accounting.guard_line_account() RETURNS trigger LANGUAGE plpgsql AS $$
DECLARE
    usable BOOLEAN;
BEGIN
    SELECT postable AND active INTO usable FROM accounting.account
    WHERE tenant_id = NEW.tenant_id AND id = NEW.account_id;
    -- No such account in this tenant: the composite foreign key rejects it.
    IF FOUND AND NOT usable THEN
        RAISE EXCEPTION 'account % is not postable and active', NEW.account_id;
    END IF;
    RETURN NEW;
END $$;

CREATE TRIGGER line_account
    BEFORE INSERT OR UPDATE OF account_id ON accounting.journal_line
    FOR EACH ROW EXECUTE FUNCTION accounting.guard_line_account();
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 6: Commit**

```bash
git add backend/accounting
git commit -m "Guard posted entries in the database: balance, immutability, usable accounts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Errors, setup, settings and accounts

**Files:**
- Modify: `backend/common/src/main/java/com/example/common/GlobalExceptionHandler.java`, `backend/security/src/main/java/com/example/security/AccessDeniedProblemHandler.java`
- Create in `backend/accounting/src/main/java/com/example/accounting/`: `AccountingException.java`, `AccountingNotFoundException.java`, `AccountingExceptionHandler.java`, `Actor.java`, `AuditAction.java`, `AuditLog.java`, `AuditLogRepository.java`, `AuditTrail.java`, `AccountType.java`, `CoaTemplate.java`, `CoaTemplateRepository.java`, `Account.java`, `AccountRepository.java`, `AccountRequest.java`, `AccountResponse.java`, `AccountService.java`, `AccountController.java`, `Settings.java`, `SettingsRepository.java`, `SettingsRequest.java`, `SettingsResponse.java`, `AccountingSettings.java`, `SetupService.java`, `SetupController.java`, `PeriodStatus.java`, `FiscalPeriod.java`, `FiscalPeriodRepository.java`, `PeriodService.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/SetupAndAccountsTests.java`

**Interfaces:**
- Consumes: `AccountingTestSupport`, `Clock accountingClock`.
- Produces:
  - `AccountingException(HttpStatus status, String code, String detail)` with static factories `badRequest(code, detail)`, `forbidden(code, detail)`, `conflict(code, detail)`, `unprocessable(code, detail)`.
  - `AccountingNotFoundException(String what)` (404 `not-found`).
  - `record Actor(String sub, String name)` with `static Actor current()`.
  - `AuditTrail.record(AuditAction action, String entity, Long entityId, Map<String, ?> details)`; `AuditAction` values `SETUP, POST, REVERSE, PERIOD_CLOSE, PERIOD_REOPEN, SETTING_CHANGE`.
  - `AccountingSettings.current()` → `Optional<Settings>`; `AccountingSettings.require()` → `Settings` or 422 `not-initialized`.
  - `Account` getters `getId, getCode, getName, getType, getParentId, isPostable, isActive`; `AccountRepository extends JpaRepository<Account, Long>`.
  - `FiscalPeriod` getters `getId, getYear, getMonth, getStartDate, getEndDate, getStatus, getClosedByName, getClosedAt`; `FiscalPeriodRepository`; `PeriodService.openYear(int year)`.
  - `PeriodStatus` values `OPEN, CLOSED`; `AccountType` values `ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE` with `boolean debitNormal()`.

- [ ] **Step 1: Write the failing tests**

`SetupAndAccountsTests.java`:

```java
package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SetupAndAccountsTests extends AccountingTestSupport {

	private void setup() throws Exception {
		mvc.perform(post("/api/accounting/setup").with(admin())).andExpect(status().isOk());
	}

	private long accountId(String code) throws Exception {
		String body = mvc.perform(get("/api/accounting/accounts").with(admin())).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.<java.util.List<Object>>read(body, "$[?(@.code=='" + code + "')].id").get(0)).longValue();
	}

	private static String account(String code, String type, Long parentId, boolean postable) {
		return """
				{"code": "%s", "name": "Name %s", "type": "%s", "parentId": %s, "postable": %s}
				""".formatted(code, code, type, parentId, postable);
	}

	private org.springframework.test.web.servlet.ResultActions createAccount(String json) throws Exception {
		return mvc.perform(post("/api/accounting/accounts").with(admin())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void beforeSetupSettingsSayNotInitializedAndAccountsAreEmpty() throws Exception {
		mvc.perform(get("/api/accounting/settings").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.initialized").value(false));
		mvc.perform(get("/api/accounting/accounts").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void setupCopiesTheTemplateOpensTheYearAndIsAudited() throws Exception {
		mvc.perform(post("/api/accounting/setup").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.initialized").value(true))
				.andExpect(jsonPath("$.requireSeparatePoster").value(true));

		mvc.perform(get("/api/accounting/accounts").with(as("u", READ)))
				.andExpect(jsonPath("$", hasSize(39)))
				.andExpect(jsonPath("$[0].code").value("1000"))
				.andExpect(jsonPath("$[0].parentId").isEmpty())
				.andExpect(jsonPath("$[?(@.code=='1110')].postable").value(true));
		long parent = accountId("1100");
		mvc.perform(get("/api/accounting/accounts").with(as("u", READ)))
				.andExpect(jsonPath("$[?(@.code=='1110')].parentId").value((int) parent));

		Number periods = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.fiscal_period where year = :y and status = 'OPEN'")
				.setParameter("y", YEAR).getSingleResult());
		assertThat(periods.longValue()).isEqualTo(12);
		Number audited = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'SETUP' and actor_sub = 'admin-sub'")
				.getSingleResult());
		assertThat(audited.longValue()).isEqualTo(1);
	}

	@Test
	void setupTwiceIsAConflict() throws Exception {
		setup();
		mvc.perform(post("/api/accounting/setup").with(admin())).andExpect(problem(409, "already-initialized"));
	}

	@Test
	void writesBeforeSetupAreRefused() throws Exception {
		createAccount(account("9000", "ASSET", null, true)).andExpect(problem(422, "not-initialized"));
		mvc.perform(put("/api/accounting/settings").with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"requireSeparatePoster\": false}"))
				.andExpect(problem(422, "not-initialized"));
	}

	@Test
	void changingTheSettingIsAudited() throws Exception {
		setup();
		mvc.perform(put("/api/accounting/settings").with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"requireSeparatePoster\": false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requireSeparatePoster").value(false))
				.andExpect(jsonPath("$.updatedByName").value("admin-sub-name"));
		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'SETTING_CHANGE'")
				.getSingleResult());
		assertThat(details).contains("\"old\": true").contains("\"new\": false");
	}

	@Test
	void permissionsAreEnforcedWithAStableCode() throws Exception {
		setup();
		mvc.perform(post("/api/accounting/setup").with(as("u", READ))).andExpect(problem(403, "forbidden"));
		mvc.perform(post("/api/accounting/accounts").with(as("u", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(account("9000", "ASSET", null, true))).andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/accounts").with(as("u"))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void accountsCanBeAddedEditedAndDeleted() throws Exception {
		setup();
		long parent = accountId("1100");
		String created = createAccount(account("1170", "ASSET", parent, true))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.active").value(true))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();

		mvc.perform(put("/api/accounting/accounts/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
						{"code": "1171", "name": "Renamed", "type": "ASSET", "parentId": %d, "postable": true, "active": false}
						""".formatted(parent)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("1171"))
				.andExpect(jsonPath("$.active").value(false));

		mvc.perform(delete("/api/accounting/accounts/" + id).with(admin())).andExpect(status().isNoContent());
		mvc.perform(delete("/api/accounting/accounts/" + id).with(admin())).andExpect(problem(404, "not-found"));
	}

	@Test
	void accountRulesAreEnforced() throws Exception {
		setup();
		long header = accountId("1100");
		long leaf = accountId("1110");

		createAccount(account("1110", "ASSET", header, true)).andExpect(problem(409, "account-code-exists"));
		createAccount(account("1171", "ASSET", leaf, true)).andExpect(problem(400, "validation"));
		createAccount(account("1171", "ASSET", 999999999L, true)).andExpect(problem(400, "validation"));
		createAccount("{\"code\": \"\", \"name\": \"x\", \"type\": \"ASSET\", \"postable\": true}")
				.andExpect(problem(400, "validation"));
		createAccount("{\"code\": \"1\", \"name\": \"x\", \"type\": \"FOO\", \"postable\": true}")
				.andExpect(problem(400, "validation"));

		// A header with children stays a header, and an account cannot become its own ancestor.
		mvc.perform(put("/api/accounting/accounts/" + header).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1100", "ASSET", accountId("1000"), true)))
				.andExpect(problem(400, "validation"));
		long top = accountId("1000");
		mvc.perform(put("/api/accounting/accounts/" + top).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1000", "ASSET", header, false)))
				.andExpect(problem(400, "validation"));

		mvc.perform(delete("/api/accounting/accounts/" + header).with(admin())).andExpect(problem(409, "account-in-use"));
	}

	@Test
	void accountsWithLinesKeepTheirTypeAndCannotBeDeleted() throws Exception {
		setup();
		long cash = accountId("1110");
		inTransaction(() -> {
			long period = ((Number) entityManager.createNativeQuery(
					"select min(id) from accounting.fiscal_period").getSingleResult()).longValue();
			insertLine(insertEntry(period, "DRAFT"), 1, cash, "1.00", "0");
			return 0;
		});

		mvc.perform(put("/api/accounting/accounts/" + cash).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1110", "EXPENSE", accountId("1100"), true)))
				.andExpect(problem(409, "account-in-use"));
		mvc.perform(put("/api/accounting/accounts/" + cash).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1110", "ASSET", accountId("1100"), false)))
				.andExpect(problem(409, "account-in-use"));
		mvc.perform(delete("/api/accounting/accounts/" + cash).with(admin())).andExpect(problem(409, "account-in-use"));
	}

	@Test
	void tenantsDoNotSeeEachOthersAccounts() throws Exception {
		setup();
		long cash = accountId("1110");
		UUID other = UUID.randomUUID();
		mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(delete("/api/accounting/accounts/" + cash).with(asTenant(other, "o", ACCOUNT_MANAGE)))
				.andExpect(problem(404, "not-found"));
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
	}

}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/accounting -am test -Dtest=SetupAndAccountsTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: 404/`No static resource` failures or `Expecting code` failures: no controller exists yet.

- [ ] **Step 3: Add stable codes to the shared handlers**

In `GlobalExceptionHandler.notFound`:

```java
	@ExceptionHandler(ResourceNotFoundException.class)
	ProblemDetail notFound(ResourceNotFoundException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.NOT_FOUND, ex.getMessage());
		problem.setProperty("code", "not-found");
		return problem;
	}
```

In `AccessDeniedProblemHandler.accessDenied`:

```java
	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail accessDenied(AccessDeniedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
				"You do not have permission to perform this action");
		problem.setProperty("code", "forbidden");
		return problem;
	}
```

- [ ] **Step 4: Write errors, actor and audit**

`AccountingException.java`:

```java
package com.example.accounting;

import org.springframework.http.HttpStatus;

/** A refused business rule; rendered as an RFC 9457 problem with a stable {@code code}. */
public class AccountingException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public AccountingException(HttpStatus status, String code, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
	}

	static AccountingException badRequest(String code, String detail) {
		return new AccountingException(HttpStatus.BAD_REQUEST, code, detail);
	}

	static AccountingException forbidden(String code, String detail) {
		return new AccountingException(HttpStatus.FORBIDDEN, code, detail);
	}

	static AccountingException conflict(String code, String detail) {
		return new AccountingException(HttpStatus.CONFLICT, code, detail);
	}

	static AccountingException unprocessable(String code, String detail) {
		return new AccountingException(HttpStatus.valueOf(422), code, detail);
	}

	HttpStatus status() {
		return status;
	}

	String code() {
		return code;
	}

}
```

`AccountingNotFoundException.java`:

```java
package com.example.accounting;

import com.example.common.ResourceNotFoundException;

public class AccountingNotFoundException extends ResourceNotFoundException {

	public AccountingNotFoundException(String what) {
		super(what + " not found");
	}

}
```

`AccountingExceptionHandler.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.InvalidFormatException;

/** Every failure of this module's API is an RFC 9457 problem with a stable {@code code}. */
@RestControllerAdvice(basePackageClasses = AccountingModule.class)
class AccountingExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(AccountingExceptionHandler.class);

	@ExceptionHandler(AccountingException.class)
	ProblemDetail rule(AccountingException ex) {
		return problem(ex.status(), ex.code(), ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalid(MethodArgumentNotValidException ex) {
		String detail = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + " " + error.getDefaultMessage())
				.collect(Collectors.joining(", "));
		return problem(HttpStatus.BAD_REQUEST, "validation", detail);
	}

	@ExceptionHandler({ MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class })
	ProblemDetail badParameter(Exception ex) {
		return problem(HttpStatus.BAD_REQUEST, "validation", ex.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable(HttpMessageNotReadableException ex) {
		if (ex.getMostSpecificCause() instanceof InvalidFormatException format
				&& format.getTargetType() == BigDecimal.class) {
			return problem(HttpStatus.BAD_REQUEST, "invalid-amount", "Amounts must be decimal numbers");
		}
		return problem(HttpStatus.BAD_REQUEST, "validation", "The request body could not be read");
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail staleVersion(ObjectOptimisticLockingFailureException ex) {
		return problem(HttpStatus.CONFLICT, "version-conflict", "Someone else changed this entry");
	}

	/** The services check every rule first, so reaching a database guard is a bug, not a user error. */
	@ExceptionHandler({ DataAccessException.class, TransactionException.class })
	ProblemDetail invariantBug(Exception ex) {
		log.error("A database guard rejected what the service allowed", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "invariant-violated", "The request could not be completed");
	}

	private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

}
```

If the tests show the code at `$.properties.code` instead of `$.code`, the problem mixin is missing from the application's JSON mapper: report it (status NEEDS_CONTEXT); do not change the tests' expectation.

`Actor.java`:

```java
package com.example.accounting;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The acting user: {@code sub} is the identity for checks, {@code name} is only for display. */
record Actor(String sub, String name) {

	static Actor current() {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
			return new Actor(token.getToken().getSubject(), token.getName());
		}
		throw new IllegalStateException("No authenticated user");
	}

}
```

`AuditAction.java`:

```java
package com.example.accounting;

enum AuditAction {

	SETUP, POST, REVERSE, PERIOD_CLOSE, PERIOD_REOPEN, SETTING_CHANGE

}
```

`AuditLog.java`:

```java
package com.example.accounting;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.ColumnTransformer;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "audit_log", schema = "accounting")
class AuditLog {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 30)
	private AuditAction action;

	@Column(nullable = false, length = 30)
	private String entity;

	@Column(name = "entity_id")
	private Long entityId;

	@Column(name = "actor_sub", nullable = false, length = 100)
	private String actorSub;

	@Column(name = "actor_name", nullable = false, length = 200)
	private String actorName;

	@Column(name = "at", nullable = false)
	private Instant at;

	@ColumnTransformer(write = "cast(? as jsonb)")
	@Column(columnDefinition = "jsonb")
	private String details;

	protected AuditLog() {
	}

	AuditLog(AuditAction action, String entity, Long entityId, Actor actor, Instant at, String details) {
		this.action = action;
		this.entity = entity;
		this.entityId = entityId;
		this.actorSub = actor.sub();
		this.actorName = actor.name();
		this.at = at;
		this.details = details;
	}

}
```

`AuditLogRepository.java`:

```java
package com.example.accounting;

import org.springframework.data.jpa.repository.JpaRepository;

interface AuditLogRepository extends JpaRepository<AuditLog, Long> {
}
```

`AuditTrail.java`:

```java
package com.example.accounting;

import java.time.Clock;
import java.util.Map;

import org.springframework.stereotype.Component;
import tools.jackson.databind.json.JsonMapper;

@Component
class AuditTrail {

	private final AuditLogRepository repository;

	private final JsonMapper json;

	private final Clock clock;

	AuditTrail(AuditLogRepository repository, JsonMapper json, Clock clock) {
		this.repository = repository;
		this.json = json;
		this.clock = clock;
	}

	void record(AuditAction action, String entity, Long entityId, Map<String, ?> details) {
		repository.save(new AuditLog(action, entity, entityId, Actor.current(), clock.instant(),
				json.writeValueAsString(details)));
	}

}
```

- [ ] **Step 5: Write the entities**

`AccountType.java`:

```java
package com.example.accounting;

public enum AccountType {

	ASSET, LIABILITY, EQUITY, REVENUE, EXPENSE;

	/** Reports show a balance on the account's normal side. */
	boolean debitNormal() {
		return this == ASSET || this == EXPENSE;
	}

}
```

`CoaTemplate.java`:

```java
package com.example.accounting;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

/** Global, read-only: the chart of accounts every tenant copies at setup. */
@Entity
@Table(name = "coa_template", schema = "accounting")
class CoaTemplate {

	@Id
	@Column(length = 20)
	private String code;

	@Column(nullable = false, length = 200)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountType type;

	@Column(name = "parent_code", length = 20)
	private String parentCode;

	@Column(nullable = false)
	private boolean postable;

	protected CoaTemplate() {
	}

	String getCode() {
		return code;
	}

	String getName() {
		return name;
	}

	AccountType getType() {
		return type;
	}

	String getParentCode() {
		return parentCode;
	}

	boolean isPostable() {
		return postable;
	}

}
```

`CoaTemplateRepository.java`:

```java
package com.example.accounting;

import org.springframework.data.jpa.repository.JpaRepository;

interface CoaTemplateRepository extends JpaRepository<CoaTemplate, String> {
}
```

`Account.java`:

```java
package com.example.accounting;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "account", schema = "accounting")
public class Account {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(nullable = false, length = 20)
	private String code;

	@Column(nullable = false, length = 200)
	private String name;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 20)
	private AccountType type;

	@Column(name = "parent_id")
	private Long parentId;

	@Column(nullable = false)
	private boolean postable;

	@Column(nullable = false)
	private boolean active;

	protected Account() {
	}

	Account(String code, String name, AccountType type, Long parentId, boolean postable, boolean active) {
		update(code, name, type, parentId, postable, active);
	}

	void update(String code, String name, AccountType type, Long parentId, boolean postable, boolean active) {
		this.code = code;
		this.name = name;
		this.type = type;
		this.parentId = parentId;
		this.postable = postable;
		this.active = active;
	}

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public AccountType getType() {
		return type;
	}

	public Long getParentId() {
		return parentId;
	}

	public boolean isPostable() {
		return postable;
	}

	public boolean isActive() {
		return active;
	}

}
```

`AccountRepository.java`:

```java
package com.example.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface AccountRepository extends JpaRepository<Account, Long> {

	boolean existsByCode(String code);

	boolean existsByParentId(Long parentId);

	@Query(nativeQuery = true, value = "select exists (select 1 from accounting.journal_line where account_id = :id)")
	boolean hasLines(@Param("id") long id);

}
```

`Settings.java`:

```java
package com.example.accounting;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

/** One row per tenant, created by setup: its existence means accounting is initialized. */
@Entity
@Table(name = "settings", schema = "accounting")
class Settings {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "require_separate_poster", nullable = false)
	private boolean requireSeparatePoster = true;

	@Column(name = "initialized_at")
	private Instant initializedAt;

	@Column(name = "updated_by_sub", length = 100)
	private String updatedBySub;

	@Column(name = "updated_by_name", length = 200)
	private String updatedByName;

	@Column(name = "updated_at")
	private Instant updatedAt;

	protected Settings() {
	}

	Settings(Instant initializedAt) {
		this.initializedAt = initializedAt;
	}

	void requireSeparatePoster(boolean value, Actor actor, Instant at) {
		this.requireSeparatePoster = value;
		this.updatedBySub = actor.sub();
		this.updatedByName = actor.name();
		this.updatedAt = at;
	}

	Long getId() {
		return id;
	}

	boolean isRequireSeparatePoster() {
		return requireSeparatePoster;
	}

	String getUpdatedByName() {
		return updatedByName;
	}

	Instant getUpdatedAt() {
		return updatedAt;
	}

}
```

`SettingsRepository.java`:

```java
package com.example.accounting;

import java.util.Optional;

import org.springframework.data.jpa.repository.JpaRepository;

interface SettingsRepository extends JpaRepository<Settings, Long> {

	Optional<Settings> findFirstByOrderByIdAsc();

}
```

`AccountingSettings.java`:

```java
package com.example.accounting;

import java.util.Optional;

import org.springframework.stereotype.Component;

@Component
class AccountingSettings {

	private final SettingsRepository repository;

	AccountingSettings(SettingsRepository repository) {
		this.repository = repository;
	}

	Optional<Settings> current() {
		return repository.findFirstByOrderByIdAsc();
	}

	Settings require() {
		return current().orElseThrow(() -> AccountingException.unprocessable("not-initialized",
				"Accounting has not been set up for this company yet"));
	}

}
```

`PeriodStatus.java`:

```java
package com.example.accounting;

public enum PeriodStatus {

	OPEN, CLOSED

}
```

`FiscalPeriod.java`:

```java
package com.example.accounting;

import java.time.Instant;
import java.time.LocalDate;
import java.time.YearMonth;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "fiscal_period", schema = "accounting")
public class FiscalPeriod {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "year", nullable = false)
	private int year;

	@Column(name = "month", nullable = false)
	private int month;

	@Column(name = "start_date", nullable = false)
	private LocalDate startDate;

	@Column(name = "end_date", nullable = false)
	private LocalDate endDate;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private PeriodStatus status = PeriodStatus.OPEN;

	@Column(name = "closed_by_sub", length = 100)
	private String closedBySub;

	@Column(name = "closed_by_name", length = 200)
	private String closedByName;

	@Column(name = "closed_at")
	private Instant closedAt;

	protected FiscalPeriod() {
	}

	FiscalPeriod(int year, int month) {
		this.year = year;
		this.month = month;
		YearMonth yearMonth = YearMonth.of(year, month);
		this.startDate = yearMonth.atDay(1);
		this.endDate = yearMonth.atEndOfMonth();
	}

	void close(Actor actor, Instant at) {
		this.status = PeriodStatus.CLOSED;
		this.closedBySub = actor.sub();
		this.closedByName = actor.name();
		this.closedAt = at;
	}

	void reopen() {
		this.status = PeriodStatus.OPEN;
		this.closedBySub = null;
		this.closedByName = null;
		this.closedAt = null;
	}

	public Long getId() {
		return id;
	}

	public int getYear() {
		return year;
	}

	public int getMonth() {
		return month;
	}

	public LocalDate getStartDate() {
		return startDate;
	}

	public LocalDate getEndDate() {
		return endDate;
	}

	public PeriodStatus getStatus() {
		return status;
	}

	public String getClosedByName() {
		return closedByName;
	}

	public Instant getClosedAt() {
		return closedAt;
	}

}
```

`FiscalPeriodRepository.java`:

```java
package com.example.accounting;

import java.util.List;
import java.util.Optional;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

interface FiscalPeriodRepository extends JpaRepository<FiscalPeriod, Long> {

	boolean existsByYear(int year);

	List<FiscalPeriod> findByYearOrderByMonth(int year);

	Optional<FiscalPeriod> findByYearAndMonth(int year, int month);

	/** Posting holds this until commit, so a close waits for it and sees the posted entry. */
	@Lock(LockModeType.PESSIMISTIC_READ)
	@Query("select p from FiscalPeriod p where p.year = :year and p.month = :month")
	Optional<FiscalPeriod> lockForPosting(@Param("year") int year, @Param("month") int month);

	/** Closing holds this until commit, so a racing post waits and then sees CLOSED. */
	@Lock(LockModeType.PESSIMISTIC_WRITE)
	@Query("select p from FiscalPeriod p where p.id = :id")
	Optional<FiscalPeriod> lockForClosing(@Param("id") long id);

	@Query(nativeQuery = true,
			value = "select count(*) from accounting.journal_entry where period_id = :id and status = 'DRAFT'")
	long countDrafts(@Param("id") long id);

}
```

- [ ] **Step 6: Write services, DTOs and controllers**

`PeriodService.java` (Task 4 extends it):

```java
package com.example.accounting;

import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PeriodService {

	private final FiscalPeriodRepository periods;

	PeriodService(FiscalPeriodRepository periods) {
		this.periods = periods;
	}

	public void openYear(int year) {
		periods.saveAll(IntStream.rangeClosed(1, 12).mapToObj(month -> new FiscalPeriod(year, month)).toList());
	}

}
```

`SettingsRequest.java`:

```java
package com.example.accounting;

import jakarta.validation.constraints.NotNull;

public record SettingsRequest(@NotNull Boolean requireSeparatePoster) {
}
```

`SettingsResponse.java`:

```java
package com.example.accounting;

import java.time.Instant;

public record SettingsResponse(boolean initialized, boolean requireSeparatePoster, String updatedByName,
		Instant updatedAt) {

	static final SettingsResponse NOT_INITIALIZED = new SettingsResponse(false, true, null, null);

	static SettingsResponse from(Settings settings) {
		return new SettingsResponse(true, settings.isRequireSeparatePoster(), settings.getUpdatedByName(),
				settings.getUpdatedAt());
	}

}
```

`SetupService.java`:

```java
package com.example.accounting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.HashMap;
import java.util.Map;

import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class SetupService {

	private final SettingsRepository settingsRepository;

	private final AccountingSettings settings;

	private final CoaTemplateRepository templates;

	private final AccountRepository accounts;

	private final PeriodService periods;

	private final AuditTrail audit;

	private final Clock clock;

	SetupService(SettingsRepository settingsRepository, AccountingSettings settings, CoaTemplateRepository templates,
			AccountRepository accounts, PeriodService periods, AuditTrail audit, Clock clock) {
		this.settingsRepository = settingsRepository;
		this.settings = settings;
		this.templates = templates;
		this.accounts = accounts;
		this.periods = periods;
		this.audit = audit;
		this.clock = clock;
	}

	public SettingsResponse setup() {
		if (settings.current().isPresent()) {
			throw alreadyInitialized();
		}
		Settings created;
		try {
			created = settingsRepository.saveAndFlush(new Settings(clock.instant()));
		}
		catch (DataIntegrityViolationException ex) {
			// Two admins clicked at once; UNIQUE (tenant_id) let one through.
			throw alreadyInitialized();
		}
		Map<String, Long> idsByCode = new HashMap<>();
		// Sorted by code, a parent always comes before its children.
		for (CoaTemplate template : templates.findAll(Sort.by("code"))) {
			Account account = accounts.save(new Account(template.getCode(), template.getName(), template.getType(),
					idsByCode.get(template.getParentCode()), template.isPostable(), true));
			idsByCode.put(template.getCode(), account.getId());
		}
		int year = LocalDate.now(clock).getYear();
		periods.openYear(year);
		audit.record(AuditAction.SETUP, "settings", created.getId(), Map.of("year", year));
		return SettingsResponse.from(created);
	}

	@Transactional(readOnly = true)
	public SettingsResponse settings() {
		return settings.current().map(SettingsResponse::from).orElse(SettingsResponse.NOT_INITIALIZED);
	}

	public SettingsResponse update(SettingsRequest request) {
		Settings current = settings.require();
		boolean old = current.isRequireSeparatePoster();
		current.requireSeparatePoster(request.requireSeparatePoster(), Actor.current(), clock.instant());
		audit.record(AuditAction.SETTING_CHANGE, "settings", current.getId(),
				Map.of("setting", "require_separate_poster", "old", old, "new", request.requireSeparatePoster()));
		return SettingsResponse.from(current);
	}

	private static AccountingException alreadyInitialized() {
		return AccountingException.conflict("already-initialized", "Accounting is already set up for this company");
	}

}
```

`SetupController.java`:

```java
package com.example.accounting;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting")
public class SetupController {

	private final SetupService service;

	public SetupController(SetupService service) {
		this.service = service;
	}

	@PostMapping("/setup")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public SettingsResponse setup() {
		return service.setup();
	}

	@GetMapping("/settings")
	@PreAuthorize("hasAuthority('accounting:read')")
	public SettingsResponse settings() {
		return service.settings();
	}

	@PutMapping("/settings")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public SettingsResponse update(@Valid @RequestBody SettingsRequest request) {
		return service.update(request);
	}

}
```

`AccountRequest.java`:

```java
package com.example.accounting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code active} defaults to true when left out. */
public record AccountRequest(
		@NotBlank @Size(max = 20) String code,
		@NotBlank @Size(max = 200) String name,
		@NotNull AccountType type,
		Long parentId,
		boolean postable,
		Boolean active) {

	boolean activeOrDefault() {
		return active == null || active;
	}

}
```

`AccountResponse.java`:

```java
package com.example.accounting;

public record AccountResponse(Long id, String code, String name, AccountType type, Long parentId, boolean postable,
		boolean active) {

	static AccountResponse from(Account account) {
		return new AccountResponse(account.getId(), account.getCode(), account.getName(), account.getType(),
				account.getParentId(), account.isPostable(), account.isActive());
	}

}
```

`AccountService.java`:

```java
package com.example.accounting;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class AccountService {

	private final AccountRepository accounts;

	private final AccountingSettings settings;

	AccountService(AccountRepository accounts, AccountingSettings settings) {
		this.accounts = accounts;
		this.settings = settings;
	}

	@Transactional(readOnly = true)
	public List<AccountResponse> list() {
		return accounts.findAll(Sort.by("code")).stream().map(AccountResponse::from).toList();
	}

	public AccountResponse create(AccountRequest request) {
		settings.require();
		checkCodeIsFree(request.code());
		checkParent(null, request.parentId());
		return AccountResponse.from(accounts.save(new Account(request.code(), request.name(), request.type(),
				request.parentId(), request.postable(), request.activeOrDefault())));
	}

	public AccountResponse update(long id, AccountRequest request) {
		settings.require();
		Account account = get(id);
		if (!account.getCode().equals(request.code())) {
			checkCodeIsFree(request.code());
		}
		checkParent(id, request.parentId());
		if (request.postable() && accounts.existsByParentId(id)) {
			throw AccountingException.badRequest("validation", "An account with children cannot be postable");
		}
		boolean structureChanges = account.getType() != request.type() || account.isPostable() != request.postable();
		if (structureChanges && accounts.hasLines(id)) {
			throw AccountingException.conflict("account-in-use",
					"The type and postability of an account with journal lines cannot change");
		}
		account.update(request.code(), request.name(), request.type(), request.parentId(), request.postable(),
				request.activeOrDefault());
		return AccountResponse.from(account);
	}

	public void delete(long id) {
		Account account = get(id);
		if (accounts.hasLines(id) || accounts.existsByParentId(id)) {
			throw AccountingException.conflict("account-in-use",
					"An account with journal lines or child accounts cannot be deleted");
		}
		accounts.delete(account);
	}

	private Account get(long id) {
		return accounts.findById(id).orElseThrow(() -> new AccountingNotFoundException("Account " + id));
	}

	private void checkCodeIsFree(String code) {
		if (accounts.existsByCode(code)) {
			throw AccountingException.conflict("account-code-exists", "Account code " + code + " already exists");
		}
	}

	/** The parent must exist, be a non-postable header, and not be the account itself or one of its descendants. */
	private void checkParent(Long id, Long parentId) {
		Long current = parentId;
		boolean first = true;
		while (current != null) {
			if (current.equals(id)) {
				throw AccountingException.badRequest("validation", "An account cannot be its own ancestor");
			}
			Long lookedUp = current;
			Account parent = accounts.findById(lookedUp).orElseThrow(
					() -> AccountingException.badRequest("validation", "Parent account " + lookedUp + " does not exist"));
			if (first && parent.isPostable()) {
				throw AccountingException.badRequest("validation", "A parent account cannot be postable");
			}
			first = false;
			current = parent.getParentId();
		}
	}

}
```

`AccountController.java`:

```java
package com.example.accounting;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/accounts")
public class AccountController {

	private final AccountService service;

	public AccountController(AccountService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('accounting:read')")
	public List<AccountResponse> list() {
		return service.list();
	}

	@PostMapping
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public ResponseEntity<AccountResponse> create(@Valid @RequestBody AccountRequest request) {
		AccountResponse created = service.create(request);
		return ResponseEntity.created(URI.create("/api/accounting/accounts/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public AccountResponse update(@PathVariable long id, @Valid @RequestBody AccountRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:account-manage')")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: BUILD SUCCESS, including `ddl-auto=validate` accepting every entity. Then `./mvnw -q -pl backend/task,backend/iam -am test`: the handler changes add a property only.

- [ ] **Step 8: Commit**

```bash
git add backend/accounting backend/common/src/main/java/com/example/common/GlobalExceptionHandler.java backend/security/src/main/java/com/example/security/AccessDeniedProblemHandler.java
git commit -m "Add accounting setup, settings and chart of accounts with stable error codes

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Fiscal periods

**Files:**
- Modify: `backend/accounting/src/main/java/com/example/accounting/PeriodService.java`
- Create: `PeriodResponse.java`, `ReopenRequest.java`, `PeriodController.java` in the same package
- Test: `backend/accounting/src/test/java/com/example/accounting/PeriodTests.java`

**Interfaces:**
- Consumes: `FiscalPeriodRepository`, `AccountingSettings.require()`, `AuditTrail.record`, `Actor.current()`, test helper `insertEntry(long periodId, String status)`.
- Produces: `PeriodService.list(int year)`, `openYear` with checks via `openYearChecked(int year)`, `close(long id)`, `reopen(long id, String reason)`; `PeriodResponse.from(FiscalPeriod)`.

- [ ] **Step 1: Write the failing tests**

`PeriodTests.java`:

```java
package com.example.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PeriodTests extends AccountingTestSupport {

	@BeforeEach
	void setup() throws Exception {
		mvc.perform(post("/api/accounting/setup").with(admin())).andExpect(status().isOk());
	}

	private long periodId(int month) throws Exception {
		String body = mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(admin()))
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$[" + (month - 1) + "].id")).longValue();
	}

	@Test
	void periodsAreListedByYear() throws Exception {
		mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(12)))
				.andExpect(jsonPath("$[1].month").value(2))
				.andExpect(jsonPath("$[1].startDate").value(YEAR + "-02-01"))
				.andExpect(jsonPath("$[1].status").value("OPEN"));
		mvc.perform(get("/api/accounting/periods").param("year", "1999").with(as("u", READ)))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/accounting/periods").with(as("u", READ))).andExpect(problem(400, "validation"));
	}

	@Test
	void aNewYearCanBeOpenedOnce() throws Exception {
		int next = YEAR + 1;
		mvc.perform(post("/api/accounting/periods/year/" + next).with(as("u", PERIOD_MANAGE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(12)));
		mvc.perform(post("/api/accounting/periods/year/" + next).with(as("u", PERIOD_MANAGE)))
				.andExpect(problem(409, "periods-exist"));
		mvc.perform(post("/api/accounting/periods/year/1800").with(as("u", PERIOD_MANAGE)))
				.andExpect(problem(400, "validation"));
		mvc.perform(post("/api/accounting/periods/year/" + (next + 1)).with(as("u", READ)))
				.andExpect(problem(403, "forbidden"));
	}

	@Test
	void closeAndReopenAreAudited() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("closer", PERIOD_MANAGE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLOSED"))
				.andExpect(jsonPath("$.closedByName").value("closer-name"));
		mvc.perform(post("/api/accounting/periods/" + id + "/reopen").with(as("closer", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"late invoice\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.closedByName").isEmpty());

		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'PERIOD_REOPEN'").getSingleResult());
		assertThat(details).contains("late invoice");
		Number closes = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'PERIOD_CLOSE'").getSingleResult());
		assertThat(closes.longValue()).isEqualTo(1);
	}

	@Test
	void reopenNeedsAReason() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE)));
		mvc.perform(post("/api/accounting/periods/" + id + "/reopen").with(as("u", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"  \"}"))
				.andExpect(problem(400, "validation"));
	}

	@Test
	void repeatingACloseOrReopenChangesNothing() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/reopen").with(as("u", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE)));
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("v", PERIOD_MANAGE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closedByName").value("u-name"));
		Number audits = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action in ('PERIOD_CLOSE', 'PERIOD_REOPEN')")
				.getSingleResult());
		assertThat(audits.longValue()).isEqualTo(1);
	}

	@Test
	void closeIsRefusedWhileDraftsExist() throws Exception {
		long id = periodId(2);
		inTransaction(() -> insertEntry(id, "DRAFT"));
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE)))
				.andExpect(problem(409, "period-has-drafts"));
	}

	@Test
	void anotherTenantsPeriodLooksMissing() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/close")
						.with(asTenant(java.util.UUID.randomUUID(), "o", PERIOD_MANAGE)))
				.andExpect(problem(404, "not-found"));
	}

}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/accounting -am test -Dtest=PeriodTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: no `/api/accounting/periods` endpoint.

- [ ] **Step 3: Write the implementation**

`PeriodResponse.java`:

```java
package com.example.accounting;

import java.time.Instant;
import java.time.LocalDate;

public record PeriodResponse(Long id, int year, int month, LocalDate startDate, LocalDate endDate, PeriodStatus status,
		String closedByName, Instant closedAt) {

	static PeriodResponse from(FiscalPeriod period) {
		return new PeriodResponse(period.getId(), period.getYear(), period.getMonth(), period.getStartDate(),
				period.getEndDate(), period.getStatus(), period.getClosedByName(), period.getClosedAt());
	}

}
```

`ReopenRequest.java`:

```java
package com.example.accounting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record ReopenRequest(@NotBlank @Size(max = 500) String reason) {
}
```

Replace `PeriodService.java` with:

```java
package com.example.accounting;

import java.time.Clock;
import java.util.List;
import java.util.Map;
import java.util.stream.IntStream;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PeriodService {

	private final FiscalPeriodRepository periods;

	private final AccountingSettings settings;

	private final AuditTrail audit;

	private final Clock clock;

	PeriodService(FiscalPeriodRepository periods, AccountingSettings settings, AuditTrail audit, Clock clock) {
		this.periods = periods;
		this.settings = settings;
		this.audit = audit;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public List<PeriodResponse> list(int year) {
		return periods.findByYearOrderByMonth(year).stream().map(PeriodResponse::from).toList();
	}

	public List<PeriodResponse> openYearChecked(int year) {
		settings.require();
		if (year < 2000 || year > 2100) {
			throw AccountingException.badRequest("validation", "Year must be between 2000 and 2100");
		}
		if (periods.existsByYear(year)) {
			throw AccountingException.conflict("periods-exist", "The periods of " + year + " already exist");
		}
		openYear(year);
		return list(year);
	}

	public void openYear(int year) {
		periods.saveAll(IntStream.rangeClosed(1, 12).mapToObj(month -> new FiscalPeriod(year, month)).toList());
	}

	public PeriodResponse close(long id) {
		FiscalPeriod period = periods.lockForClosing(id).orElseThrow(() -> notFound(id));
		if (period.getStatus() == PeriodStatus.CLOSED) {
			return PeriodResponse.from(period);
		}
		long drafts = periods.countDrafts(id);
		if (drafts > 0) {
			throw AccountingException.conflict("period-has-drafts",
					"Post or delete the " + drafts + " draft entries dated in this period first");
		}
		period.close(Actor.current(), clock.instant());
		audit.record(AuditAction.PERIOD_CLOSE, "fiscal_period", id,
				Map.of("year", period.getYear(), "month", period.getMonth()));
		return PeriodResponse.from(period);
	}

	public PeriodResponse reopen(long id, String reason) {
		FiscalPeriod period = periods.lockForClosing(id).orElseThrow(() -> notFound(id));
		if (period.getStatus() == PeriodStatus.OPEN) {
			return PeriodResponse.from(period);
		}
		period.reopen();
		audit.record(AuditAction.PERIOD_REOPEN, "fiscal_period", id,
				Map.of("year", period.getYear(), "month", period.getMonth(), "reason", reason));
		return PeriodResponse.from(period);
	}

	private static AccountingNotFoundException notFound(long id) {
		return new AccountingNotFoundException("Period " + id);
	}

}
```

`PeriodController.java`:

```java
package com.example.accounting;

import java.util.List;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/periods")
public class PeriodController {

	private final PeriodService service;

	public PeriodController(PeriodService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('accounting:read')")
	public List<PeriodResponse> list(@RequestParam int year) {
		return service.list(year);
	}

	@PostMapping("/year/{year}")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public List<PeriodResponse> openYear(@PathVariable int year) {
		return service.openYearChecked(year);
	}

	@PostMapping("/{id}/close")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public PeriodResponse close(@PathVariable long id) {
		return service.close(id);
	}

	@PostMapping("/{id}/reopen")
	@PreAuthorize("hasAuthority('accounting:period-manage')")
	public PeriodResponse reopen(@PathVariable long id, @Valid @RequestBody ReopenRequest request) {
		return service.reopen(id, request.reason());
	}

}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting
git commit -m "Add fiscal periods: list, open a year, close and reopen with audit

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Journal drafts

**Files:**
- Create in `backend/accounting/src/main/java/com/example/accounting/`: `Side.java`, `JournalRules.java`, `EntryStatus.java`, `JournalEntry.java`, `JournalLine.java`, `JournalEntryRepository.java`, `LineRequest.java`, `EntryRequest.java`, `LineResponse.java`, `EntryResponse.java`, `EntrySummary.java`, `EntryPage.java`, `JournalService.java`, `JournalController.java`
- Modify: `AccountingTestSupport.java` (journal helpers)
- Test: `backend/accounting/src/test/java/com/example/accounting/JournalRulesTests.java`, `JournalDraftTests.java`

**Interfaces:**
- Consumes: `AccountRepository`, `FiscalPeriodRepository`, `AccountingSettings.require()`, `Actor.current()`, `AccountingException`, `AccountingNotFoundException`.
- Produces:
  - `record Side(BigDecimal debit, BigDecimal credit)` with `Side swapped()`.
  - `JournalRules`: `MAX_LINES = 500`, `BigDecimal amount(BigDecimal)`, `void checkLine(Side)`, `void checkBalanced(List<Side>)`, `boolean mayPost(String posterSub, String createdBySub, String lastModifiedBySub, boolean requireSeparatePoster)`.
  - `EntryStatus` values `DRAFT, POSTED, REVERSED`.
  - `JournalEntry` with `getId, getEntryNo, getEntryDate, getPeriodId, getStatus, getCreatedBySub, getLastModifiedBySub, getLines, getVersion`, plus `markPosted(String entryNo, Actor, Instant)`, `markReversed(Long reversedBy)`, `reversalOf(Long)`; constructor `JournalEntry(LocalDate entryDate, Long periodId, String reference, String description, Actor actor, Instant at)`; `JournalLine(JournalEntry entry, int lineNo, Long accountId, BigDecimal debit, BigDecimal credit, String memo)` with `getLineNo, getAccountId, getDebit, getCredit, getMemo, side()`.
  - `JournalEntryRepository`; `JournalService.get(long)` (public, returns `EntryResponse`), `JournalService.load(long)` (returns `JournalEntry` or 404), `JournalService.checkAccountsUsable(Collection<Long>)`, both public because transactional beans are proxied and a package-private method could run on the proxy instance.
  - `EntryResponse.from(JournalEntry)`.
  - Test helpers `long account(String code)`, `static String line(long, String, String)`, `static String entry(String, String...)`, `long draft(String sub, String date, String... lines)`, `long balancedDraft(String sub, String date)`, `void setupAccounting()`.

- [ ] **Step 1: Write the failing rule tests**

`JournalRulesTests.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class JournalRulesTests {

	private static Side side(String debit, String credit) {
		return new Side(new BigDecimal(debit), new BigDecimal(credit));
	}

	private static String codeOf(Runnable rule) {
		try {
			rule.run();
			return null;
		}
		catch (AccountingException ex) {
			return ex.code();
		}
	}

	@Test
	void amountsAreNormalizedToTwoDecimals() {
		assertThat(JournalRules.amount(new BigDecimal("1.5"))).isEqualByComparingTo("1.50").hasScaleOf(2);
		assertThat(JournalRules.amount(new BigDecimal("1.500"))).hasToString("1.50");
		assertThat(JournalRules.amount(new BigDecimal("1E+3"))).hasToString("1000.00");
		assertThat(JournalRules.amount(null)).hasToString("0.00");
	}

	@Test
	void moreThanTwoDecimalsIsNeverRoundedSilently() {
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("1.555")))).isEqualTo("invalid-amount");
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("0.001")))).isEqualTo("invalid-amount");
	}

	@Test
	void amountsThatDoNotFitAreRefusedWithoutExpandingThem() {
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("1E+1000000000")))).isEqualTo("invalid-amount");
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("1E-1000000000")))).isEqualTo("invalid-amount");
		assertThat(codeOf(() -> JournalRules.amount(new BigDecimal("123456789012345678")))).isEqualTo("invalid-amount");
		assertThat(JournalRules.amount(new BigDecimal("12345678901234567.89"))).hasToString("12345678901234567.89");
	}

	@Test
	void aLineHasExactlyOnePositiveSide() {
		assertThat(codeOf(() -> JournalRules.checkLine(side("0", "0")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("1", "1")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("-1", "0")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("0", "-1")))).isEqualTo("invalid-line");
		assertThat(codeOf(() -> JournalRules.checkLine(side("1", "0")))).isNull();
		assertThat(codeOf(() -> JournalRules.checkLine(side("0", "1")))).isNull();
	}

	@Test
	void balanceNeedsTwoLinesAndEqualPositiveSides() {
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("1", "0"))))).isEqualTo("too-few-lines");
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("2", "0"), side("0", "1")))))
				.isEqualTo("unbalanced");
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("1.50", "0"), side("0", "1.5"))))).isNull();
		assertThat(codeOf(() -> JournalRules.checkBalanced(List.of(side("1", "0"), side("1", "0"), side("0", "2")))))
				.isNull();
	}

	@Test
	void reversalSwapsSides() {
		assertThat(side("5", "0").swapped()).isEqualTo(side("0", "5"));
	}

	@Test
	void makerCheckerMatrix() {
		assertThat(JournalRules.mayPost("poster", "maker", "editor", true)).isTrue();
		assertThat(JournalRules.mayPost("maker", "maker", "editor", true)).isFalse();
		assertThat(JournalRules.mayPost("editor", "maker", "editor", true)).isFalse();
		assertThat(JournalRules.mayPost("maker", "maker", "maker", false)).isTrue();
	}

}
```

Run: `./mvnw -q -pl backend/accounting -am test -Dtest=JournalRulesTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile error, `cannot find symbol: class Side`.

- [ ] **Step 2: Write the rules**

`Side.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;

record Side(BigDecimal debit, BigDecimal credit) {

	Side swapped() {
		return new Side(credit, debit);
	}

}
```

`JournalRules.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.util.List;

/** The bookkeeping rules that need no database, so they can be tested alone. */
final class JournalRules {

	static final int MAX_LINES = 500;

	private static final int MAX_INTEGER_DIGITS = 17;

	private JournalRules() {
	}

	/** Normalizes to two decimals; refuses anything that would need rounding or does not fit NUMERIC(19,2). */
	static BigDecimal amount(BigDecimal value) {
		if (value == null) {
			return BigDecimal.ZERO.setScale(2);
		}
		// Checked before setScale: an exponent like 1E+1000000000 would otherwise expand into a billion digits.
		BigDecimal stripped = value.stripTrailingZeros();
		if (stripped.scale() > 2 || (long) stripped.precision() - stripped.scale() > MAX_INTEGER_DIGITS) {
			throw AccountingException.badRequest("invalid-amount", "Amount " + value.toEngineeringString()
					+ " must have at most " + MAX_INTEGER_DIGITS + " integer digits and 2 decimals");
		}
		return stripped.setScale(2);
	}

	static void checkLine(Side side) {
		boolean debit = side.debit().signum() > 0;
		boolean credit = side.credit().signum() > 0;
		if (debit == credit || side.debit().signum() < 0 || side.credit().signum() < 0) {
			throw AccountingException.badRequest("invalid-line",
					"Every line needs exactly one side greater than zero");
		}
	}

	static void checkBalanced(List<Side> sides) {
		if (sides.size() < 2) {
			throw AccountingException.unprocessable("too-few-lines", "An entry needs at least two lines to be posted");
		}
		BigDecimal debit = sides.stream().map(Side::debit).reduce(BigDecimal.ZERO, BigDecimal::add);
		BigDecimal credit = sides.stream().map(Side::credit).reduce(BigDecimal.ZERO, BigDecimal::add);
		if (debit.compareTo(credit) != 0 || debit.signum() <= 0) {
			throw AccountingException.unprocessable("unbalanced",
					"Debit " + debit.toPlainString() + " does not equal credit " + credit.toPlainString());
		}
	}

	static boolean mayPost(String posterSub, String createdBySub, String lastModifiedBySub,
			boolean requireSeparatePoster) {
		return !requireSeparatePoster || (!posterSub.equals(createdBySub) && !posterSub.equals(lastModifiedBySub));
	}

}
```

Run the same command. Expected: PASS. Note `new BigDecimal("1E+1000000000").toEngineeringString()` is short, so the error message stays small.

- [ ] **Step 3: Add the journal test helpers**

Add to `AccountingTestSupport`, with imports `com.jayway.jsonpath.JsonPath`, `org.springframework.http.MediaType`, and static imports of `MockMvcRequestBuilders.get` and `post`:

```java
	void setupAccounting() throws Exception {
		mvc.perform(post("/api/accounting/setup").with(admin())).andExpect(status().isOk());
	}

	long account(String code) throws Exception {
		String body = mvc.perform(get("/api/accounting/accounts").with(admin())).andReturn().getResponse().getContentAsString();
		List<Number> ids = JsonPath.read(body, "$[?(@.code=='" + code + "')].id");
		return ids.get(0).longValue();
	}

	static String line(long accountId, String debit, String credit) {
		return """
				{"accountId": %d, "debit": "%s", "credit": "%s"}""".formatted(accountId, debit, credit);
	}

	static String entry(String date, String... lines) {
		return """
				{"date": "%s", "reference": "REF", "description": "Test entry", "lines": [%s]}
				""".formatted(date, String.join(",", lines));
	}

	long draft(String sub, String date, String... lines) throws Exception {
		String body = mvc.perform(post("/api/accounting/journal-entries").with(as(sub, READ, WRITE))
						.contentType(MediaType.APPLICATION_JSON).content(entry(date, lines)))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.id")).longValue();
	}

	/** A draft of 100.00 from cash to sales. */
	long balancedDraft(String sub, String date) throws Exception {
		return draft(sub, date, line(account("1110"), "100.00", "0"), line(account("4100"), "0", "100.00"));
	}
```

- [ ] **Step 4: Write the failing API tests**

`JournalDraftTests.java`:

```java
package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JournalDraftTests extends AccountingTestSupport {

	private long cash;

	private long sales;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
	}

	private ResultActions create(String json) throws Exception {
		return mvc.perform(post("/api/accounting/journal-entries").with(as("maker", READ, WRITE))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions update(long id, long version, String date, String... lines) throws Exception {
		String json = entry(date, lines).replaceFirst("\\{", "{\"version\": " + version + ", ");
		return mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("editor", READ, WRITE))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long versionOf(long id) throws Exception {
		String body = mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.version")).longValue();
	}

	@Test
	void aDraftIsCreatedWithStringAmountsAndNoNumber() throws Exception {
		create(entry(DATE, line(cash, "1665000", "0"), line(sales, "0", "1665000.00")))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", startsWith("/api/accounting/journal-entries/")))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.entryNo").isEmpty())
				.andExpect(jsonPath("$.date").value(DATE))
				.andExpect(jsonPath("$.createdByName").value("maker-name"))
				.andExpect(jsonPath("$.lines", hasSize(2)))
				.andExpect(jsonPath("$.lines[0].lineNo").value(1))
				.andExpect(jsonPath("$.lines[0].debit").value("1665000.00"))
				.andExpect(jsonPath("$.lines[0].credit").value("0.00"))
				.andExpect(jsonPath("$.totalDebit").value("1665000.00"));
	}

	@Test
	void aDraftMayBeUnbalancedOrEmpty() throws Exception {
		create(entry(DATE, line(cash, "1.00", "0"))).andExpect(status().isCreated());
		create(entry(DATE)).andExpect(status().isCreated());
	}

	@Test
	void linesAreChecked() throws Exception {
		create(entry(DATE, line(cash, "0", "0"))).andExpect(problem(400, "invalid-line"));
		create(entry(DATE, line(cash, "1", "1"))).andExpect(problem(400, "invalid-line"));
		create(entry(DATE, line(cash, "-1", "0"))).andExpect(problem(400, "invalid-line"));
		String[] tooMany = new String[501];
		java.util.Arrays.fill(tooMany, line(cash, "1", "0"));
		create(entry(DATE, tooMany)).andExpect(problem(400, "too-many-lines"));
	}

	@Test
	void oddAmountsAreRefusedNotRounded() throws Exception {
		create(entry(DATE, line(cash, "1.555", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "abc", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "1E+1000000000", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "123456789012345678", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "1.500", "0")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.lines[0].debit").value("1.50"));
	}

	@Test
	void linesNeedAUsableAccountOfThisTenant() throws Exception {
		long header = account("1100");
		create(entry(DATE, line(header, "1", "0"))).andExpect(problem(422, "account-not-postable"));

		mvc.perform(put("/api/accounting/accounts/" + sales).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
				{"code": "4100", "name": "Penjualan", "type": "REVENUE", "parentId": %d, "postable": true, "active": false}
				""".formatted(account("4000")))).andExpect(status().isOk());
		create(entry(DATE, line(sales, "1", "0"))).andExpect(problem(422, "account-not-postable"));

		create(entry(DATE, line(999999999L, "1", "0"))).andExpect(problem(422, "account-not-postable"));
		UUID other = UUID.randomUUID();
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
		String body = mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ))).andReturn().getResponse().getContentAsString();
		java.util.List<Number> otherCash = JsonPath.read(body, "$[?(@.code=='1110')].id");
		create(entry(DATE, line(otherCash.get(0).longValue(), "1", "0"))).andExpect(problem(422, "account-not-postable"));
	}

	@Test
	void theDateMustFallIntoAPeriod() throws Exception {
		create(entry("1999-01-01", line(cash, "1", "0"))).andExpect(problem(422, "no-period"));
		create("{\"description\": \"no date\", \"lines\": []}").andExpect(problem(400, "validation"));
		create("{\"date\": \"" + DATE + "\", \"description\": \" \", \"lines\": []}").andExpect(problem(400, "validation"));
	}

	@Test
	void writesBeforeSetupAreRefused() throws Exception {
		UUID fresh = UUID.randomUUID();
		mvc.perform(post("/api/accounting/journal-entries").with(asTenant(fresh, "u", WRITE))
						.contentType(MediaType.APPLICATION_JSON).content(entry(DATE)))
				.andExpect(problem(422, "not-initialized"));
	}

	@Test
	void anEditReplacesTheLinesAndRecordsTheEditor() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		update(id, versionOf(id), YEAR + "-04-02", line(sales, "0", "7"), line(cash, "7", "0"), line(cash, "2", "0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.date").value(YEAR + "-04-02"))
				.andExpect(jsonPath("$.createdByName").value("maker-name"))
				.andExpect(jsonPath("$.lastModifiedByName").value("editor-name"))
				.andExpect(jsonPath("$.lines", hasSize(3)))
				.andExpect(jsonPath("$.lines[0].accountId").value((int) sales))
				.andExpect(jsonPath("$.lines[2].lineNo").value(3));
	}

	@Test
	void aStaleVersionIsAConflict() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"));
		long version = versionOf(id);
		update(id, version, DATE, line(cash, "2", "0")).andExpect(status().isOk());
		update(id, version, DATE, line(cash, "3", "0")).andExpect(problem(409, "version-conflict"));
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("editor", WRITE))
				.contentType(MediaType.APPLICATION_JSON).content(entry(DATE))).andExpect(problem(400, "validation"));
	}

	@Test
	void aDraftCanBeDeleted() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"));
		mvc.perform(delete("/api/accounting/journal-entries/" + id).with(as("u", WRITE))).andExpect(status().isNoContent());
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andExpect(problem(404, "not-found"));
	}

	@Test
	void writesNeedTheWritePermission() throws Exception {
		mvc.perform(post("/api/accounting/journal-entries").with(as("u", READ))
				.contentType(MediaType.APPLICATION_JSON).content(entry(DATE))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void anotherTenantsEntriesLookMissing() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"));
		UUID other = UUID.randomUUID();
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(asTenant(other, "o", READ)))
				.andExpect(problem(404, "not-found"));
		mvc.perform(delete("/api/accounting/journal-entries/" + id).with(asTenant(other, "o", WRITE)))
				.andExpect(problem(404, "not-found"));
		mvc.perform(get("/api/accounting/journal-entries").with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void theListFiltersAndPages() throws Exception {
		draft("maker", YEAR + "-01-10", line(cash, "1", "0"));
		long second = draft("maker", YEAR + "-02-10", line(sales, "0", "1"));
		long third = draft("maker", YEAR + "-03-10", line(cash, "1", "0"), line(sales, "0", "1"));

		mvc.perform(get("/api/accounting/journal-entries").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.size").value(50))
				.andExpect(jsonPath("$.items[0].id").value((int) third))
				.andExpect(jsonPath("$.items[0].status").value("DRAFT"));
		mvc.perform(get("/api/accounting/journal-entries").param("from", YEAR + "-02-01").param("to", YEAR + "-02-28").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].id").value((int) second));
		mvc.perform(get("/api/accounting/journal-entries").param("accountId", String.valueOf(cash)).with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(2));
		mvc.perform(get("/api/accounting/journal-entries").param("status", "POSTED").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/accounting/journal-entries").param("size", "1").param("page", "1").with(as("u", READ)))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.total").value(3));
		mvc.perform(get("/api/accounting/journal-entries").param("q", "test ENTRY").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(3));
		mvc.perform(get("/api/accounting/journal-entries").param("q", "%").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/accounting/journal-entries").param("q", "t_st").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void pagingValuesOutsideTheLimitsAreRefused() throws Exception {
		mvc.perform(get("/api/accounting/journal-entries").param("size", "0").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("size", "201").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("page", "-1").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("status", "NOPE").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("from", "nope").with(as("u", READ))).andExpect(problem(400, "validation"));
	}

}
```

Run: `./mvnw -q -pl backend/accounting -am test -Dtest=JournalDraftTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: no `/api/accounting/journal-entries` endpoint.

- [ ] **Step 5: Write the entities and repository**

`EntryStatus.java`:

```java
package com.example.accounting;

public enum EntryStatus {

	DRAFT, POSTED, REVERSED

}
```

`JournalLine.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "journal_line", schema = "accounting")
class JournalLine {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@ManyToOne(fetch = FetchType.LAZY, optional = false)
	@JoinColumn(name = "entry_id", nullable = false, updatable = false)
	private JournalEntry entry;

	@Column(name = "line_no", nullable = false)
	private int lineNo;

	@Column(name = "account_id", nullable = false)
	private Long accountId;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal debit;

	@Column(nullable = false, precision = 19, scale = 2)
	private BigDecimal credit;

	@Column(length = 200)
	private String memo;

	protected JournalLine() {
	}

	JournalLine(JournalEntry entry, int lineNo, Long accountId, BigDecimal debit, BigDecimal credit, String memo) {
		this.entry = entry;
		this.lineNo = lineNo;
		this.accountId = accountId;
		this.debit = debit;
		this.credit = credit;
		this.memo = memo;
	}

	Side side() {
		return new Side(debit, credit);
	}

	int getLineNo() {
		return lineNo;
	}

	Long getAccountId() {
		return accountId;
	}

	BigDecimal getDebit() {
		return debit;
	}

	BigDecimal getCredit() {
		return credit;
	}

	String getMemo() {
		return memo;
	}

}
```

`JournalEntry.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.GeneratedValue;
import jakarta.persistence.GenerationType;
import jakarta.persistence.Id;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;
import jakarta.persistence.Version;

import org.hibernate.annotations.BatchSize;
import org.hibernate.annotations.TenantId;

@Entity
@Table(name = "journal_entry", schema = "accounting")
public class JournalEntry {

	@Id
	@GeneratedValue(strategy = GenerationType.IDENTITY)
	private Long id;

	@TenantId
	@Column(name = "tenant_id", nullable = false, updatable = false)
	private UUID tenantId;

	@Column(name = "entry_no", length = 20)
	private String entryNo;

	@Column(name = "entry_date", nullable = false)
	private LocalDate entryDate;

	@Column(name = "period_id", nullable = false)
	private Long periodId;

	@Column(length = 100)
	private String reference;

	@Column(nullable = false, length = 500)
	private String description;

	@Enumerated(EnumType.STRING)
	@Column(nullable = false, length = 10)
	private EntryStatus status = EntryStatus.DRAFT;

	@Column(name = "reversal_of")
	private Long reversalOf;

	@Column(name = "reversed_by")
	private Long reversedBy;

	@Column(name = "created_by_sub", nullable = false, length = 100, updatable = false)
	private String createdBySub;

	@Column(name = "created_by_name", nullable = false, length = 200, updatable = false)
	private String createdByName;

	@Column(name = "created_at", nullable = false, updatable = false)
	private Instant createdAt;

	@Column(name = "last_modified_by_sub", nullable = false, length = 100)
	private String lastModifiedBySub;

	@Column(name = "last_modified_by_name", nullable = false, length = 200)
	private String lastModifiedByName;

	@Column(name = "last_modified_at", nullable = false)
	private Instant lastModifiedAt;

	@Column(name = "posted_by_sub", length = 100)
	private String postedBySub;

	@Column(name = "posted_by_name", length = 200)
	private String postedByName;

	@Column(name = "posted_at")
	private Instant postedAt;

	@Version
	private Long version;

	@OneToMany(mappedBy = "entry", cascade = CascadeType.ALL, orphanRemoval = true)
	@OrderBy("lineNo")
	@BatchSize(size = 200)
	private List<JournalLine> lines = new ArrayList<>();

	protected JournalEntry() {
	}

	JournalEntry(LocalDate entryDate, Long periodId, String reference, String description, Actor actor, Instant at) {
		this.createdBySub = actor.sub();
		this.createdByName = actor.name();
		this.createdAt = at;
		edit(entryDate, periodId, reference, description, actor, at);
	}

	void edit(LocalDate entryDate, Long periodId, String reference, String description, Actor actor, Instant at) {
		this.entryDate = entryDate;
		this.periodId = periodId;
		this.reference = reference;
		this.description = description;
		this.lastModifiedBySub = actor.sub();
		this.lastModifiedByName = actor.name();
		this.lastModifiedAt = at;
	}

	void addLine(Long accountId, Side side, String memo) {
		lines.add(new JournalLine(this, lines.size() + 1, accountId, side.debit(), side.credit(), memo));
	}

	void reversalOf(Long originalId) {
		this.reversalOf = originalId;
	}

	void markPosted(String entryNo, Actor actor, Instant at) {
		this.entryNo = entryNo;
		this.status = EntryStatus.POSTED;
		this.postedBySub = actor.sub();
		this.postedByName = actor.name();
		this.postedAt = at;
	}

	void markReversed(Long reversedBy) {
		this.status = EntryStatus.REVERSED;
		this.reversedBy = reversedBy;
	}

	BigDecimal totalDebit() {
		return lines.stream().map(JournalLine::getDebit).reduce(BigDecimal.ZERO.setScale(2), BigDecimal::add);
	}

	List<Side> sides() {
		return lines.stream().map(JournalLine::side).toList();
	}

	public Long getId() {
		return id;
	}

	public String getEntryNo() {
		return entryNo;
	}

	public LocalDate getEntryDate() {
		return entryDate;
	}

	public Long getPeriodId() {
		return periodId;
	}

	public String getReference() {
		return reference;
	}

	public String getDescription() {
		return description;
	}

	public EntryStatus getStatus() {
		return status;
	}

	public Long getReversalOf() {
		return reversalOf;
	}

	public Long getReversedBy() {
		return reversedBy;
	}

	public String getCreatedBySub() {
		return createdBySub;
	}

	public String getCreatedByName() {
		return createdByName;
	}

	public Instant getCreatedAt() {
		return createdAt;
	}

	public String getLastModifiedBySub() {
		return lastModifiedBySub;
	}

	public String getLastModifiedByName() {
		return lastModifiedByName;
	}

	public Instant getLastModifiedAt() {
		return lastModifiedAt;
	}

	public String getPostedByName() {
		return postedByName;
	}

	public Instant getPostedAt() {
		return postedAt;
	}

	public Long getVersion() {
		return version;
	}

	List<JournalLine> getLines() {
		return lines;
	}

}
```

`JournalEntryRepository.java`:

```java
package com.example.accounting;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.JpaSpecificationExecutor;

interface JournalEntryRepository extends JpaRepository<JournalEntry, Long>, JpaSpecificationExecutor<JournalEntry> {
}
```

- [ ] **Step 6: Write the DTOs, service and controller**

`LineRequest.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;

import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

public record LineRequest(@NotNull Long accountId, BigDecimal debit, BigDecimal credit, @Size(max = 200) String memo) {
}
```

`EntryRequest.java`:

```java
package com.example.accounting;

import java.time.LocalDate;
import java.util.List;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code version} is required when updating, ignored when creating. */
public record EntryRequest(
		@NotNull LocalDate date,
		@Size(max = 100) String reference,
		@NotBlank @Size(max = 500) String description,
		@NotNull List<@Valid LineRequest> lines,
		Long version) {
}
```

`LineResponse.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

public record LineResponse(int lineNo, Long accountId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
		String memo) {

	static LineResponse from(JournalLine line) {
		return new LineResponse(line.getLineNo(), line.getAccountId(), line.getDebit(), line.getCredit(), line.getMemo());
	}

}
```

`EntryResponse.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record EntryResponse(Long id, String entryNo, LocalDate date, Long periodId, String reference,
		String description, EntryStatus status, Long reversalOf, Long reversedBy, String createdByName,
		Instant createdAt, String lastModifiedByName, Instant lastModifiedAt, String postedByName, Instant postedAt,
		Long version, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebit, List<LineResponse> lines) {

	static EntryResponse from(JournalEntry entry) {
		return new EntryResponse(entry.getId(), entry.getEntryNo(), entry.getEntryDate(), entry.getPeriodId(),
				entry.getReference(), entry.getDescription(), entry.getStatus(), entry.getReversalOf(),
				entry.getReversedBy(), entry.getCreatedByName(), entry.getCreatedAt(), entry.getLastModifiedByName(),
				entry.getLastModifiedAt(), entry.getPostedByName(), entry.getPostedAt(), entry.getVersion(),
				entry.totalDebit(), entry.getLines().stream().map(LineResponse::from).toList());
	}

}
```

`EntrySummary.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;

import com.fasterxml.jackson.annotation.JsonFormat;

public record EntrySummary(Long id, String entryNo, LocalDate date, String reference, String description,
		EntryStatus status, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebit) {

	static EntrySummary from(JournalEntry entry) {
		return new EntrySummary(entry.getId(), entry.getEntryNo(), entry.getEntryDate(), entry.getReference(),
				entry.getDescription(), entry.getStatus(), entry.totalDebit());
	}

}
```

`EntryPage.java`:

```java
package com.example.accounting;

import java.util.List;

public record EntryPage(List<EntrySummary> items, long total, int page, int size) {
}
```

`JournalService.java`:

```java
package com.example.accounting;

import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.Collection;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.function.Function;
import java.util.stream.Collectors;

import jakarta.persistence.criteria.Predicate;
import jakarta.persistence.criteria.Root;
import jakarta.persistence.criteria.Subquery;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Sort;
import org.springframework.data.jpa.domain.Specification;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class JournalService {

	static final int MAX_PAGE_SIZE = 200;

	private final JournalEntryRepository entries;

	private final AccountRepository accounts;

	private final FiscalPeriodRepository periods;

	private final AccountingSettings settings;

	private final Clock clock;

	JournalService(JournalEntryRepository entries, AccountRepository accounts, FiscalPeriodRepository periods,
			AccountingSettings settings, Clock clock) {
		this.entries = entries;
		this.accounts = accounts;
		this.periods = periods;
		this.settings = settings;
		this.clock = clock;
	}

	@Transactional(readOnly = true)
	public EntryPage list(LocalDate from, LocalDate to, EntryStatus status, Long accountId, String q, int page, int size) {
		if (size < 1 || size > MAX_PAGE_SIZE || page < 0) {
			throw AccountingException.badRequest("validation",
					"page must not be negative and size must be between 1 and " + MAX_PAGE_SIZE);
		}
		Page<JournalEntry> result = entries.findAll(filter(from, to, status, accountId, q),
				PageRequest.of(page, size, Sort.by(Sort.Direction.DESC, "entryDate", "id")));
		return new EntryPage(result.stream().map(EntrySummary::from).toList(), result.getTotalElements(), page, size);
	}

	@Transactional(readOnly = true)
	public EntryResponse get(long id) {
		return EntryResponse.from(load(id));
	}

	public EntryResponse create(EntryRequest request) {
		settings.require();
		List<ValidLine> lines = validated(request.lines());
		JournalEntry entry = new JournalEntry(request.date(), periodOf(request.date()), request.reference(),
				request.description(), Actor.current(), clock.instant());
		lines.forEach(line -> entry.addLine(line.accountId(), line.side(), line.memo()));
		return EntryResponse.from(entries.saveAndFlush(entry));
	}

	public EntryResponse update(long id, EntryRequest request) {
		JournalEntry entry = load(id);
		requireDraft(entry);
		if (request.version() == null) {
			throw AccountingException.badRequest("validation", "version is required when updating an entry");
		}
		if (!request.version().equals(entry.getVersion())) {
			throw AccountingException.conflict("version-conflict", "Someone else changed this entry");
		}
		List<ValidLine> lines = validated(request.lines());
		entry.edit(request.date(), periodOf(request.date()), request.reference(), request.description(),
				Actor.current(), clock.instant());
		// Hibernate inserts before it deletes, which would trip UNIQUE (tenant_id, entry_id, line_no).
		entry.getLines().clear();
		entries.flush();
		lines.forEach(line -> entry.addLine(line.accountId(), line.side(), line.memo()));
		return EntryResponse.from(entries.saveAndFlush(entry));
	}

	public void delete(long id) {
		JournalEntry entry = load(id);
		requireDraft(entry);
		entries.delete(entry);
	}

	public JournalEntry load(long id) {
		return entries.findById(id).orElseThrow(() -> new AccountingNotFoundException("Journal entry " + id));
	}

	static void requireDraft(JournalEntry entry) {
		if (entry.getStatus() != EntryStatus.DRAFT) {
			throw AccountingException.conflict("not-draft", "Only a draft entry can be changed");
		}
	}

	/** An unknown id and another tenant's id are refused the same way as an unusable account. */
	public void checkAccountsUsable(Collection<Long> accountIds) {
		Set<Long> ids = Set.copyOf(accountIds);
		Map<Long, Account> found = accounts.findAllById(ids).stream()
				.collect(Collectors.toMap(Account::getId, Function.identity()));
		for (Long id : ids) {
			Account account = found.get(id);
			if (account == null || !account.isPostable() || !account.isActive()) {
				throw AccountingException.unprocessable("account-not-postable",
						"Account " + id + " cannot receive journal lines");
			}
		}
	}

	private List<ValidLine> validated(List<LineRequest> requested) {
		if (requested.size() > JournalRules.MAX_LINES) {
			throw AccountingException.badRequest("too-many-lines",
					"An entry can have at most " + JournalRules.MAX_LINES + " lines");
		}
		List<ValidLine> lines = new ArrayList<>();
		for (LineRequest line : requested) {
			Side side = new Side(JournalRules.amount(line.debit()), JournalRules.amount(line.credit()));
			JournalRules.checkLine(side);
			lines.add(new ValidLine(line.accountId(), side, line.memo()));
		}
		checkAccountsUsable(lines.stream().map(ValidLine::accountId).toList());
		return lines;
	}

	private Long periodOf(LocalDate date) {
		return periods.findByYearAndMonth(date.getYear(), date.getMonthValue())
				.orElseThrow(() -> AccountingException.unprocessable("no-period", "No fiscal period exists for " + date))
				.getId();
	}

	private static Specification<JournalEntry> filter(LocalDate from, LocalDate to, EntryStatus status, Long accountId,
			String q) {
		return (root, query, cb) -> {
			List<Predicate> predicates = new ArrayList<>();
			if (from != null) {
				predicates.add(cb.greaterThanOrEqualTo(root.<LocalDate>get("entryDate"), from));
			}
			if (to != null) {
				predicates.add(cb.lessThanOrEqualTo(root.<LocalDate>get("entryDate"), to));
			}
			if (status != null) {
				predicates.add(cb.equal(root.get("status"), status));
			}
			if (accountId != null) {
				Subquery<Long> lines = query.subquery(Long.class);
				Root<JournalLine> line = lines.from(JournalLine.class);
				lines.select(line.<Long>get("id"))
						.where(cb.equal(line.get("entry"), root), cb.equal(line.get("accountId"), accountId));
				predicates.add(cb.exists(lines));
			}
			if (q != null && !q.isBlank()) {
				// The search text is literal: % and _ typed by a user are not wildcards.
				String pattern = "%" + q.strip().toLowerCase().replace("\\", "\\\\").replace("%", "\\%").replace("_", "\\_") + "%";
				predicates.add(cb.or(cb.like(cb.lower(root.<String>get("description")), pattern, '\\'),
						cb.like(cb.lower(root.<String>get("reference")), pattern, '\\'),
						cb.like(cb.lower(root.<String>get("entryNo")), pattern, '\\')));
			}
			return cb.and(predicates.toArray(Predicate[]::new));
		};
	}

	private record ValidLine(Long accountId, Side side, String memo) {
	}

}
```

`JournalController.java`:

```java
package com.example.accounting;

import java.net.URI;
import java.time.LocalDate;

import jakarta.validation.Valid;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/journal-entries")
public class JournalController {

	private final JournalService service;

	public JournalController(JournalService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('accounting:read')")
	public EntryPage list(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
			@RequestParam(required = false) EntryStatus status,
			@RequestParam(required = false) Long accountId,
			@RequestParam(required = false) String q,
			@RequestParam(defaultValue = "0") int page,
			@RequestParam(defaultValue = "50") int size) {
		return service.list(from, to, status, accountId, q, page, size);
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:read')")
	public EntryResponse get(@PathVariable long id) {
		return service.get(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('accounting:write')")
	public ResponseEntity<EntryResponse> create(@Valid @RequestBody EntryRequest request) {
		EntryResponse created = service.create(request);
		return ResponseEntity.created(URI.create("/api/accounting/journal-entries/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:write')")
	public EntryResponse update(@PathVariable long id, @Valid @RequestBody EntryRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('accounting:write')")
	public ResponseEntity<Void> delete(@PathVariable long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

}
```

- [ ] **Step 7: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 8: Commit**

```bash
git add backend/accounting
git commit -m "Add journal drafts: rules, create, edit, delete, filtered paged list

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Posting, numbering and reversal

**Files:**
- Create in `backend/accounting/src/main/java/com/example/accounting/`: `EntryNumbers.java`, `ReverseRequest.java`, `ReverseResponse.java`, `PostingService.java`, `PostingController.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/PostingTests.java`, `PostingConcurrencyTests.java`

**Interfaces:**
- Consumes: `JournalService.load(long)`, `JournalService.requireDraft`, `JournalService.checkAccountsUsable`, `JournalEntryRepository`, `FiscalPeriodRepository.lockForPosting(int, int)`, `AccountingSettings.require()`, `AuditTrail.record`, `JournalRules`, test helpers `balancedDraft`, `draft`, `line`, `account`, `setupAccounting`.
- Produces: `PostingService.post(long id)` → `EntryResponse`; `PostingService.reverse(long id, ReverseRequest)` → `ReverseResponse(EntryResponse reversal, Long draftId)`. Both are public on a public class so tests can call them inside a transaction.

- [ ] **Step 1: Write the failing tests**

`PostingTests.java`:

```java
package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostingTests extends AccountingTestSupport {

	private long cash;

	private long sales;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
	}

	private ResultActions postEntry(long id, String sub) throws Exception {
		return mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as(sub, READ, POST)));
	}

	private ResultActions reverse(long id, String json) throws Exception {
		return mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", READ, POST))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long periodId(int month) throws Exception {
		String body = mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(admin())).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$[" + (month - 1) + "].id")).longValue();
	}

	private void closePeriod(int month) throws Exception {
		mvc.perform(post("/api/accounting/periods/" + periodId(month) + "/close").with(as("u", PERIOD_MANAGE)))
				.andExpect(status().isOk());
	}

	private void setSeparatePoster(boolean required) throws Exception {
		mvc.perform(put("/api/accounting/settings").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"requireSeparatePoster\": " + required + "}")).andExpect(status().isOk());
	}

	@Test
	void postingNumbersTheEntryAndAuditsIt() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"))
				.andExpect(jsonPath("$.postedByName").value("checker-name"));
		long second = balancedDraft("maker", DATE);
		postEntry(second, "checker").andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000002"));

		Number audits = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'POST' and actor_sub = 'checker'")
				.getSingleResult());
		assertThat(audits.longValue()).isEqualTo(2);
	}

	@Test
	void eachTenantAndYearHasItsOwnNumbers() throws Exception {
		mvc.perform(post("/api/accounting/periods/year/" + (YEAR + 1)).with(as("u", PERIOD_MANAGE))).andExpect(status().isOk());
		postEntry(balancedDraft("maker", DATE), "checker").andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"));
		postEntry(balancedDraft("maker", (YEAR + 1) + "-01-05"), "checker")
				.andExpect(jsonPath("$.entryNo").value("JE-" + (YEAR + 1) + "-000001"));

		UUID other = UUID.randomUUID();
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
		String body = mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ))).andReturn().getResponse().getContentAsString();
		long otherCash = ((java.util.List<Number>) JsonPath.read(body, "$[?(@.code=='1110')].id")).get(0).longValue();
		long otherSales = ((java.util.List<Number>) JsonPath.read(body, "$[?(@.code=='4100')].id")).get(0).longValue();
		String created = mvc.perform(post("/api/accounting/journal-entries").with(asTenant(other, "o", WRITE))
						.contentType(MediaType.APPLICATION_JSON)
						.content(entry(DATE, line(otherCash, "5", "0"), line(otherSales, "0", "5"))))
				.andReturn().getResponse().getContentAsString();
		long otherEntry = ((Number) JsonPath.read(created, "$.id")).longValue();
		mvc.perform(post("/api/accounting/journal-entries/" + otherEntry + "/post").with(asTenant(other, "p", POST)))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"));

		mvc.perform(post("/api/accounting/journal-entries/" + otherEntry + "/post").with(as("checker", POST)))
				.andExpect(problem(404, "not-found"));
	}

	@Test
	void anUnbalancedOrShortEntryIsNotPosted() throws Exception {
		postEntry(draft("maker", DATE, line(cash, "1", "0")), "checker").andExpect(problem(422, "too-few-lines"));
		postEntry(draft("maker", DATE, line(cash, "2", "0"), line(sales, "0", "1")), "checker")
				.andExpect(problem(422, "unbalanced"));
		postEntry(draft("maker", DATE), "checker").andExpect(problem(422, "too-few-lines"));
	}

	@Test
	void postingNeedsThePostPermission() throws Exception {
		long id = balancedDraft("maker", DATE);
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as("u", READ, WRITE)))
				.andExpect(problem(403, "forbidden"));
	}

	@Test
	void makerCannotPostUntilTheSettingIsOff() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "maker").andExpect(problem(403, "separate-poster-required"));

		long edited = balancedDraft("maker", DATE);
		String body = mvc.perform(get("/api/accounting/journal-entries/" + edited).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		long version = ((Number) JsonPath.read(body, "$.version")).longValue();
		mvc.perform(put("/api/accounting/journal-entries/" + edited).with(as("editor", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, line(cash, "3", "0"), line(sales, "0", "3")).replaceFirst("\\{", "{\"version\": " + version + ", ")))
				.andExpect(status().isOk());
		postEntry(edited, "editor").andExpect(problem(403, "separate-poster-required"));
		postEntry(edited, "maker").andExpect(problem(403, "separate-poster-required"));

		setSeparatePoster(false);
		postEntry(id, "maker").andExpect(status().isOk());
	}

	@Test
	void aPostedEntryCannotBeChanged() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker").andExpect(status().isOk());

		postEntry(id, "checker").andExpect(problem(409, "not-draft"));
		mvc.perform(delete("/api/accounting/journal-entries/" + id).with(as("maker", WRITE))).andExpect(problem(409, "not-draft"));
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("maker", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE).replaceFirst("\\{", "{\"version\": 0, "))).andExpect(problem(409, "not-draft"));
	}

	@Test
	void nothingIsPostedIntoAClosedPeriod() throws Exception {
		closePeriod(2);
		long id = balancedDraft("maker", YEAR + "-02-10");
		postEntry(id, "checker").andExpect(problem(422, "period-closed"));
	}

	@Test
	void movingADraftIntoAClosedPeriodBlocksPosting() throws Exception {
		closePeriod(2);
		long id = balancedDraft("maker", DATE);
		String body = mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		long version = ((Number) JsonPath.read(body, "$.version")).longValue();
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("maker", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-02-10", line(cash, "1", "0"), line(sales, "0", "1")).replaceFirst("\\{", "{\"version\": " + version + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(problem(422, "period-closed"));

		String moved = mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		version = ((Number) JsonPath.read(moved, "$.version")).longValue();
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("maker", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, line(cash, "1", "0"), line(sales, "0", "1")).replaceFirst("\\{", "{\"version\": " + version + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(status().isOk());
	}

	@Test
	void aDeactivatedAccountBlocksPosting() throws Exception {
		long id = balancedDraft("maker", DATE);
		deactivate(sales, "4100", "Penjualan", "REVENUE", account("4000"));
		postEntry(id, "checker").andExpect(problem(422, "account-not-postable"));
	}

	private void deactivate(long accountId, String code, String name, String type, long parent) throws Exception {
		mvc.perform(put("/api/accounting/accounts/" + accountId).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
				{"code": "%s", "name": "%s", "type": "%s", "parentId": %d, "postable": true, "active": false}
				""".formatted(code, name, type, parent))).andExpect(status().isOk());
	}

	@Test
	void reversalSwapsSidesAndMarksTheOriginal() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");

		String body = reverse(id, "{\"date\": \"" + YEAR + "-04-01\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reversal.status").value("POSTED"))
				.andExpect(jsonPath("$.reversal.entryNo").value("JE-" + YEAR + "-000002"))
				.andExpect(jsonPath("$.reversal.date").value(YEAR + "-04-01"))
				.andExpect(jsonPath("$.reversal.description").value("Reversal of JE-" + YEAR + "-000001"))
				.andExpect(jsonPath("$.reversal.reversalOf").value((int) id))
				.andExpect(jsonPath("$.reversal.lines[0].debit").value("0.00"))
				.andExpect(jsonPath("$.reversal.lines[0].credit").value("100.00"))
				.andExpect(jsonPath("$.draftId").isEmpty())
				.andReturn().getResponse().getContentAsString();
		long reversalId = ((Number) JsonPath.read(body, "$.reversal.id")).longValue();

		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ)))
				.andExpect(jsonPath("$.status").value("REVERSED"))
				.andExpect(jsonPath("$.reversedBy").value((int) reversalId));

		Number audits = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'REVERSE'").getSingleResult());
		assertThat(audits.longValue()).isEqualTo(1);
	}

	@Test
	void theMakerMayReverseAndTheDateDefaultsToToday() throws Exception {
		long id = balancedDraft("checker", DATE);
		postEntry(id, "other");
		reverse(id, "{}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reversal.date").value(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).toString()));
	}

	@Test
	void reverseAndCopyCreatesACorrectableDraft() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");

		String body = reverse(id, "{\"date\": \"" + DATE + "\", \"copyToDraft\": true}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.draftId").isNumber())
				.andReturn().getResponse().getContentAsString();
		long draftId = ((Number) JsonPath.read(body, "$.draftId")).longValue();
		mvc.perform(get("/api/accounting/journal-entries/" + draftId).with(as("u", READ)))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.lines[0].debit").value("100.00"))
				.andExpect(jsonPath("$.createdByName").value("checker-name"));
	}

	@Test
	void reversalRulesAreEnforced() throws Exception {
		long draftId = balancedDraft("maker", DATE);
		reverse(draftId, "{}").andExpect(problem(409, "not-draft"));

		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");
		reverse(id, "{\"date\": \"1999-01-01\"}").andExpect(problem(422, "no-period"));
		closePeriod(5);
		reverse(id, "{\"date\": \"" + YEAR + "-05-01\"}").andExpect(problem(422, "period-closed"));
		reverse(id, "{\"date\": \"" + DATE + "\"}").andExpect(status().isOk());
		reverse(id, "{\"date\": \"" + DATE + "\"}").andExpect(problem(409, "already-reversed"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("u", READ))
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(problem(403, "forbidden"));
	}

	@Test
	void reversingWithADeactivatedAccountIsAClearRefusal() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");
		deactivate(sales, "4100", "Penjualan", "REVENUE", account("4000"));
		reverse(id, "{\"date\": \"" + DATE + "\"}").andExpect(problem(422, "account-not-postable"));
	}

}
```

`PostingConcurrencyTests.java`:

```java
package com.example.accounting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostingConcurrencyTests extends AccountingTestSupport {

	@Autowired
	PostingService posting;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
	}

	private MockHttpServletResponse postEntry(long id) throws Exception {
		return mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as("checker", READ, POST)))
				.andReturn().getResponse();
	}

	@Test
	void twentyParallelPostsGetGaplessNumbers() throws Exception {
		List<Long> drafts = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			drafts.add(balancedDraft("maker", DATE));
		}
		ExecutorService pool = Executors.newFixedThreadPool(20);
		try {
			List<Callable<MockHttpServletResponse>> posts = new ArrayList<>();
			drafts.forEach(id -> posts.add(() -> postEntry(id)));
			List<String> numbers = new ArrayList<>();
			for (Future<MockHttpServletResponse> result : pool.invokeAll(posts)) {
				MockHttpServletResponse response = result.get();
				assertThat(response.getStatus()).isEqualTo(200);
				numbers.add(JsonPath.read(response.getContentAsString(), "$.entryNo"));
			}
			List<String> expected = IntStream.rangeClosed(1, 20)
					.mapToObj(n -> "JE-%d-%06d".formatted(YEAR, n))
					.toList();
			assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aFailedPostConsumesNoNumber() throws Exception {
		long id = balancedDraft("maker", DATE);
		// A number is allocated, then the transaction rolls back after it, as a failure at commit would.
		assertThatThrownBy(() -> inTenantTransaction(tenant, "checker", () -> {
			posting.post(id);
			throw new IllegalStateException("roll back after numbering");
		})).hasMessage("roll back after numbering");
		long unbalanced = draft("maker", DATE, line(account("1110"), "1", "0"));
		assertThat(postEntry(unbalanced).getStatus()).isEqualTo(422);

		mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as("checker", READ, POST)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"));
	}

	@Test
	void aPostRacingACloseNeverLandsInAClosedPeriodAfterTheClose() throws Exception {
		String body = mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(admin()))
				.andReturn().getResponse().getContentAsString();
		long period = ((Number) JsonPath.read(body, "$[2].id")).longValue();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 10; round++) {
				long id = balancedDraft("maker", DATE);
				Future<MockHttpServletResponse> posted = pool.submit(() -> postEntry(id));
				Future<MockHttpServletResponse> closed = pool.submit(() -> mvc
						.perform(post("/api/accounting/periods/" + period + "/close").with(as("closer", PERIOD_MANAGE)))
						.andReturn().getResponse());
				assertThat(posted.get().getStatus()).isIn(200, 422);
				assertThat(closed.get().getStatus()).isIn(200, 409);

				Object[] state = inTransaction(() -> (Object[]) entityManager.createNativeQuery("""
						select p.status, e.status <> 'POSTED' or p.status = 'OPEN' or e.posted_at <= p.closed_at
						from accounting.journal_entry e join accounting.fiscal_period p on p.id = e.period_id
						where e.id = :id
						""").setParameter("id", id).getSingleResult());
				assertThat(state[1]).as("a post never lands after the close").isEqualTo(true);
				if ("CLOSED".equals(state[0])) {
					mvc.perform(post("/api/accounting/periods/" + period + "/reopen").with(as("closer", PERIOD_MANAGE))
							.contentType("application/json").content("{\"reason\": \"next round\"}"))
							.andExpect(status().isOk());
				}
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/accounting -am test -Dtest='Posting*Tests' -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile error, `cannot find symbol: class PostingService`.

- [ ] **Step 3: Write the implementation**

`EntryNumbers.java`:

```java
package com.example.accounting;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Component;

/**
 * Gapless numbers per tenant and year. The upsert holds the sequence row's lock until the transaction ends, so
 * concurrent posts wait, and a rollback gives the number back.
 */
@Component
class EntryNumbers {

	@PersistenceContext
	private EntityManager entityManager;

	String next(int year) {
		long value = ((Number) entityManager.createNativeQuery("""
				insert into accounting.entry_sequence (tenant_id, year, next_value)
				values (cast(nullif(current_setting('app.tenant_id', true), '') as uuid), :year, 2)
				on conflict (tenant_id, year) do update set next_value = accounting.entry_sequence.next_value + 1
				returning next_value - 1
				""").setParameter("year", year).getSingleResult()).longValue();
		return "JE-%d-%06d".formatted(year, value);
	}

}
```

`ReverseRequest.java`:

```java
package com.example.accounting;

import java.time.LocalDate;

/** {@code date} defaults to today; {@code copyToDraft} also creates a draft with the original's lines. */
public record ReverseRequest(LocalDate date, boolean copyToDraft) {
}
```

`ReverseResponse.java`:

```java
package com.example.accounting;

public record ReverseResponse(EntryResponse reversal, Long draftId) {
}
```

`PostingService.java`:

```java
package com.example.accounting;

import java.time.Clock;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Map;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class PostingService {

	private final JournalService journal;

	private final JournalEntryRepository entries;

	private final FiscalPeriodRepository periods;

	private final AccountingSettings settings;

	private final EntryNumbers numbers;

	private final AuditTrail audit;

	private final Clock clock;

	PostingService(JournalService journal, JournalEntryRepository entries, FiscalPeriodRepository periods,
			AccountingSettings settings, EntryNumbers numbers, AuditTrail audit, Clock clock) {
		this.journal = journal;
		this.entries = entries;
		this.periods = periods;
		this.settings = settings;
		this.numbers = numbers;
		this.audit = audit;
		this.clock = clock;
	}

	public EntryResponse post(long id) {
		Actor actor = Actor.current();
		Settings current = settings.require();
		JournalEntry entry = journal.load(id);
		JournalService.requireDraft(entry);
		JournalRules.checkBalanced(entry.sides());
		journal.checkAccountsUsable(entry.getLines().stream().map(JournalLine::getAccountId).toList());
		lockOpenPeriod(entry.getEntryDate());
		if (!JournalRules.mayPost(actor.sub(), entry.getCreatedBySub(), entry.getLastModifiedBySub(),
				current.isRequireSeparatePoster())) {
			throw AccountingException.forbidden("separate-poster-required",
					"You prepared this entry; another user must post it");
		}
		assignNumberAndPost(entry, actor);
		audit.record(AuditAction.POST, "journal_entry", entry.getId(), Map.of("entryNo", entry.getEntryNo()));
		return EntryResponse.from(entry);
	}

	public ReverseResponse reverse(long id, ReverseRequest request) {
		Actor actor = Actor.current();
		settings.require();
		JournalEntry original = journal.load(id);
		if (original.getStatus() == EntryStatus.DRAFT) {
			throw AccountingException.conflict("not-draft", "Only a posted entry can be reversed");
		}
		if (original.getStatus() == EntryStatus.REVERSED) {
			throw AccountingException.conflict("already-reversed", "This entry has already been reversed");
		}
		// The guard on journal_line would otherwise reject these lines with an unexplained 500.
		journal.checkAccountsUsable(original.getLines().stream().map(JournalLine::getAccountId).toList());
		Instant now = clock.instant();
		LocalDate date = request.date() != null ? request.date() : LocalDate.now(clock);
		FiscalPeriod period = lockOpenPeriod(date);

		JournalEntry reversal = new JournalEntry(date, period.getId(), original.getReference(),
				"Reversal of " + original.getEntryNo(), actor, now);
		reversal.reversalOf(original.getId());
		original.getLines().forEach(line -> reversal.addLine(line.getAccountId(), line.side().swapped(), line.getMemo()));
		// Lines cannot be added to a posted entry, so the reversal goes in as a draft first.
		entries.saveAndFlush(reversal);
		assignNumberAndPost(reversal, actor);

		original.markReversed(reversal.getId());
		entries.saveAndFlush(original);
		audit.record(AuditAction.REVERSE, "journal_entry", original.getId(),
				Map.of("entryNo", original.getEntryNo(), "reversalEntryNo", reversal.getEntryNo()));

		Long draftId = request.copyToDraft() ? copyToDraft(original, date, period, actor, now) : null;
		return new ReverseResponse(EntryResponse.from(reversal), draftId);
	}

	private Long copyToDraft(JournalEntry original, LocalDate date, FiscalPeriod period, Actor actor, Instant now) {
		JournalEntry copy = new JournalEntry(date, period.getId(), original.getReference(), original.getDescription(),
				actor, now);
		original.getLines().forEach(line -> copy.addLine(line.getAccountId(), line.side(), line.getMemo()));
		return entries.saveAndFlush(copy).getId();
	}

	/** Holds the period FOR SHARE until commit, so a close either waits for this post or this post sees it closed. */
	private FiscalPeriod lockOpenPeriod(LocalDate date) {
		FiscalPeriod period = periods.lockForPosting(date.getYear(), date.getMonthValue())
				.orElseThrow(() -> AccountingException.unprocessable("no-period", "No fiscal period exists for " + date));
		if (period.getStatus() != PeriodStatus.OPEN) {
			throw AccountingException.unprocessable("period-closed", "The period of " + date + " is closed");
		}
		return period;
	}

	private void assignNumberAndPost(JournalEntry entry, Actor actor) {
		entry.markPosted(numbers.next(entry.getEntryDate().getYear()), actor, clock.instant());
		entries.saveAndFlush(entry);
	}

}
```

`PostingController.java`:

```java
package com.example.accounting;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/accounting/journal-entries")
public class PostingController {

	private final PostingService service;

	public PostingController(PostingService service) {
		this.service = service;
	}

	@PostMapping("/{id}/post")
	@PreAuthorize("hasAuthority('accounting:post')")
	public EntryResponse post(@PathVariable long id) {
		return service.post(id);
	}

	@PostMapping("/{id}/reverse")
	@PreAuthorize("hasAuthority('accounting:post')")
	public ReverseResponse reverse(@PathVariable long id, @Valid @RequestBody ReverseRequest request) {
		return service.reverse(id, request);
	}

}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: BUILD SUCCESS. Check the log: no `A database guard rejected what the service allowed` error line, since every refusal here must come from the services. Grep the output for it and report the result.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting
git commit -m "Post and reverse journal entries with maker/checker and gapless numbers

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Reports

**Files:**
- Create in `backend/accounting/src/main/java/com/example/accounting/`: `TrialBalance.java`, `GeneralLedger.java`, `ReportService.java`, `ReportController.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/ReportTests.java`

**Interfaces:**
- Consumes: `AccountRepository`, `AccountType.debitNormal()`, `Clock`, `AccountingNotFoundException`, `AccountingException`, test helpers.
- Produces: `GET /api/accounting/reports/trial-balance?asOf=` and `GET /api/accounting/reports/general-ledger?accountId&from&to`.

- [ ] **Step 1: Write the failing tests**

`ReportTests.java`:

```java
package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportTests extends AccountingTestSupport {

	private long cash;

	private long sales;

	private long rent;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
		rent = account("6120");
	}

	private long posted(String date, String... lines) throws Exception {
		long id = draft("maker", date, lines);
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as("checker", POST))).andExpect(status().isOk());
		return id;
	}

	private org.springframework.test.web.servlet.ResultActions trialBalance(String asOf) throws Exception {
		return mvc.perform(get("/api/accounting/reports/trial-balance").param("asOf", asOf).with(as("u", READ)));
	}

	private org.springframework.test.web.servlet.ResultActions ledger(long account, String from, String to) throws Exception {
		return mvc.perform(get("/api/accounting/reports/general-ledger").param("accountId", String.valueOf(account))
				.param("from", from).param("to", to).with(as("u", READ)));
	}

	@Test
	void trialBalanceShowsBalancesOnTheNormalSideAndZeroDifference() throws Exception {
		posted(YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		posted(YEAR + "-01-20", line(rent, "300.00", "0"), line(cash, "0", "300.00"));

		trialBalance(YEAR + "-12-31")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rows", hasSize(3)))
				.andExpect(jsonPath("$.rows[0].code").value("1110"))
				.andExpect(jsonPath("$.rows[0].debit").value("1000.00"))
				.andExpect(jsonPath("$.rows[0].credit").value("300.00"))
				.andExpect(jsonPath("$.rows[0].balance").value("700.00"))
				.andExpect(jsonPath("$.rows[1].code").value("4100"))
				.andExpect(jsonPath("$.rows[1].balance").value("1000.00"))
				.andExpect(jsonPath("$.rows[2].code").value("6120"))
				.andExpect(jsonPath("$.rows[2].balance").value("300.00"))
				.andExpect(jsonPath("$.totalDebit").value("1300.00"))
				.andExpect(jsonPath("$.totalCredit").value("1300.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void reportsRespectTheDateAndIgnoreDrafts() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		posted(YEAR + "-02-10", line(cash, "20.00", "0"), line(sales, "0", "20.00"));
		draft("maker", YEAR + "-01-15", line(cash, "99.00", "0"), line(sales, "0", "99.00"));

		trialBalance(YEAR + "-01-31")
				.andExpect(jsonPath("$.totalDebit").value("10.00"))
				.andExpect(jsonPath("$.rows", hasSize(2)));
	}

	@Test
	void anEntryAndItsReversalNetToZero() throws Exception {
		long id = posted(YEAR + "-01-10", line(cash, "50.00", "0"), line(sales, "0", "50.00"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", POST))
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + YEAR + "-01-11\"}")).andExpect(status().isOk());

		trialBalance(YEAR + "-12-31")
				.andExpect(jsonPath("$.rows[0].balance").value("0.00"))
				.andExpect(jsonPath("$.rows[1].balance").value("0.00"))
				.andExpect(jsonPath("$.totalDebit").value("50.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void aContraAssetShowsAsANegativeBalance() throws Exception {
		long depreciation = account("1290");
		posted(YEAR + "-01-10", line(account("6140"), "5.00", "0"), line(depreciation, "0", "5.00"));
		trialBalance(YEAR + "-12-31").andExpect(jsonPath("$.rows[0].balance").value("-5.00"));
	}

	@Test
	void generalLedgerRunsTheBalanceFromTheOpeningBalance() throws Exception {
		posted(YEAR + "-01-10", line(cash, "100.00", "0"), line(sales, "0", "100.00"));
		posted(YEAR + "-02-05", line(cash, "40.00", "0"), line(sales, "0", "40.00"));
		posted(YEAR + "-02-20", line(rent, "15.00", "0"), line(cash, "0", "15.00"));
		posted(YEAR + "-03-01", line(cash, "7.00", "0"), line(sales, "0", "7.00"));

		ledger(cash, YEAR + "-02-01", YEAR + "-02-28")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountCode").value("1110"))
				.andExpect(jsonPath("$.openingBalance").value("100.00"))
				.andExpect(jsonPath("$.lines", hasSize(2)))
				.andExpect(jsonPath("$.lines[0].date").value(YEAR + "-02-05"))
				.andExpect(jsonPath("$.lines[0].entryNo").value("JE-" + YEAR + "-000002"))
				.andExpect(jsonPath("$.lines[0].debit").value("40.00"))
				.andExpect(jsonPath("$.lines[0].runningBalance").value("140.00"))
				.andExpect(jsonPath("$.lines[1].credit").value("15.00"))
				.andExpect(jsonPath("$.lines[1].runningBalance").value("125.00"))
				.andExpect(jsonPath("$.closingBalance").value("125.00"));
		ledger(sales, YEAR + "-01-01", YEAR + "-01-31").andExpect(jsonPath("$.closingBalance").value("100.00"));
	}

	@Test
	void anAccountWithNoActivityHasAnEmptyLedger() throws Exception {
		ledger(rent, YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openingBalance").value("0.00"))
				.andExpect(jsonPath("$.lines", hasSize(0)))
				.andExpect(jsonPath("$.closingBalance").value("0.00"));
	}

	@Test
	void parametersAreChecked() throws Exception {
		ledger(cash, YEAR + "-02-01", YEAR + "-01-01").andExpect(problem(400, "validation"));
		ledger(999999999L, YEAR + "-01-01", YEAR + "-02-01").andExpect(problem(404, "not-found"));
		mvc.perform(get("/api/accounting/reports/general-ledger").param("accountId", String.valueOf(cash)).with(as("u", READ)))
				.andExpect(problem(400, "validation"));
		trialBalance("nope").andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/reports/trial-balance").with(as("u"))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void trialBalanceDefaultsToToday() throws Exception {
		mvc.perform(get("/api/accounting/reports/trial-balance").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.asOf").value(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).toString()));
	}

	@Test
	void tenantsSeeOnlyTheirOwnFigures() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		UUID other = UUID.randomUUID();
		mvc.perform(get("/api/accounting/reports/trial-balance").param("asOf", YEAR + "-12-31").with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$.rows", hasSize(0)))
				.andExpect(jsonPath("$.totalDebit").value("0.00"));
		mvc.perform(get("/api/accounting/reports/general-ledger").param("accountId", String.valueOf(cash))
						.param("from", YEAR + "-01-01").param("to", YEAR + "-12-31").with(asTenant(other, "o", READ)))
				.andExpect(problem(404, "not-found"));
	}

}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/accounting -am test -Dtest=ReportTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: no `/api/accounting/reports` endpoint.

- [ ] **Step 3: Write the implementation**

`TrialBalance.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record TrialBalance(LocalDate asOf, List<Row> rows,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalDebit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal totalCredit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal difference) {

	/** {@code balance} is on the account's normal side, so a contra-asset shows as a negative asset. */
	public record Row(Long accountId, String code, String name, AccountType type,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal balance) {
	}

}
```

`GeneralLedger.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import com.fasterxml.jackson.annotation.JsonFormat;

public record GeneralLedger(Long accountId, String accountCode, String accountName, AccountType type, LocalDate from,
		LocalDate to, @JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal openingBalance, List<Line> lines,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal closingBalance) {

	public record Line(LocalDate date, Long entryId, String entryNo, String description, String memo,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
			@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal runningBalance) {
	}

}
```

`ReportService.java`:

```java
package com.example.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

/**
 * Computed live from posted lines (Phase 1 stores no balances). The native queries return only this tenant's rows
 * through row-level security. A reversed entry still counts: its reversal, also posted, nets it to zero.
 */
@Service
@Transactional(readOnly = true)
public class ReportService {

	private static final BigDecimal ZERO = BigDecimal.ZERO.setScale(2);

	@PersistenceContext
	private EntityManager entityManager;

	private final AccountRepository accounts;

	private final Clock clock;

	ReportService(AccountRepository accounts, Clock clock) {
		this.accounts = accounts;
		this.clock = clock;
	}

	public TrialBalance trialBalance(LocalDate requestedAsOf) {
		LocalDate asOf = requestedAsOf != null ? requestedAsOf : LocalDate.now(clock);
		@SuppressWarnings("unchecked")
		List<Object[]> found = entityManager.createNativeQuery("""
				select a.id, a.code, a.name, a.type, sum(l.debit), sum(l.credit)
				from accounting.account a
				join accounting.journal_line l on l.tenant_id = a.tenant_id and l.account_id = a.id
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where e.status in ('POSTED', 'REVERSED') and e.entry_date <= :asOf
				group by a.id, a.code, a.name, a.type
				order by a.code
				""").setParameter("asOf", asOf).getResultList();
		List<TrialBalance.Row> rows = new ArrayList<>();
		BigDecimal totalDebit = ZERO;
		BigDecimal totalCredit = ZERO;
		for (Object[] row : found) {
			AccountType type = AccountType.valueOf((String) row[3]);
			BigDecimal debit = money(row[4]);
			BigDecimal credit = money(row[5]);
			rows.add(new TrialBalance.Row(((Number) row[0]).longValue(), (String) row[1], (String) row[2], type, debit,
					credit, balance(type, debit, credit)));
			totalDebit = totalDebit.add(debit);
			totalCredit = totalCredit.add(credit);
		}
		return new TrialBalance(asOf, rows, totalDebit, totalCredit, totalDebit.subtract(totalCredit));
	}

	public GeneralLedger generalLedger(long accountId, LocalDate from, LocalDate to) {
		if (from.isAfter(to)) {
			throw AccountingException.badRequest("validation", "from must not be after to");
		}
		Account account = accounts.findById(accountId)
				.orElseThrow(() -> new AccountingNotFoundException("Account " + accountId));
		Object[] before = (Object[]) entityManager.createNativeQuery("""
				select coalesce(sum(l.debit), 0), coalesce(sum(l.credit), 0)
				from accounting.journal_line l
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where l.account_id = :account and e.status in ('POSTED', 'REVERSED') and e.entry_date < :from
				""").setParameter("account", accountId).setParameter("from", from).getSingleResult();
		BigDecimal opening = balance(account.getType(), money(before[0]), money(before[1]));
		@SuppressWarnings("unchecked")
		List<Object[]> found = entityManager.createNativeQuery("""
				select cast(e.entry_date as text), e.id, e.entry_no, e.description, l.memo, l.debit, l.credit
				from accounting.journal_line l
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				where l.account_id = :account and e.status in ('POSTED', 'REVERSED')
				  and e.entry_date >= :from and e.entry_date <= :to
				order by e.entry_date, e.entry_no, l.line_no
				""").setParameter("account", accountId).setParameter("from", from).setParameter("to", to).getResultList();
		List<GeneralLedger.Line> lines = new ArrayList<>();
		BigDecimal running = opening;
		for (Object[] row : found) {
			BigDecimal debit = money(row[5]);
			BigDecimal credit = money(row[6]);
			running = running.add(balance(account.getType(), debit, credit));
			lines.add(new GeneralLedger.Line(LocalDate.parse((String) row[0]), ((Number) row[1]).longValue(),
					(String) row[2], (String) row[3], (String) row[4], debit, credit, running));
		}
		return new GeneralLedger(account.getId(), account.getCode(), account.getName(), account.getType(), from, to,
				opening, lines, running);
	}

	private static BigDecimal balance(AccountType type, BigDecimal debit, BigDecimal credit) {
		return type.debitNormal() ? debit.subtract(credit) : credit.subtract(debit);
	}

	private static BigDecimal money(Object value) {
		return ((BigDecimal) value).setScale(2);
	}

}
```

`ReportController.java`:

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
@RequestMapping("/api/accounting/reports")
public class ReportController {

	private final ReportService service;

	public ReportController(ReportService service) {
		this.service = service;
	}

	@GetMapping("/trial-balance")
	@PreAuthorize("hasAuthority('accounting:read')")
	public TrialBalance trialBalance(
			@RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate asOf) {
		return service.trialBalance(asOf);
	}

	@GetMapping("/general-ledger")
	@PreAuthorize("hasAuthority('accounting:read')")
	public GeneralLedger generalLedger(@RequestParam long accountId,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
			@RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
		return service.generalLedger(accountId, from, to);
	}

}
```

Note `coalesce(sum(l.debit), 0)` returns a `BigDecimal` from the Postgres `numeric`; if Hibernate maps it differently, change `money` to `new BigDecimal(value.toString()).setScale(2)`.

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting
git commit -m "Add the trial balance and general ledger reports

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: End to end with real tokens, documentation, full verification

**Files:**
- Modify: `backend/app/src/test/java/com/example/app/TokenToPermissionsTests.java`, `ApiApplicationTests.java`
- Modify: `README.md`, `CLAUDE.md`, `docs/superpowers/specs/2026-10-29-accounting-core-design.md`

**Interfaces:**
- Consumes: the whole API; `bearer(String username, String... roles)` and `bearerIn(Map<String, UUID> organizations, String username, String... roles)` in `TokenToPermissionsTests`, whose tokens have the subject `sub-<username>`, so maker/checker sees different users.
- Produces: nothing for later tasks.

- [ ] **Step 1: Write the end-to-end test**

In `ApiApplicationTests.apiNeedsAToken`, add:

```java
		mvc.perform(get("/api/accounting/accounts")).andExpect(status().isUnauthorized());
```

In `TokenToPermissionsTests`, add this test, and `com.jayway.jsonpath.JsonPath` and `java.util.List` imports if missing. It is one test on purpose: setup can run once per tenant, and the class shares one database.

```java
	@Test
	void bookkeeperDraftsAccountantPostsAndTenantsStaySeparate() throws Exception {
		int year = java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).getYear();
		String demoAdmin = bearer("admin", "ADMIN");
		String bookkeeper = bearer("bookkeeper", "BOOKKEEPER");
		String accountant = bearer("accountant", "ACCOUNTANT");
		String viewer = bearer("viewer", "VIEWER");
		String acmeAdmin = bearerIn(Map.of("acme", ACME), "acme-admin", "ADMIN");

		mvc.perform(post("/api/accounting/setup").header(HttpHeaders.AUTHORIZATION, accountant)).andExpect(status().isForbidden());
		mvc.perform(post("/api/accounting/setup").header(HttpHeaders.AUTHORIZATION, demoAdmin)).andExpect(status().isOk());

		String accounts = mvc.perform(get("/api/accounting/accounts").header(HttpHeaders.AUTHORIZATION, viewer))
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long cash = ((List<Number>) JsonPath.read(accounts, "$[?(@.code=='1110')].id")).get(0).longValue();
		long sales = ((List<Number>) JsonPath.read(accounts, "$[?(@.code=='4100')].id")).get(0).longValue();

		String draft = "{\"date\": \"%d-03-15\", \"description\": \"Sale\", \"lines\": [{\"accountId\": %d, \"debit\": \"100.00\"}, {\"accountId\": %d, \"credit\": \"100.00\"}]}"
				.formatted(year, cash, sales);
		mvc.perform(post("/api/accounting/journal-entries").header(HttpHeaders.AUTHORIZATION, viewer)
				.contentType(MediaType.APPLICATION_JSON).content(draft)).andExpect(status().isForbidden());
		String created = mvc.perform(post("/api/accounting/journal-entries").header(HttpHeaders.AUTHORIZATION, bookkeeper)
						.contentType(MediaType.APPLICATION_JSON).content(draft))
				.andExpect(status().isCreated()).andReturn().getResponse().getContentAsString();
		long entry = ((Number) JsonPath.read(created, "$.id")).longValue();
		String postPath = "/api/accounting/journal-entries/" + entry + "/post";

		mvc.perform(post(postPath).header(HttpHeaders.AUTHORIZATION, bookkeeper)).andExpect(status().isForbidden());
		mvc.perform(post(postPath).header(HttpHeaders.AUTHORIZATION, accountant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryNo").value("JE-" + year + "-000001"))
				.andExpect(jsonPath("$.postedByName").value("accountant"));

		mvc.perform(get("/api/accounting/journal-entries/" + entry).header(HttpHeaders.AUTHORIZATION, acmeAdmin))
				.andExpect(status().isNotFound());
		mvc.perform(get("/api/accounting/reports/trial-balance").header(HttpHeaders.AUTHORIZATION, acmeAdmin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rows").isEmpty());
		mvc.perform(get("/api/accounting/reports/trial-balance").param("asOf", year + "-12-31").header(HttpHeaders.AUTHORIZATION, viewer))
				.andExpect(jsonPath("$.totalDebit").value("100.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));

		mvc.perform(post("/api/accounting/setup").header(HttpHeaders.AUTHORIZATION, acmeAdmin)).andExpect(status().isOk());
		String acmeAccounts = mvc.perform(get("/api/accounting/accounts").header(HttpHeaders.AUTHORIZATION, acmeAdmin))
				.andReturn().getResponse().getContentAsString();
		long acmeCash = ((List<Number>) JsonPath.read(acmeAccounts, "$[?(@.code=='1110')].id")).get(0).longValue();
		org.assertj.core.api.Assertions.assertThat(acmeCash).isNotEqualTo(cash);
	}
```

A line's missing `credit` or `debit` is treated as zero by `JournalRules.amount(null)`, which this test relies on.

- [ ] **Step 2: Run the test**

Run: `./mvnw -q -pl backend/app -am test`
Expected: PASS on the first run if Tasks 1–7 are right; this test pins the assembled path (signed token → converter → `iam` permissions → `tenancy` → RLS → accounting) that module tests cannot. If it fails, fix the cause; do not weaken the test.

- [ ] **Step 3: Documentation**

`README.md`:
- Demo users table: add `bookkeeper` / `BOOKKEEPER` (read + drafts) / `demo` and `accountant` / `ACCOUNTANT` (read, drafts, post, periods) / `demo`.
- Add a section `## Accounting` after "Multi-tenancy": one paragraph on what Phase 1 offers (chart of accounts copied at setup, draft → post → reverse, gapless `JE-<year>-<6 digits>` numbers, periods, trial balance, general ledger); a table of the API from the spec's "REST API" section; one line that amounts are JSON strings; one line that every error has a stable `code`. Note: "The screens arrive with the frontend plan; until then use the API (Swagger UI: `/swagger-ui.html`)." The spec's manual checklist rows are browser steps, so they come with the frontend plan too.
- "Upgrading an existing setup": add a step: the `BOOKKEEPER` and `ACCOUNTANT` roles and users come with the realm file, so re-import the realm as in step 2 (or add them in the admin console), and log in again.

`CLAUDE.md`:
- Under "Gotchas", add: "To replace an entry's lines, clear the collection and flush before adding the new ones: Hibernate inserts before it deletes, which would trip `UNIQUE (tenant_id, entry_id, line_no)`." and "`accounting` grants `app_runtime` per table, never with `ALTER DEFAULT PRIVILEGES`. Database triggers guard posted entries (`V20261102_4__accounting_guards.sql`): a service that breaks a rule gets a 500 logged as an invariant bug, so check every rule in the service first."
- Under "Status", add: "Verified `<date>` (`./mvnw verify`): accounting backend, including database guards bypassing the services, 20 parallel posts with gapless numbers, and the bookkeeper/accountant path with real signed tokens." Use the date of the verify run.

`docs/superpowers/specs/2026-10-29-accounting-core-design.md`: change the status line to `Status: backend implemented on feat/accounting-core; frontend not started`, and add this list after "Decisions taken during design", under the heading `### Settled by the backend plan`: the eight bullets from this plan's "Decisions this plan settles".

- [ ] **Step 4: Full verification**

Run: `./mvnw verify`
Expected: BUILD SUCCESS across all modules; no `Discarding location` warning; no `A database guard rejected` line. Report per-module counts.

- [ ] **Step 5: Commit**

```bash
git add backend/app/src/test/java/com/example/app/TokenToPermissionsTests.java backend/app/src/test/java/com/example/app/ApiApplicationTests.java README.md CLAUDE.md docs/superpowers/specs/2026-10-29-accounting-core-design.md
git commit -m "Prove the accounting path with real tokens and document it

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
