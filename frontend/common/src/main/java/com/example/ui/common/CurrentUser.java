package com.example.ui.common;

import org.springframework.stereotype.Component;
import org.springframework.web.context.annotation.SessionScope;

/**
 * The signed-in user and their permissions, fetched from {@code /api/iam/me} once per HTTP session. View models wire
 * it with {@code @WireVariable private CurrentUser currentUser;}.
 *
 * <p>Permission changes in {@code iam} show up in the UI after the next login; the backend applies them within a
 * minute regardless.
 */
@Component
@SessionScope
public class CurrentUser {

	private final IamApiClient iamApiClient;

	private MeDto me;

	public CurrentUser(IamApiClient iamApiClient) {
		this.iamApiClient = iamApiClient;
	}

	/** Fetches on first use; a failed fetch throws and is retried on the next call. */
	public synchronized MeDto me() {
		if (me == null) {
			me = iamApiClient.me();
		}
		return me;
	}

	/** Whether the user holds the permission; {@code null} means nothing is required. */
	public boolean can(String permission) {
		return permission == null || me().permissions().contains(permission);
	}

	public String getName() {
		return me().name();
	}

}
