package com.example.task;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The issuer is never contacted: jwt() below supplies the authentication, and Boot resolves the issuer lazily.
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

	/** A request carrying a valid token whose holder has exactly these permissions. */
	private static RequestPostProcessor as(String... permissions) {
		return jwt().authorities(Arrays.stream(permissions).map(SimpleGrantedAuthority::new)
				.toArray(GrantedAuthority[]::new));
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
