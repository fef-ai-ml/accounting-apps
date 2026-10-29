package com.example.ui.common;

import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.HttpClientErrorException;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.util.Clients;

/** How a failed backend call is shown to the user. View models call {@link #show} from their catch blocks. */
public final class ApiErrors {

	public enum Kind {
		/** The user lacks the permission (API answered 403). */
		FORBIDDEN,
		/** The login is gone: API answered 401, or the access token could not be refreshed. */
		REAUTHENTICATE,
		/** Anything else: backend down, validation error, server error. */
		OTHER
	}

	private ApiErrors() {
	}

	public static Kind classify(RuntimeException ex) {
		if (ex instanceof HttpClientErrorException.Forbidden) {
			return Kind.FORBIDDEN;
		}
		if (ex instanceof HttpClientErrorException.Unauthorized || ex instanceof OAuth2AuthorizationException) {
			return Kind.REAUTHENTICATE;
		}
		return Kind.OTHER;
	}

	/** Must be called while handling a ZK event (a view model command or {@code @Init}). */
	public static void show(RuntimeException ex) {
		switch (classify(ex)) {
			case FORBIDDEN -> Clients.showNotification("You don't have permission to do that", "warning", null,
					"middle_center", 3000);
			// Clears the shell session and goes through Keycloak login again.
			case REAUTHENTICATE -> Executions.sendRedirect("/logout");
			case OTHER -> Clients.showNotification("API error: " + ex.getMessage(), "error", null, "middle_center",
					4000);
		}
	}

}
