package com.example.ui.common;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClientResponseException;
import org.zkoss.xml.XMLs;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.util.Clients;
import tools.jackson.core.JacksonException;
import tools.jackson.databind.JsonNode;
import tools.jackson.databind.json.JsonMapper;
import tools.jackson.databind.node.MissingNode;

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

	/** The stable {@code code} of an RFC 9457 problem response, or empty. */
	public static String code(RuntimeException ex) {
		return problem(ex).path("code").asString("");
	}

	/** What to tell the user: the problem's {@code detail}, never the exception's message, which carries bodies and URLs. */
	public static String message(RuntimeException ex) {
		String detail = detail(ex);
		return detail.isBlank() ? "The server could not complete the request." : detail;
	}

	private static String detail(RuntimeException ex) {
		return problem(ex).path("detail").asString("");
	}

	private static JsonNode problem(RuntimeException ex) {
		if (ex instanceof RestClientResponseException response) {
			try {
				return JsonMapper.shared().readTree(response.getResponseBodyAsString());
			}
			catch (JacksonException notJson) {
				return MissingNode.getInstance();
			}
		}
		return MissingNode.getInstance();
	}

	/** Must be called while handling a ZK event (a view model command or {@code @Init}). */
	public static void show(RuntimeException ex) {
		switch (classify(ex)) {
			case FORBIDDEN -> {
				log.info("API call forbidden: {}", ex.getMessage());
				toast(detail(ex).isBlank() ? "You don't have permission to do that" : detail(ex), "warning", 3000);
			}
			case REAUTHENTICATE -> {
				log.warn("API call unauthorized, sending the user to log in again (WWW-Authenticate: {})",
						wwwAuthenticate(ex), ex);
				// Clears the shell session and goes through Keycloak login again.
				Executions.sendRedirect("/logout");
			}
			case OTHER -> {
				if (code(ex).isEmpty()) {
					log.error("API call failed", ex);
					toast(message(ex), "error", 4000);
				}
				else {
					log.info("API refused the request: {}", ex.getMessage());
					toast(message(ex), "warning", 5000);
				}
			}
		}
	}

	/** Notifications render HTML, and a backend detail can echo what a user typed. */
	private static void toast(String text, String type, int duration) {
		Clients.showNotification(XMLs.escapeXML(text), type, null, "middle_center", duration);
	}

}
