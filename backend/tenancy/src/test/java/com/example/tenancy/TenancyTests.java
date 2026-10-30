package com.example.tenancy;

import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.Authentication;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.transaction.support.TransactionTemplate;

import com.example.security.KeycloakAuthentication;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

// One pooled connection, so consecutive transactions reuse it: a tenant left on it would leak.
@SpringBootTest(classes = TenancyTests.TestApp.class,
		properties = { "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test",
				"spring.datasource.hikari.maximum-pool-size=1", "spring.jpa.hibernate.ddl-auto=validate" })
class TenancyTests {

	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ TenancyModule.class, TestcontainersConfiguration.class })
	static class TestApp {
	}

	static final UUID A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

	static final UUID B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

	@Autowired
	NoteRepository notes;

	@Autowired
	TransactionTemplate transaction;

	@PersistenceContext
	EntityManager entityManager;

	private static Jwt jwt() {
		return Jwt.withTokenValue("t").header("alg", "none").subject("user").build();
	}

	/** Runs the work in a transaction as the given authentication (null = anonymous). */
	private <T> T as(Authentication authentication, Supplier<T> work) {
		SecurityContextHolder.getContext().setAuthentication(authentication);
		try {
			return transaction.execute(status -> work.get());
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	private <T> T asTenant(UUID tenant, Supplier<T> work) {
		return as(new KeycloakAuthentication(jwt(), List.of(), "user", tenant), work);
	}

	private List<String> texts() {
		return notes.findAll().stream().map(Note::getText).sorted().toList();
	}

	@SuppressWarnings("unchecked")
	private List<String> nativeTexts() {
		return entityManager.createNativeQuery("select text from tenancy_test.note order by text").getResultList();
	}

	@AfterEach
	void cleanUp() {
		asTenant(A, () -> { notes.deleteAll(); return null; });
		asTenant(B, () -> { notes.deleteAll(); return null; });
	}

	@Test
	void runsAsARoleThatRowLevelSecurityAppliesTo() {
		// Superusers, table owners and BYPASSRLS roles skip RLS: every other test here would prove nothing.
		Object[] role = asTenant(A, () -> (Object[]) entityManager.createNativeQuery(
				"select current_user::text, rolsuper, rolbypassrls from pg_roles where rolname = current_user")
				.getSingleResult());
		Number owned = asTenant(A, () -> (Number) entityManager.createNativeQuery(
				"select count(*) from pg_tables where tableowner = current_user").getSingleResult());

		assertThat(role).containsExactly("app_runtime", false, false);
		assertThat(owned.intValue()).isZero();
	}

	@Test
	void eachTenantSeesOnlyItsOwnRows() {
		asTenant(A, () -> notes.save(new Note("a1")));
		asTenant(B, () -> notes.save(new Note("b1")));

		assertThat(asTenant(A, this::texts)).containsExactly("a1");
		assertThat(asTenant(B, this::texts)).containsExactly("b1");
		assertThat(asTenant(A, () -> notes.findAll().getFirst().getTenantId())).isEqualTo(A);
	}

	@Test
	void rowLevelSecurityAloneHidesOtherTenants() {
		asTenant(A, () -> notes.save(new Note("a1")));
		asTenant(B, () -> notes.save(new Note("b1")));

		// Native SQL bypasses Hibernate's tenant filter; only the database policy is left.
		assertThat(asTenant(A, this::nativeTexts)).containsExactly("a1");
	}

	@Test
	void noTenantSeesNothing() {
		asTenant(A, () -> notes.save(new Note("a1")));

		assertThat(as(null, this::texts)).isEmpty();
		assertThat(asTenant(null, this::texts)).isEmpty();
	}

	@Test
	void plainJwtAuthenticationSeesNothing() {
		asTenant(A, () -> notes.save(new Note("a1")));

		assertThat(as(new JwtAuthenticationToken(jwt()), this::texts)).isEmpty();
	}

	@Test
	void pooledConnectionForgetsTheTenant() {
		asTenant(A, () -> notes.save(new Note("a1")));

		// Same single connection, now reset to '': must be zero rows, not a uuid cast error.
		assertThat(as(null, this::nativeTexts)).isEmpty();
	}

	@Test
	void writingIntoAnotherTenantIsRejected() {
		assertThatThrownBy(() -> asTenant(A, () -> entityManager.createNativeQuery(
						"insert into tenancy_test.note (tenant_id, text) values (cast(:tenant as uuid), 'x')")
				.setParameter("tenant", B.toString())
				.executeUpdate()))
				.hasStackTraceContaining("row-level security");
	}

	@Test
	void insertWithoutTenantIsRejected() {
		// Rejected by the RLS check (root marker stamped) or by NOT NULL (nothing stamped); never stored silently.
		assertThatThrownBy(() -> as(null, () -> notes.save(new Note("orphan"))))
				.isInstanceOf(RuntimeException.class);
		assertThat(asTenant(A, this::texts)).doesNotContain("orphan");
	}

}
