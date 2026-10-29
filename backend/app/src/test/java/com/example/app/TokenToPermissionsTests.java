package com.example.app;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.List;
import java.util.Map;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.oauth2.core.OAuth2TokenValidator;
import org.springframework.security.oauth2.jose.jws.SignatureAlgorithm;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.jwt.JwsHeader;
import org.springframework.security.oauth2.jwt.JwtClaimsSet;
import org.springframework.security.oauth2.jwt.JwtDecoder;
import org.springframework.security.oauth2.jwt.JwtEncoder;
import org.springframework.security.oauth2.jwt.JwtEncoderParameters;
import org.springframework.security.oauth2.jwt.NimbusJwtDecoder;
import org.springframework.security.oauth2.jwt.NimbusJwtEncoder;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import static org.hamcrest.Matchers.contains;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/**
 * A real signed bearer token, as Keycloak would issue it, through the whole chain: JWT decoder, Keycloak converter,
 * {@code DbPermissionResolver} reading the seeded {@code iam} tables, and {@code @PreAuthorize}. Only the signing key
 * is the test's own (it replaces Keycloak's JWKS); no authorities are injected.
 */
@SpringBootTest
@AutoConfigureMockMvc
@Import(TokenToPermissionsTests.Config.class)
class TokenToPermissionsTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		private final KeyPair keys = generateRsaKeyPair();

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
		}

		/** Replaces the issuer-based decoder: tokens are verified against the test key instead of Keycloak's JWKS. */
		@Bean
		JwtDecoder jwtDecoder(OAuth2TokenValidator<Jwt> keycloakAccessTokenValidator) {
			NimbusJwtDecoder decoder = NimbusJwtDecoder.withPublicKey((RSAPublicKey) keys.getPublic()).build();
			decoder.setJwtValidator(keycloakAccessTokenValidator);
			return decoder;
		}

		@Bean
		JwtEncoder testTokenEncoder() {
			RSAKey key = new RSAKey.Builder((RSAPublicKey) keys.getPublic())
					.privateKey((RSAPrivateKey) keys.getPrivate())
					.build();
			return new NimbusJwtEncoder(new ImmutableJWKSet<>(new JWKSet(key)));
		}

		private static KeyPair generateRsaKeyPair() {
			try {
				KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
				generator.initialize(2048);
				return generator.generateKeyPair();
			}
			catch (Exception ex) {
				throw new IllegalStateException(ex);
			}
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	JwtEncoder encoder;

	/** An access token shaped like Keycloak's: preferred_username, name and realm_access.roles. */
	private String bearer(String username, String... roles) {
		return tokenOfType("Bearer", username, roles);
	}

	private String tokenOfType(String type, String username, String... roles) {
		Instant now = Instant.now();
		JwtClaimsSet claims = JwtClaimsSet.builder()
				.subject("sub-" + username)
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.claim("typ", type)
				.claim("preferred_username", username)
				.claim("name", username + " (test)")
				.claim("realm_access", Map.of("roles", List.of(roles)))
				.build();
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return "Bearer " + encoder.encode(JwtEncoderParameters.from(header, claims)).getTokenValue();
	}

	@Test
	void editorTokenResolvesToEditorPermissions() throws Exception {
		mvc.perform(get("/api/iam/me").header(HttpHeaders.AUTHORIZATION, bearer("editor", "EDITOR", "offline_access")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("editor"))
				.andExpect(jsonPath("$.roles", contains("EDITOR", "offline_access")))
				.andExpect(jsonPath("$.permissions", contains("task:read", "task:write")));
	}

	@Test
	void permissionsFromTheDatabaseDecideTaskAccess() throws Exception {
		String editor = bearer("editor", "EDITOR");
		String viewer = bearer("viewer", "VIEWER");

		mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, viewer)).andExpect(status().isOk());
		mvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, viewer)
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"t\", \"done\": false}"))
				.andExpect(status().isForbidden());
		mvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, editor)
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"t\", \"done\": false}"))
				.andExpect(status().isCreated());
		mvc.perform(delete("/api/tasks/1").header(HttpHeaders.AUTHORIZATION, editor))
				.andExpect(status().isForbidden());
	}

	@Test
	void tokenWithOnlyKeycloakBuiltInRolesIsForbiddenNotAnError() throws Exception {
		mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION,
						bearer("nobody", "default-roles-zk-spring", "offline_access", "uma_authorization")))
				.andExpect(status().isForbidden());
	}

	@Test
	void idTokenIsUnauthorized() throws Exception {
		mvc.perform(get("/api/iam/me").header(HttpHeaders.AUTHORIZATION, tokenOfType("ID", "editor", "EDITOR")))
				.andExpect(status().isUnauthorized());
	}

	@Test
	void tamperedTokenIsUnauthorized() throws Exception {
		String token = bearer("admin", "ADMIN");
		String tampered = token.substring(0, token.length() - 4) + "AAAA";

		mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, tampered))
				.andExpect(status().isUnauthorized());
	}

}
