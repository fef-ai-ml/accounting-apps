package com.example.ui.common;

import org.springframework.core.env.Environment;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} a UI module uses to reach its backend. The base URL is looked up per module
 * ({@code app.modules.<id>.api-base-url}) and falls back to {@code app.api.base-url}, so pointing one module at its own
 * microservice is a configuration change only.
 */
@Component
public class ModuleRestClients {

	private static final String DEFAULT_BASE_URL = "http://localhost:8080";

	private final RestClient.Builder builder;

	private final Environment environment;

	public ModuleRestClients(RestClient.Builder builder, Environment environment) {
		this.builder = builder;
		this.environment = environment;
	}

	public RestClient forModule(String moduleId) {
		String fallback = environment.getProperty("app.api.base-url", DEFAULT_BASE_URL);
		String baseUrl = environment.getProperty("app.modules." + moduleId + ".api-base-url", fallback);
		return builder.clone().baseUrl(baseUrl).build();
	}

}
