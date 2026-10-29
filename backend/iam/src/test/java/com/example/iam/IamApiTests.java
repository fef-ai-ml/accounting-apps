package com.example.iam;

import java.util.List;
import java.util.Map;
import java.util.Set;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest(classes = IamApiTests.TestApp.class,
		properties = {"spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test",
				"spring.jpa.hibernate.ddl-auto=validate"})
@AutoConfigureMockMvc
class IamApiTests {

	/** Boots the iam module on its own. */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ IamModule.class, TestcontainersConfiguration.class })
	static class TestApp {
	}

	@Autowired
	MockMvc mvc;

	@Autowired
	DbPermissionResolver resolver;

	private static Jwt token(Map<String, Object> claims) {
		return Jwt.withTokenValue("t").header("alg", "none").subject("sub-1").claims(c -> c.putAll(claims)).build();
	}

	@Test
	void seededRolesHaveTheirPermissions() {
		assertThat(resolver.permissionsForRoles(Set.of("VIEWER"))).containsExactly("task:read");
		assertThat(resolver.permissionsForRoles(Set.of("EDITOR"))).containsExactlyInAnyOrder("task:read", "task:write");
		assertThat(resolver.permissionsForRoles(Set.of("ADMIN")))
				.containsExactlyInAnyOrder("task:read", "task:write", "task:delete", "iam:read");
	}

	@Test
	void severalRolesAreUnioned() {
		assertThat(resolver.permissionsForRoles(Set.of("VIEWER", "EDITOR")))
				.containsExactlyInAnyOrder("task:read", "task:write");
	}

	@Test
	void builtInAndUnknownRolesAreIgnored() {
		Set<String> keycloakBuiltIns = Set.of("default-roles-zk-spring", "offline_access", "uma_authorization");
		assertThat(resolver.permissionsForRoles(keycloakBuiltIns)).isEmpty();
		assertThat(resolver.permissionsForRoles(Set.of("default-roles-zk-spring", "VIEWER"))).containsExactly("task:read");
		assertThat(resolver.permissionsForRoles(Set.of("editor"))).as("role names are case-sensitive").isEmpty();
	}

	@Test
	void tokenWithoutRolesHasNoPermissions() {
		assertThat(resolver.permissionsFor(token(Map.of("scope", "openid")))).isEmpty();
	}

	@Test
	void resolverReadsRolesFromTheToken() {
		Jwt editor = token(Map.of("realm_access", Map.of("roles", List.of("EDITOR", "offline_access"))));

		assertThat(resolver.permissionsFor(editor)).containsExactlyInAnyOrder("task:read", "task:write");
	}

	@Test
	void meDescribesTheCaller() throws Exception {
		mvc.perform(get("/api/iam/me").with(jwt()
						.jwt(j -> j.claim("preferred_username", "editor").claim("name", "Eddie Editor")
								.claim("realm_access", Map.of("roles", List.of("EDITOR"))))
						.authorities(new SimpleGrantedAuthority("task:write"), new SimpleGrantedAuthority("task:read"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.username").value("editor"))
				.andExpect(jsonPath("$.name").value("Eddie Editor"))
				.andExpect(jsonPath("$.roles", contains("EDITOR")))
				.andExpect(jsonPath("$.permissions", contains("task:read", "task:write")));
	}

	@Test
	void meFallsBackToUsernameWhenTokenHasNoName() throws Exception {
		mvc.perform(get("/api/iam/me").with(jwt().jwt(j -> j.claim("preferred_username", "viewer"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.name").value("viewer"))
				.andExpect(jsonPath("$.roles").isEmpty());
	}

	@Test
	void meRequiresToken() throws Exception {
		mvc.perform(get("/api/iam/me")).andExpect(status().isUnauthorized());
	}

	@Test
	void rolesNeedIamRead() throws Exception {
		mvc.perform(get("/api/iam/roles").with(jwt().authorities(new SimpleGrantedAuthority("task:read"))))
				.andExpect(status().isForbidden());

		mvc.perform(get("/api/iam/roles").with(jwt().authorities(new SimpleGrantedAuthority("iam:read"))))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$[0].role").value("ADMIN"))
				.andExpect(jsonPath("$[0].permissions", contains("iam:read", "task:delete", "task:read", "task:write")))
				.andExpect(jsonPath("$[1].role").value("EDITOR"))
				.andExpect(jsonPath("$[2].role").value("VIEWER"));
	}

}
