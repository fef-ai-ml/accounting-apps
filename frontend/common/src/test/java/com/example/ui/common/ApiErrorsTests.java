package com.example.ui.common;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;

import com.example.ui.common.ApiErrors.Kind;

import static org.assertj.core.api.Assertions.assertThat;

class ApiErrorsTests {

	private static HttpClientErrorException clientError(HttpStatus status) {
		return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(), new byte[0], null);
	}

	@Test
	void forbiddenIsAPermissionProblem() {
		assertThat(ApiErrors.classify(clientError(HttpStatus.FORBIDDEN))).isEqualTo(Kind.FORBIDDEN);
	}

	@Test
	void unauthorizedOrFailedTokenRefreshMeansLogInAgain() {
		assertThat(ApiErrors.classify(clientError(HttpStatus.UNAUTHORIZED))).isEqualTo(Kind.REAUTHENTICATE);
		assertThat(ApiErrors.classify(new ClientAuthorizationRequiredException("keycloak")))
				.isEqualTo(Kind.REAUTHENTICATE);
		assertThat(ApiErrors.classify(new OAuth2AuthorizationException(new OAuth2Error("invalid_grant"))))
				.isEqualTo(Kind.REAUTHENTICATE);
	}

	@Test
	void everythingElseIsAnApiError() {
		assertThat(ApiErrors.classify(clientError(HttpStatus.NOT_FOUND))).isEqualTo(Kind.OTHER);
		assertThat(ApiErrors.classify(HttpServerErrorException.create(HttpStatus.INTERNAL_SERVER_ERROR, "boom",
				new HttpHeaders(), new byte[0], null))).isEqualTo(Kind.OTHER);
		assertThat(ApiErrors.classify(new ResourceAccessException("Connection refused"))).isEqualTo(Kind.OTHER);
	}

	private static HttpClientErrorException clientError(HttpStatus status, String body) {
		return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
				body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	@Test
	void aProblemsDetailAndCodeAreShown() {
		RuntimeException ex = clientError(HttpStatus.CONFLICT,
				"{\"detail\": \"Someone else changed this entry\", \"code\": \"version-conflict\"}");

		assertThat(ApiErrors.code(ex)).isEqualTo("version-conflict");
		assertThat(ApiErrors.message(ex)).isEqualTo("Someone else changed this entry");
	}

	@Test
	void aResponseThatIsNotAProblemFallsBackToTheGenericMessage() {
		for (String body : new String[] { "<html>Bad gateway</html>", "", "[1, 2]", "{\"detail\": \"\"}" }) {
			RuntimeException ex = clientError(HttpStatus.BAD_REQUEST, body);

			assertThat(ApiErrors.code(ex)).isEmpty();
			assertThat(ApiErrors.message(ex)).startsWith("API error: ");
		}
	}

	@Test
	void anErrorThatIsNotAClientErrorHasNoCode() {
		RuntimeException ex = new ResourceAccessException("Connection refused");

		assertThat(ApiErrors.code(ex)).isEmpty();
		assertThat(ApiErrors.message(ex)).isEqualTo("API error: Connection refused");
	}

}
