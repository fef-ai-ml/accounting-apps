package com.example.ui.common;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Client for the {@code iam} API; its base URL can be pointed elsewhere with {@code app.modules.iam.api-base-url}. */
@Component
public class IamApiClient {

	static final String MODULE_ID = "iam";

	private final RestClient client;

	public IamApiClient(ModuleRestClients clients) {
		this.client = clients.forModule(MODULE_ID);
	}

	public MeDto me() {
		return client.get().uri("/api/iam/me").retrieve().body(MeDto.class);
	}

}
