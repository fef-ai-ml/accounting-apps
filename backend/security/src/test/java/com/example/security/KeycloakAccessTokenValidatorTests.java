package com.example.security;

import java.time.Instant;

import org.junit.jupiter.api.Test;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakAccessTokenValidatorTests {

	private final OAuth2TokenValidator<Jwt> validator = new SecurityModule().keycloakAccessTokenValidator();

	private static Jwt.Builder jwt() {
		return Jwt.withTokenValue("t").header("alg", "RS256").subject("s").issuedAt(Instant.now())
				.expiresAt(Instant.now().plusSeconds(60));
	}

	@Test
	void acceptsAccessToken() {
		assertThat(validator.validate(jwt().claim("typ", "Bearer").build()).hasErrors()).isFalse();
	}

	@Test
	void rejectsIdToken() {
		assertThat(validator.validate(jwt().claim("typ", "ID").build()).hasErrors()).isTrue();
	}

	@Test
	void rejectsTokenWithoutType() {
		assertThat(validator.validate(jwt().build()).hasErrors()).isTrue();
	}

}
