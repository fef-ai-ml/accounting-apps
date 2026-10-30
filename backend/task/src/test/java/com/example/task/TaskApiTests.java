package com.example.task;

import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.function.Supplier;

import jakarta.persistence.EntityManager;
import jakarta.persistence.PersistenceContext;

import com.example.security.KeycloakAuthentication;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.authentication;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The issuer is never contacted: the authentication post-processors below supply the authentication, and Boot
// resolves the issuer lazily.
@SpringBootTest(classes = TaskApiTests.TestApp.class,
		properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test")
@AutoConfigureMockMvc
class TaskApiTests {

	/** Boots the task module on its own, the way a task-only microservice would. */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ TaskModule.class, TestcontainersConfiguration.class })
	static class TestApp {
	}

	private static final String READ = "task:read";
	private static final String WRITE = "task:write";
	private static final String DELETE = "task:delete";

	private static final String NEW_TASK = """
			{"title": "Write skeleton", "description": "ZK + Spring Boot", "done": false}
			""";

	@Autowired
	MockMvc mvc;

	@PersistenceContext
	EntityManager entityManager;

	@Autowired
	PlatformTransactionManager transactionManager;

	static final UUID TENANT_A = UUID.fromString("aaaaaaaa-0000-4000-8000-000000000001");

	static final UUID TENANT_B = UUID.fromString("bbbbbbbb-0000-4000-8000-000000000002");

	/** A request of tenant A whose holder has exactly these permissions. */
	private static RequestPostProcessor as(String... permissions) {
		return asTenant(TENANT_A, permissions);
	}

	/** A request whose authentication resolved to this tenant (null = no tenant), as the converter would build it. */
	private static RequestPostProcessor asTenant(UUID tenant, String... permissions) {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user").build();
		List<GrantedAuthority> authorities = Arrays.stream(permissions)
				.<GrantedAuthority>map(SimpleGrantedAuthority::new)
				.toList();
		return authentication(new KeycloakAuthentication(jwt, authorities, "user", tenant));
	}

	@Test
	void tenantsDoNotSeeEachOthersTasks() throws Exception {
		String location = createTask();

		mvc.perform(get("/api/tasks").with(asTenant(TENANT_B, READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
		// Another tenant's id looks exactly like a missing one.
		mvc.perform(get(location).with(asTenant(TENANT_B, READ))).andExpect(status().isNotFound());
		mvc.perform(put(location).with(asTenant(TENANT_B, WRITE)).contentType(MediaType.APPLICATION_JSON)
						.content(NEW_TASK))
				.andExpect(status().isNotFound());
		mvc.perform(delete(location).with(asTenant(TENANT_B, DELETE))).andExpect(status().isNotFound());
		mvc.perform(get(location).with(as(READ))).andExpect(status().isOk());
	}

	/** Runs work in a transaction as this tenant; the connection gets app.tenant_id from the tenancy module. */
	private <T> T inTenantTransaction(UUID tenant, Supplier<T> work) {
		Jwt jwt = Jwt.withTokenValue("t").header("alg", "none").subject("user").build();
		SecurityContextHolder.getContext().setAuthentication(new KeycloakAuthentication(jwt, List.of(), "user", tenant));
		try {
			return new TransactionTemplate(transactionManager).execute(status -> work.get());
		}
		finally {
			SecurityContextHolder.clearContext();
		}
	}

	private Number nativeTaskCount() {
		return (Number) entityManager.createNativeQuery("select count(*) from task.task").getSingleResult();
	}

	@Test
	void rowLevelSecurityAloneHidesOtherTenantsTasks() throws Exception {
		createTask();

		// Native SQL bypasses Hibernate's tenant filter; without the policy B would count A's task too.
		assertThat(inTenantTransaction(TENANT_A, this::nativeTaskCount).longValue()).isPositive();
		assertThat(inTenantTransaction(TENANT_B, this::nativeTaskCount).longValue()).isZero();
	}

	@Test
	void writingATaskIntoAnotherTenantIsRejected() {
		// Without the policy's check (WITH CHECK, or USING standing in for it) the insert would succeed:
		// app_runtime has INSERT.
		assertThatThrownBy(() -> inTenantTransaction(TENANT_A, () -> entityManager.createNativeQuery(
						"insert into task.task (tenant_id, title) values (cast(:tenant as uuid), 'x')")
				.setParameter("tenant", TENANT_B.toString())
				.executeUpdate()))
				.hasStackTraceContaining("row-level security");
	}

	@Test
	void requestWithoutTenantSeesNoTasks() throws Exception {
		createTask();

		mvc.perform(get("/api/tasks").with(asTenant(null, READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	private String createTask() throws Exception {
		return mvc.perform(post("/api/tasks").with(as(WRITE)).contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
	}

	@Test
	void createThenFetchThenDelete() throws Exception {
		String location = mvc.perform(post("/api/tasks").with(as(READ, WRITE, DELETE))
						.contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.id", notNullValue()))
				.andExpect(jsonPath("$.createdAt", notNullValue()))
				.andReturn().getResponse().getHeader("Location");

		mvc.perform(get(location).with(as(READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Write skeleton"));

		mvc.perform(delete(location).with(as(DELETE))).andExpect(status().isNoContent());
		mvc.perform(get(location).with(as(READ))).andExpect(status().isNotFound());
	}

	@Test
	void rejectsBlankTitle() throws Exception {
		mvc.perform(post("/api/tasks").with(as(WRITE))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title": " ", "done": false}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void requestsWithoutTokenAreUnauthorized() throws Exception {
		mvc.perform(get("/api/tasks"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
	}

	@Test
	void readerCanList() throws Exception {
		mvc.perform(get("/api/tasks").with(as(READ))).andExpect(status().isOk());
	}

	@Test
	void readerCannotChangeAnything() throws Exception {
		String location = createTask();

		mvc.perform(post("/api/tasks").with(as(READ)).contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(403));
		mvc.perform(put(location).with(as(READ)).contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isForbidden());
		mvc.perform(delete(location).with(as(READ))).andExpect(status().isForbidden());
		// Authorization comes before the lookup: a reader cannot probe which ids exist.
		mvc.perform(delete("/api/tasks/999999").with(as(READ))).andExpect(status().isForbidden());
	}

	@Test
	void editorCanCreateAndUpdateButNotDelete() throws Exception {
		String location = createTask();

		mvc.perform(put(location).with(as(READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title": "Renamed", "done": true}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Renamed"));
		mvc.perform(delete(location).with(as(READ, WRITE))).andExpect(status().isForbidden());
		mvc.perform(delete(location).with(as(DELETE))).andExpect(status().isNoContent());
	}

	@Test
	void writerWithoutReadCannotList() throws Exception {
		mvc.perform(get("/api/tasks").with(as(WRITE))).andExpect(status().isForbidden());
	}

}
