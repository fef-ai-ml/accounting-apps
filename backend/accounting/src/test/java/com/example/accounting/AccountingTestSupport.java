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
		return result -> {
			status().is(httpStatus).match(result);
			jsonPath("$.code").value(code).match(result);
		};
	}

}
