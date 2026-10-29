package com.example.ui.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Login for every ZK app: OAuth2 authorization code flow against Keycloak. Picked up by {@link ZkSupport}'s component
 * scan, so the shell and any split-out UI app are protected the same way.
 *
 * <p>Access and refresh tokens stay in the HttpSession; the browser only holds the session cookie.
 */
@Configuration(value = "uiSecurityConfiguration", proxyBeanMethods = false)
public class UiSecurity {

	/** The single client registration ({@code spring.security.oauth2.client.registration.keycloak.*}). */
	public static final String REGISTRATION_ID = "keycloak";

	@Bean
	SecurityFilterChain uiSecurity(HttpSecurity http, KeycloakLogoutSuccessHandler logoutSuccessHandler)
			throws Exception {
		http.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**").permitAll()
						// ZK's AJAX endpoint. Without a session there is no ZK desktop, so ZK answers with its own
						// session-timeout handling (zk.xml timeout-uri) instead of the browser getting Keycloak's HTML.
						.requestMatchers(HttpMethod.POST, "/zkau").permitAll()
						.anyRequest().authenticated())
				.oauth2Login(Customizer.withDefaults())
				.logout(logout -> logout
						.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/logout"))
						.logoutSuccessHandler(logoutSuccessHandler))
				// ZK ties every AJAX request to a desktop in the user's session; Spring's CSRF token would reject them.
				.csrf(csrf -> csrf.ignoringRequestMatchers("/zkau", "/zkau/**"))
				.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
		return http.build();
	}

	@Bean
	OAuth2AuthorizedClientRepository authorizedClientRepository() {
		return new HttpSessionOAuth2AuthorizedClientRepository();
	}

	/** Gives {@link ModuleRestClients} a current access token, refreshing it with the refresh token when expired. */
	@Bean
	OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientRepository authorizedClients) {
		var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
		manager.setAuthorizedClientProvider(
				OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build());
		return manager;
	}

	@Bean
	KeycloakLogoutSuccessHandler keycloakLogoutSuccessHandler(@Value("${app.keycloak.issuer-uri}") String issuerUri) {
		return new KeycloakLogoutSuccessHandler(issuerUri);
	}

}
