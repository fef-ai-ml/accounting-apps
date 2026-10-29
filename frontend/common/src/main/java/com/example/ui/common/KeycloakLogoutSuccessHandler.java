package com.example.ui.common;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * After the local session is gone, ends the Keycloak session too (OIDC RP-initiated logout), so the next visit shows
 * the login page instead of signing the user straight back in.
 *
 * <p>Built from the issuer URI rather than OIDC discovery, so the app starts while Keycloak is down.
 */
public class KeycloakLogoutSuccessHandler implements LogoutSuccessHandler {

	private final String issuerUri;

	public KeycloakLogoutSuccessHandler(String issuerUri) {
		this.issuerUri = issuerUri;
	}

	@Override
	public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		String home = ServletUriComponentsBuilder.fromContextPath(request).path("/").toUriString();
		if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
			response.sendRedirect(home);
			return;
		}
		String keycloakLogout = UriComponentsBuilder.fromUriString(issuerUri)
				.path("/protocol/openid-connect/logout")
				.queryParam("id_token_hint", user.getIdToken().getTokenValue())
				.queryParam("post_logout_redirect_uri", home)
				.encode()
				.toUriString();
		response.sendRedirect(keycloakLogout);
	}

}
