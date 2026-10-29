package com.example.ui.common;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModuleRestClientsTests {

	private static final ClientRegistration KEYCLOAK = ClientRegistration.withRegistrationId(UiSecurity.REGISTRATION_ID)
			.clientId("zk-shell")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
			.authorizationUri("http://kc/auth")
			.tokenUri("http://kc/token")
			.build();

	/** Stands in for Spring's manager: hands out a fixed access token for whoever is signed in. */
	private final OAuth2AuthorizedClientManager authorizedClients = request -> new OAuth2AuthorizedClient(KEYCLOAK,
			request.getPrincipal().getName(), new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
					"access-token-value", Instant.now(), Instant.now().plusSeconds(300)));

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	@BeforeEach
	void signIn() {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("editor", null, "USER"));
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void callsTheApiAsTheSignedInUser() {
		var environment = new MockEnvironment().withProperty("app.api.base-url", "http://api");
		server.expect(requestTo("http://api/api/tasks"))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token-value"))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		new ModuleRestClients(builder, environment, authorizedClients)
				.forModule("task").get().uri("/api/tasks").retrieve().toBodilessEntity();

		server.verify();
	}

	@Test
	void aModuleCanHaveItsOwnBaseUrl() {
		var environment = new MockEnvironment().withProperty("app.api.base-url", "http://api")
				.withProperty("app.modules.task.api-base-url", "http://task-service");
		server.expect(requestTo("http://task-service/api/tasks"))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token-value"))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		new ModuleRestClients(builder, environment, authorizedClients)
				.forModule("task").get().uri("/api/tasks").retrieve().toBodilessEntity();

		server.verify();
	}

}
