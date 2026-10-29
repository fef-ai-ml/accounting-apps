package com.example.ui.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
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

	private static final Logger log = LoggerFactory.getLogger(ApiErrors.class);

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

	private static String wwwAuthenticate(RuntimeException ex) {
		if (ex instanceof HttpClientErrorException.Unauthorized unauthorized
				&& unauthorized.getResponseHeaders() != null) {
			return unauthorized.getResponseHeaders().getFirst(HttpHeaders.WWW_AUTHENTICATE);
		}
		return null;
	}

	/** Must be called while handling a ZK event (a view model command or {@code @Init}). */
	public static void show(RuntimeException ex) {
		switch (classify(ex)) {
			case FORBIDDEN -> {
				log.info("API call forbidden: {}", ex.getMessage());
				Clients.showNotification("You don't have permission to do that", "warning", null, "middle_center",
						3000);
			}
			case REAUTHENTICATE -> {
				log.warn("API call unauthorized, sending the user to log in again (WWW-Authenticate: {})",
						wwwAuthenticate(ex), ex);
				// Clears the shell session and goes through Keycloak login again.
				Executions.sendRedirect("/logout");
			}
			case OTHER -> {
				log.error("API call failed", ex);
				Clients.showNotification("API error: " + ex.getMessage(), "error", null, "middle_center", 4000);
			}
		}
	}

}
