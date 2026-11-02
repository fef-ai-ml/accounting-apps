package com.example.app;

import java.security.KeyPair;
import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jose.jwk.source.ImmutableJWKSet;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
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
import org.springframework.test.annotation.DirtiesContext;
import org.springframework.test.web.servlet.MockMvc;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.nullValue;
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
@SpringBootTest(properties = "iam.demo-tenants=true")
@AutoConfigureMockMvc
@Import({ TokenToPermissionsTests.Config.class, TestcontainersConfiguration.class })
// Test classes here use different contexts and so start their own container; stop each before the next starts.
@DirtiesContext
class TokenToPermissionsTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class Config {

		private final KeyPair keys = generateRsaKeyPair();

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

	static final UUID DEMO = UUID.fromString("7f3a0c4e-1b2d-4e5f-8a9b-0c1d2e3f4a5b");

	static final UUID ACME = UUID.fromString("b2c4e6a8-0d1f-4a3b-9c5d-7e8f9a0b1c2d");

	/**
	 * An access token shaped like Keycloak's: preferred_username, name, realm_access.roles, the built-in
	 * {@code organization} aliases and the {@code tenant} claim (alias to organization id).
	 */
	private String bearer(String username, String... roles) {
		return tokenOfType("Bearer", Map.of("demo", DEMO), username, roles);
	}

	private String bearerIn(Map<String, UUID> organizations, String username, String... roles) {
		return tokenOfType("Bearer", organizations, username, roles);
	}

	private String tokenOfType(String type, Map<String, UUID> organizations, String username, String... roles) {
		Instant now = Instant.now();
		JwtClaimsSet.Builder claims = JwtClaimsSet.builder()
				.subject("sub-" + username)
				.issuedAt(now)
				.expiresAt(now.plusSeconds(300))
				.claim("typ", type)
				.claim("preferred_username", username)
				.claim("name", username + " (test)")
				.claim("realm_access", Map.of("roles", List.of(roles)));
		if (!organizations.isEmpty()) {
			claims.claim("organization", List.copyOf(organizations.keySet()));
			Map<String, Object> tenant = new HashMap<>();
			organizations.forEach((alias, id) -> tenant.put(alias, Map.of("id", id.toString())));
			claims.claim("tenant", tenant);
		}
		JwsHeader header = JwsHeader.with(SignatureAlgorithm.RS256).build();
		return "Bearer " + encoder.encode(JwtEncoderParameters.from(header, claims.build())).getTokenValue();
	}

	@Test
	void meNamesTheTenant() throws Exception {
		mvc.perform(get("/api/iam/me").header(HttpHeaders.AUTHORIZATION, bearer("editor", "EDITOR")))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant.id").value(DEMO.toString()))
				.andExpect(jsonPath("$.tenant.alias").value("demo"))
				.andExpect(jsonPath("$.tenant.status").value("ACTIVE"));
	}

	@Test
	void tokenWithoutOrganizationHasNoPermissions() throws Exception {
		String noTenant = bearerIn(Map.of(), "loner", "ADMIN");

		mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, noTenant)).andExpect(status().isForbidden());
		mvc.perform(get("/api/iam/me").header(HttpHeaders.AUTHORIZATION, noTenant))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value(nullValue()))
				.andExpect(jsonPath("$.permissions").isEmpty());
	}

	@Test
	void reCreatedOrganizationDoesNotInheritTheTenant() throws Exception {
		// Organization "demo" deleted in Keycloak and created again: same alias, new id, not onboarded.
		String reCreated = bearerIn(Map.of("demo", UUID.randomUUID()), "newcomer", "ADMIN");

		mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, reCreated)).andExpect(status().isForbidden());
		mvc.perform(get("/api/iam/me").header(HttpHeaders.AUTHORIZATION, reCreated))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.tenant").value(nullValue()))
				.andExpect(jsonPath("$.permissions").isEmpty());
	}

	@Test
	void tenantsAreIsolatedEndToEnd() throws Exception {
		String demoEditor = bearer("editor", "EDITOR");
		String acmeAdmin = bearerIn(Map.of("acme", ACME), "acme-admin", "ADMIN");

		String location = mvc.perform(post("/api/tasks").header(HttpHeaders.AUTHORIZATION, demoEditor)
						.contentType(MediaType.APPLICATION_JSON).content("{\"title\": \"demo only\", \"done\": false}"))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");

		mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, acmeAdmin)).andExpect(status().isNotFound());
		mvc.perform(get("/api/tasks").header(HttpHeaders.AUTHORIZATION, acmeAdmin))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[*].title", not(hasItem("demo only"))));
		mvc.perform(get(location).header(HttpHeaders.AUTHORIZATION, demoEditor)).andExpect(status().isOk());
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
		String idToken = tokenOfType("ID", Map.of("demo", DEMO), "editor", "EDITOR");

		mvc.perform(get("/api/iam/me").header(HttpHeaders.AUTHORIZATION, idToken))
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
