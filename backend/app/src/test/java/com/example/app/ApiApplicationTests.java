package com.example.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.example.iam.DbPermissionResolver;
import com.example.security.PermissionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiApplicationTests.Postgres.class)
class ApiApplicationTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class Postgres {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
		}

	}

	@Autowired
	MockMvc mvc;

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

}
