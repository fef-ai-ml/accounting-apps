package com.example.ui.common;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakLogoutSuccessHandlerTests {

	private final KeycloakLogoutSuccessHandler handler =
			new KeycloakLogoutSuccessHandler("http://localhost:8180/realms/zk-spring");

	private static MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/logout");
		request.setServerPort(8081);
		return request;
	}

	@Test
	void endsTheKeycloakSessionAndComesBackHome() throws Exception {
		var idToken = OidcIdToken.withTokenValue("id-token-value").claim("sub", "u-1").build();
		var authentication = new TestingAuthenticationToken(new DefaultOidcUser(List.of(), idToken), null);
		var response = new MockHttpServletResponse();

		handler.onLogoutSuccess(request(), response, authentication);

		UriComponents redirect = UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build();
		assertThat(redirect.toUriString())
				.startsWith("http://localhost:8180/realms/zk-spring/protocol/openid-connect/logout?");
		MultiValueMap<String, String> query = redirect.getQueryParams();
		assertThat(query.getFirst("id_token_hint")).isEqualTo("id-token-value");
		assertThat(query.getFirst("post_logout_redirect_uri")).isEqualTo("http://localhost:8081/");
	}

	@Test
	void withoutAnOidcLoginItJustGoesHome() throws Exception {
		var response = new MockHttpServletResponse();

		handler.onLogoutSuccess(request(), response, null);

		assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/");
	}

}
