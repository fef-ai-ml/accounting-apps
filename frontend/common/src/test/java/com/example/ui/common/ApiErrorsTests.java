package com.example.ui.common;

import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpStatus;
import org.springframework.security.oauth2.client.ClientAuthorizationRequiredException;
import org.springframework.security.oauth2.core.OAuth2AuthorizationException;
import org.springframework.security.oauth2.core.OAuth2Error;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

import com.example.ui.common.ApiErrors.Kind;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.catchThrowableOfType;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;

class ApiErrorsTests {

	private static final String INTERNAL_URL = "http://internal-api:8080/api/accounting";

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

	/** The real failure path: RestClient puts the response body into the exception's message. */
	private static RestClientException failure(HttpStatus status, String body) {
		RestClient.Builder builder = RestClient.builder();
		MockRestServiceServer.bindTo(builder).build().expect(requestTo(INTERNAL_URL)).andRespond(withStatus(status).body(body));
		return catchThrowableOfType(RestClientException.class, () -> builder.build().get().uri(INTERNAL_URL).retrieve().toBodilessEntity());
	}

	@Test
	void aProblemsDetailAndCodeAreShownWhateverTheStatus() {
		for (HttpStatus status : new HttpStatus[] { HttpStatus.CONFLICT, HttpStatus.FORBIDDEN, HttpStatus.INTERNAL_SERVER_ERROR }) {
			RuntimeException ex = failure(status, "{\"detail\": \"Someone else changed this entry\", \"code\": \"version-conflict\"}");

			assertThat(ApiErrors.code(ex)).as(status.toString()).isEqualTo("version-conflict");
			assertThat(ApiErrors.message(ex)).as(status.toString()).isEqualTo("Someone else changed this entry");
		}
	}

	@Test
	void aResponseThatIsNotAProblemNeverReachesTheUser() {
		for (HttpStatus status : new HttpStatus[] { HttpStatus.BAD_REQUEST, HttpStatus.BAD_GATEWAY }) {
			for (String body : new String[] { "<html>secret proxy page</html>", "", "[1, 2]", "{\"detail\": \"\"}" }) {
				RuntimeException ex = failure(status, body);

				assertThat(ApiErrors.code(ex)).isEmpty();
				assertThat(ApiErrors.message(ex)).isEqualTo("The server could not complete the request.");
			}
		}
		// What the generic text protects against: the exception does carry the body.
		assertThat(failure(HttpStatus.BAD_GATEWAY, "<html>secret proxy page</html>")).hasMessageContaining("secret proxy page");
	}

	@Test
	void anErrorWithNoResponseNeverShowsItsMessage() {
		RuntimeException ex = new ResourceAccessException("I/O error on GET request for \"" + INTERNAL_URL + "\": Connection refused");

		assertThat(ApiErrors.code(ex)).isEmpty();
		assertThat(ApiErrors.message(ex)).isEqualTo("The server could not complete the request.");
	}

}
