package com.example.task;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = TaskApiTests.TestApp.class)
@AutoConfigureMockMvc
class TaskApiTests {

	/** Boots the task module on its own, the way a task-only microservice would. */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ TaskModule.class, TestcontainersConfiguration.class })
	static class TestApp {
	}

	@Autowired
	MockMvc mvc;

	@Test
	void createThenFetchThenDelete() throws Exception {
		String location = mvc.perform(post("/api/tasks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title": "Write skeleton", "description": "ZK + Spring Boot", "done": false}
						"""))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.id", notNullValue()))
				.andExpect(jsonPath("$.createdAt", notNullValue()))
				.andReturn().getResponse().getHeader("Location");

		mvc.perform(get(location))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Write skeleton"));

		mvc.perform(delete(location)).andExpect(status().isNoContent());
		mvc.perform(get(location)).andExpect(status().isNotFound());
	}

	@Test
	void rejectsBlankTitle() throws Exception {
		mvc.perform(post("/api/tasks")
				.contentType(MediaType.APPLICATION_JSON)
				.content("""
						{"title": " ", "done": false}
						"""))
				.andExpect(status().isBadRequest());
	}

}
