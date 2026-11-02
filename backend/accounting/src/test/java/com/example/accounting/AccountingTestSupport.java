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
import org.springframework.http.MediaType;
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
import com.jayway.jsonpath.JsonPath;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

	/** Asserts an RFC 9457 problem with this status and stable code. */
	static ResultMatcher problem(int httpStatus, String code) {
		return result -> {
			status().is(httpStatus).match(result);
			jsonPath("$.code").value(code).match(result);
		};
	}

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

}
