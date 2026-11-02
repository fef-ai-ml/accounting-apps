package com.example.shell;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrl;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShellSecurityTests {

	@Autowired
	MockMvc mvc;

	@Test
	void loginAsksForTheOrganizationScope() throws Exception {
		mvc.perform(get("/oauth2/authorization/keycloak"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", containsString("organization")));
	}

	@Test
	void pagesRequireLogin() throws Exception {
		mvc.perform(get("/"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrl("/oauth2/authorization/keycloak"));
	}

	@Test
	void loginGoesToKeycloakWithoutDiscovery() throws Exception {
		mvc.perform(get("/oauth2/authorization/keycloak"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location",
						startsWith("http://localhost:8180/realms/zk-spring/protocol/openid-connect/auth?")));
	}

	@Test
	void zkAjaxRequestsAreNotRedirectedToLogin() throws Exception {
		// MockMvc does not run ZK's servlets, so whatever MVC answers is fine as long as security neither redirected
		// to Keycloak (ZK cannot render that HTML) nor rejected the POST for a missing CSRF token.
		int status = mvc.perform(post("/zkau")).andReturn().getResponse().getStatus();

		assertThat(status).isNotIn(302, 401, 403);
	}

	@Test
	void logoutAlsoEndsTheKeycloakSession() throws Exception {
		mvc.perform(get("/logout").with(oidcLogin().idToken(token -> token.tokenValue("id-token-value"))))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", startsWith(
						"http://localhost:8180/realms/zk-spring/protocol/openid-connect/logout?id_token_hint=id-token-value")));
	}

	@Test
	void framingIsAllowedFromTheSameOriginOnly() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
	}

}
