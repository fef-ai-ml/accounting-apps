package com.example.ui.common;

import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} a UI module uses to reach its backend. The base URL is looked up per module
 * ({@code app.modules.<id>.api-base-url}) and falls back to {@code app.api.base-url}, so pointing one module at its own
 * microservice is a configuration change only.
 *
 * <p>Every client calls the API as the signed-in user: it sends that user's Keycloak access token as a bearer token
 * and refreshes it when it has expired. This works because ZK runs view model commands on the servlet request thread,
 * where the user's security context and session are available.
 */
@Component
public class ModuleRestClients {

	private static final String DEFAULT_BASE_URL = "http://localhost:8080";

	private final RestClient.Builder builder;

	private final Environment environment;

	public ModuleRestClients(RestClient.Builder builder, Environment environment,
			OAuth2AuthorizedClientManager authorizedClients) {
		OAuth2ClientHttpRequestInterceptor bearerToken = new OAuth2ClientHttpRequestInterceptor(authorizedClients);
		bearerToken.setClientRegistrationIdResolver(request -> UiSecurity.REGISTRATION_ID);
		this.builder = builder.clone().requestInterceptor(bearerToken);
		this.environment = environment;
	}

	public RestClient forModule(String moduleId) {
		String fallback = environment.getProperty("app.api.base-url", DEFAULT_BASE_URL);
		String baseUrl = environment.getProperty("app.modules." + moduleId + ".api-base-url", fallback);
		return builder.clone().baseUrl(baseUrl).build();
	}

}
