# Keycloak Login and `iam` Permissions Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Users log in to the ZK shell through Keycloak; the shell calls the backend with the user's bearer token; the
backend authorizes every endpoint by `<module>:<action>` permissions resolved from the `iam` Postgres schema.

**Architecture:** New `backend/security` module (stateless JWT resource server, `PermissionResolver` interface,
fail-closed default) and `backend/iam` module (schema `iam`, cached `DbPermissionResolver`, `/api/iam/me`,
`/api/iam/roles`). `backend/task` enforces with `@PreAuthorize` and never imports `iam`. `frontend/common` turns every
ZK app into an OAuth2 client (`oauth2Login`, tokens in the HttpSession, bearer relay in `ModuleRestClients`,
`CurrentUser`, `ApiErrors`). Keycloak 26.8.1 runs from `compose.yaml` with a committed realm file.

**Tech Stack:** Spring Boot 4.1.2 (Spring Security 7.1.2), Java 25, ZK 10.3.0.1-jakarta, PostgreSQL 17, Flyway,
Caffeine 3.2.4 (Boot-managed), Keycloak 26.8.1, Testcontainers.

**Spec:** `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md`

## Global Constraints

- Spring Boot 4.1.2, Java 25, ZK `10.3.0.1-jakarta`. No `zkspringboot` starter, no `zkspring-core`.
- Modules are entered through one class and composed with `@Import`; no package scanning across modules.
- Each backend module owns its schema and its migrations in `db/migration/<module>/`, date-based versions
  (`V20261028_1__...`). No cross-module joins or foreign keys.
- UI modules never import backend classes; the REST API is the contract. Anything bound from ZUL has JavaBean getters.
- Permissions are named `<module>:<action>`. Seed: `task:read`, `task:write`, `task:delete`, `iam:read`;
  `VIEWER` = read, `EDITOR` = read + write, `ADMIN` = all four.
- `PermissionResolver` signature is exactly `Set<String> permissionsFor(Jwt jwt)`.
- No `PermissionResolver` bean = fail closed (authenticated, zero permissions) plus a startup warning.
- Resolver cache: per role set, 60 seconds.
- Env vars: `KEYCLOAK_ISSUER_URI` (default `http://localhost:8180/realms/zk-spring`), `KEYCLOAK_CLIENT_ID` (default
  `zk-shell`), `KEYCLOAK_CLIENT_SECRET` (default `zk-shell-dev-secret`), `KEYCLOAK_URL` (Keycloak `KC_HOSTNAME`,
  default `http://localhost:8180`).
- Keycloak image `quay.io/keycloak/keycloak:26.8.1`, host port `8180`, `start-dev --import-realm`, database `keycloak`.
- Tokens never reach the browser; the shell keeps them in the HttpSession.
- 401 carries `WWW-Authenticate: Bearer`; 403 is an RFC 9457 `ProblemDetail`.
- Commit messages: imperative sentence, body optional, ending with
  `Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>`.

### Prerequisites for running tests

Backend tests use Testcontainers. On this Mac `DOCKER_HOST` points at the home-lab Docker socket
(`unix:///Users/fef/.local/share/containers/home-lab.sock`). When the socket is connected, containers start on the
home-lab, so Testcontainers must be told where their ports are (fish syntax):

```fish
set -x TESTCONTAINERS_HOST_OVERRIDE <homelab-ip>
docker ps    # must work before running backend tests
```

If `docker ps` fails, connect the home-lab socket first (or use the local `podman` context). Frontend tests need no
Docker.

### Deliberate deviations from the spec (approved in planning; Task 9 records them in the spec)

1. API errors in the UI are handled by a shared `ApiErrors.show(...)` helper called from view models, not by `zk.xml`
   error pages. View models already catch API exceptions; `zk.xml` is used only for the session-timeout URI.
2. A 401 / failed token refresh sends the user to `/logout` (not a plain reload), so a dead shell session is cleared
   and the user goes through Keycloak login again.
3. `UiSecurity` is picked up by `ZkSupport`'s existing component scan of `com.example.ui.common`, so every ZK app
   (shell or a split-out UI app) gets login automatically; the shell does not import it explicitly.
4. `POST /zkau` (ZK's AJAX endpoint) is `permitAll` at the Spring Security level. Without a session there is no ZK
   desktop, so ZK answers with its own session-timeout handling instead of the browser receiving Keycloak's HTML.
   Everything else, including `/zkau/web/**` (ZUL pages and resources), requires login.

## Review Focus

Inputs the spec implies but does not spell out, most likely to bite first:

1. **Token without usable roles** (no `realm_access` claim, or only Keycloak's built-in roles `default-roles-zk-spring`,
   `offline_access`, `uma_authorization`): must be authenticated with zero permissions (403), never a 500, and an
   empty role set must not produce invalid SQL (`IN ()`). Tests: Task 2 (`missingRealmAccessMeansNoRoles`), Task 4
   (`builtInAndUnknownRolesAreIgnored`, `tokenWithoutRolesHasNoPermissions`).
2. **Refresh token expired / Keycloak session ended while the page is open:** the next action must send the user
   through `/logout` to the login page, not show "API error" forever. Test: Task 6 (`ApiErrorsTests`, OAuth2
   exceptions and 401 classify as `REAUTHENTICATE`).
3. **HTTP session expired while a ZK page is open:** ZK's AJAX POST must not be redirected to Keycloak (ZK cannot
   render that HTML) or rejected by CSRF. Test: Task 7 (`zkAjaxRequestsAreNotRedirectedToLogin`), plus manual check in
   Task 8.
4. **Backend or `iam` unreachable when the shell loads:** the page must render with a notification, and the next
   attempt must retry instead of caching the failure. Test: Task 6 (`failedFetchIsNotCached`), plus manual check in
   Task 8.
5. **Permission check on a missing record:** a reader deleting `/api/tasks/999` must get 403, not 404 (authorization
   happens before the lookup, so a reader cannot probe which ids exist). Test: Task 3
   (`readerCannotChangeAnything`).

---

## File Structure

```
compose.yaml                                              modify: keycloak service, postgres init mount
infra/postgres/init/01-keycloak.sql                       create: CREATE DATABASE keycloak
infra/keycloak/zk-spring-realm.json                       create: realm, client, roles, demo users
pom.xml                                                   modify: dependencyManagement for new modules
backend/pom.xml                                           modify: modules security, iam
backend/security/pom.xml                                  create
backend/security/src/main/java/com/example/security/
  PermissionResolver.java                                 interface + denyAll()
  KeycloakRoles.java                                      realm_access.roles reader
  KeycloakJwtAuthenticationConverter.java                 Jwt -> JwtAuthenticationToken(permissions)
  SecurityModule.java                                     filter chain, method security, fallback resolver
  AccessDeniedProblemHandler.java                         403 ProblemDetail
backend/security/src/test/java/com/example/security/
  KeycloakJwtAuthenticationConverterTests.java
backend/task/pom.xml                                      modify: backend-security, security test starter
backend/task/src/main/java/com/example/task/TaskModule.java       modify: import SecurityModule
backend/task/src/main/java/com/example/task/TaskController.java   modify: @PreAuthorize
backend/task/src/test/java/com/example/task/TaskApiTests.java     modify: jwt() requests, 401/403 cases
backend/iam/pom.xml                                       create
backend/iam/src/main/java/com/example/iam/
  IamModule.java                                          entry point + Flyway location
  RolePermissionRepository.java                           JdbcClient queries
  DbPermissionResolver.java                               PermissionResolver + Caffeine cache
  IamController.java                                      /api/iam/me, /api/iam/roles
  MeResponse.java, RoleResponse.java                      response records
backend/iam/src/main/resources/db/migration/iam/
  V20261028_1__create_iam_tables.sql
  V20261028_2__seed_permissions.sql
backend/iam/src/test/java/com/example/iam/
  IamApiTests.java, TestcontainersConfiguration.java
backend/app/pom.xml                                       modify: backend-iam, webmvc test starter
backend/app/src/main/java/com/example/app/ApiApplication.java      modify: import IamModule
backend/app/src/main/resources/application.properties              modify: issuer-uri
backend/app/src/test/java/com/example/app/ApiApplicationTests.java modify: security smoke tests
frontend/common/pom.xml                                   modify: oauth2 client starter, test starter
frontend/common/src/main/java/com/example/ui/common/
  UiModule.java                                           modify: requiredPermission
  MeDto.java                                              create
  IamApiClient.java                                       create
  CurrentUser.java                                        create (session scoped)
  ApiErrors.java                                          create
  UiSecurity.java                                         create
  KeycloakLogoutSuccessHandler.java                       create
  ModuleRestClients.java                                  modify: bearer interceptor
frontend/common/src/main/resources/metainfo/zk/zk.xml     create: session timeout URI
frontend/common/src/test/java/com/example/ui/common/
  UiModuleTests.java, CurrentUserTests.java, ApiErrorsTests.java,
  KeycloakLogoutSuccessHandlerTests.java, ModuleRestClientsTests.java
frontend/shell/pom.xml                                    modify: oauth2 client test starter
frontend/shell/src/main/resources/application.properties  modify: Keycloak client registration
frontend/shell/src/test/java/com/example/shell/ShellSecurityTests.java  create
frontend/shell/src/main/java/com/example/shell/ShellViewModel.java      modify: filter nav, user name
frontend/shell/src/main/resources/web/shell/index.zul     modify: user + logout in header
frontend/shell/src/main/resources/web/shell/app.css       modify: header user styles
frontend/task/src/main/java/com/example/ui/task/TaskUiModule.java   modify: requiredPermission
frontend/task/src/main/java/com/example/ui/task/TaskViewModel.java  modify: canWrite/canDelete, ApiErrors
frontend/task/src/main/resources/web/task/main.zul        modify: hide controls by permission
README.md, CLAUDE.md, docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md   modify (Task 9)
```

---

### Task 1: Keycloak in `compose.yaml` with the `zk-spring` realm

**Files:**
- Modify: `compose.yaml`
- Create: `infra/postgres/init/01-keycloak.sql`
- Create: `infra/keycloak/zk-spring-realm.json`

**Interfaces:**
- Produces: realm `zk-spring`; confidential client `zk-shell` with secret `zk-shell-dev-secret`, redirect
  `http://localhost:8081/*`; realm roles `ADMIN`, `EDITOR`, `VIEWER`; users `admin`/`admin`, `editor`/`editor`,
  `viewer`/`viewer`; issuer `${KEYCLOAK_URL}/realms/zk-spring`. Access tokens carry `realm_access.roles`,
  `preferred_username`, `name` (Keycloak default client scopes `roles`, `profile`).

- [ ] **Step 1: Create the Postgres init script**

`infra/postgres/init/01-keycloak.sql`:

```sql
-- Runs only when the Postgres volume is empty. Existing installs run this statement once by hand.
CREATE DATABASE keycloak OWNER app;
```

- [ ] **Step 2: Create the realm file**

`infra/keycloak/zk-spring-realm.json` (dev values only; Keycloak imports it only if the realm does not exist yet):

```json
{
  "realm": "zk-spring",
  "enabled": true,
  "roles": {
    "realm": [
      { "name": "ADMIN", "description": "Everything, including viewing role mappings" },
      { "name": "EDITOR", "description": "Read and change data" },
      { "name": "VIEWER", "description": "Read-only" }
    ]
  },
  "clients": [
    {
      "clientId": "zk-shell",
      "name": "ZK shell",
      "enabled": true,
      "protocol": "openid-connect",
      "publicClient": false,
      "clientAuthenticatorType": "client-secret",
      "secret": "zk-shell-dev-secret",
      "standardFlowEnabled": true,
      "implicitFlowEnabled": false,
      "directAccessGrantsEnabled": false,
      "serviceAccountsEnabled": false,
      "redirectUris": ["http://localhost:8081/*"],
      "webOrigins": ["http://localhost:8081"],
      "attributes": {
        "post.logout.redirect.uris": "http://localhost:8081/*"
      }
    }
  ],
  "users": [
    {
      "username": "admin",
      "enabled": true,
      "email": "admin@example.com",
      "emailVerified": true,
      "firstName": "Ada",
      "lastName": "Admin",
      "credentials": [{ "type": "password", "value": "admin", "temporary": false }],
      "realmRoles": ["ADMIN"]
    },
    {
      "username": "editor",
      "enabled": true,
      "email": "editor@example.com",
      "emailVerified": true,
      "firstName": "Eddie",
      "lastName": "Editor",
      "credentials": [{ "type": "password", "value": "editor", "temporary": false }],
      "realmRoles": ["EDITOR"]
    },
    {
      "username": "viewer",
      "enabled": true,
      "email": "viewer@example.com",
      "emailVerified": true,
      "firstName": "Vera",
      "lastName": "Viewer",
      "credentials": [{ "type": "password", "value": "viewer", "temporary": false }],
      "realmRoles": ["VIEWER"]
    }
  ]
}
```

First/last name and email are set because Keycloak's default user profile otherwise forces an "update account" form
on first login.

- [ ] **Step 3: Add Keycloak to `compose.yaml`**

Replace the whole file with:

```yaml
services:
  postgres:
    image: postgres:17-alpine
    environment:
      POSTGRES_DB: app
      POSTGRES_USER: app
      POSTGRES_PASSWORD: app
    ports:
      - "5432:5432"
    volumes:
      - pgdata:/var/lib/postgresql/data
      # Runs only on an empty volume; see infra/postgres/init/01-keycloak.sql
      - ./infra/postgres/init:/docker-entrypoint-initdb.d:ro
    healthcheck:
      test: ["CMD-SHELL", "pg_isready -U app -d app"]
      interval: 5s
      timeout: 3s
      retries: 10

  keycloak:
    image: quay.io/keycloak/keycloak:26.8.1
    command: start-dev --import-realm
    environment:
      KC_DB: postgres
      KC_DB_URL: jdbc:postgresql://postgres:5432/keycloak
      KC_DB_USERNAME: app
      KC_DB_PASSWORD: app
      # Must be the URL both the browser and the backend use: tokens carry it as their issuer.
      KC_HOSTNAME: ${KEYCLOAK_URL:-http://localhost:8180}
      KC_BOOTSTRAP_ADMIN_USERNAME: ${KEYCLOAK_ADMIN:-admin}
      KC_BOOTSTRAP_ADMIN_PASSWORD: ${KEYCLOAK_ADMIN_PASSWORD:-admin}
    ports:
      - "8180:8080"
    volumes:
      - ./infra/keycloak:/opt/keycloak/data/import:ro
    depends_on:
      postgres:
        condition: service_healthy

volumes:
  pgdata:
```

- [ ] **Step 4: Validate the compose file and realm JSON locally**

Run: `docker compose config --quiet; and python3 -m json.tool infra/keycloak/zk-spring-realm.json > /dev/null; and echo OK`
Expected: `OK`

- [ ] **Step 5: Start Keycloak on the home-lab and verify the realm** (manual, on the home-lab host after pulling the branch)

```fish
docker exec zk-spring-postgres-1 psql -U app -d app -c 'CREATE DATABASE keycloak OWNER app;'
env KEYCLOAK_URL=http://<homelab-ip>:8180 docker compose up -d keycloak
docker compose logs -f keycloak    # wait for "Imported realm zk-spring" and "Listening on: http://0.0.0.0:8080"
```

From the Mac:

```fish
curl -s http://<homelab-ip>:8180/realms/zk-spring/.well-known/openid-configuration | python3 -c 'import sys,json; print(json.load(sys.stdin)["issuer"])'
```

Expected: `http://<homelab-ip>:8180/realms/zk-spring`. Then open `http://<homelab-ip>:8180/realms/zk-spring/account`
in a browser and log in as `editor` / `editor`: the account page opens with no "update profile" form.

- [ ] **Step 6: Commit**

```fish
git add compose.yaml infra/
git commit -m "Add Keycloak with the zk-spring realm to compose

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: `backend/security` module

**Files:**
- Create: `backend/security/pom.xml`
- Create: `backend/security/src/main/java/com/example/security/PermissionResolver.java`
- Create: `backend/security/src/main/java/com/example/security/KeycloakRoles.java`
- Create: `backend/security/src/main/java/com/example/security/KeycloakJwtAuthenticationConverter.java`
- Create: `backend/security/src/main/java/com/example/security/SecurityModule.java`
- Create: `backend/security/src/main/java/com/example/security/AccessDeniedProblemHandler.java`
- Test: `backend/security/src/test/java/com/example/security/KeycloakJwtAuthenticationConverterTests.java`
- Modify: `pom.xml` (dependencyManagement), `backend/pom.xml` (modules)

**Interfaces:**
- Produces:
  - `public interface PermissionResolver { Set<String> permissionsFor(Jwt jwt); static PermissionResolver denyAll(); }`
  - `public final class KeycloakRoles { public static Set<String> of(Jwt jwt); }`
  - `public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken>`,
    constructor `(PermissionResolver)`; authority strings = permission codes; name = `preferred_username`, else `sub`.
  - `public class SecurityModule` (`@Configuration`, import it with `@Import(SecurityModule.class)`); permits
    `/actuator/health/**`, `/actuator/info`, `/v3/api-docs/**`, `/swagger-ui/**`, `/swagger-ui.html`, `/error`;
    everything else needs a JWT. Requires property `spring.security.oauth2.resourceserver.jwt.issuer-uri`.
  - Maven artifact `com.example:backend-security`.

- [ ] **Step 1: Register the module**

In `backend/pom.xml`, replace the `<modules>` block with:

```xml
	<modules>
		<module>common</module>
		<module>security</module>
		<module>task</module>
		<module>app</module>
	</modules>
```

In the root `pom.xml` `<dependencyManagement>`, after the `backend-common` entry, add:

```xml
			<dependency>
				<groupId>com.example</groupId>
				<artifactId>backend-security</artifactId>
				<version>${project.version}</version>
			</dependency>
```

Create `backend/security/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>com.example</groupId>
		<artifactId>backend</artifactId>
		<version>0.0.1-SNAPSHOT</version>
	</parent>

	<artifactId>backend-security</artifactId>
	<name>backend-security</name>
	<description>Shared resource-server wiring: JWT validation, roles to permissions, method security</description>

	<dependencies>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server</artifactId>
		</dependency>

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>
</project>
```

- [ ] **Step 2: Write the failing test**

`backend/security/src/test/java/com/example/security/KeycloakJwtAuthenticationConverterTests.java`:

```java
package com.example.security;

import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.atomic.AtomicReference;

import org.junit.jupiter.api.Test;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakJwtAuthenticationConverterTests {

	private static Jwt jwt(Map<String, Object> claims) {
		return Jwt.withTokenValue("token").header("alg", "none").subject("sub-123").claims(c -> c.putAll(claims)).build();
	}

	@Test
	void rolesAreReadFromRealmAccess() {
		Jwt token = jwt(Map.of("realm_access", Map.of("roles", List.of("EDITOR", "VIEWER"))));

		assertThat(KeycloakRoles.of(token)).containsExactlyInAnyOrder("EDITOR", "VIEWER");
	}

	@Test
	void missingRealmAccessMeansNoRoles() {
		assertThat(KeycloakRoles.of(jwt(Map.of("scope", "openid")))).isEmpty();
		assertThat(KeycloakRoles.of(jwt(Map.of("realm_access", "not-a-map")))).isEmpty();
		assertThat(KeycloakRoles.of(jwt(Map.of("realm_access", Map.of("roles", "ADMIN"))))).isEmpty();
	}

	@Test
	void authoritiesAreThePermissionsFromTheResolver() {
		AtomicReference<Jwt> seen = new AtomicReference<>();
		var converter = new KeycloakJwtAuthenticationConverter(token -> {
			seen.set(token);
			return Set.of("task:write", "task:read");
		});
		Jwt token = jwt(Map.of("preferred_username", "editor"));

		AbstractAuthenticationToken authentication = converter.convert(token);

		assertThat(seen.get()).isSameAs(token);
		assertThat(authentication.getAuthorities()).extracting(GrantedAuthority::getAuthority)
				.containsExactly("task:read", "task:write");
		assertThat(authentication.getName()).isEqualTo("editor");
		assertThat(authentication.isAuthenticated()).isTrue();
	}

	@Test
	void nameFallsBackToSubject() {
		var converter = new KeycloakJwtAuthenticationConverter(token -> Set.of());

		assertThat(converter.convert(jwt(Map.of("scope", "openid"))).getName()).isEqualTo("sub-123");
	}

	@Test
	void denyAllGrantsNothing() {
		var converter = new KeycloakJwtAuthenticationConverter(PermissionResolver.denyAll());

		AbstractAuthenticationToken authentication =
				converter.convert(jwt(Map.of("realm_access", Map.of("roles", List.of("ADMIN")))));

		assertThat(authentication.getAuthorities()).isEmpty();
		assertThat(authentication.isAuthenticated()).isTrue();
	}

}
```

- [ ] **Step 3: Run the test to verify it fails**

Run: `./mvnw -q -pl backend/security -am test`
Expected: compilation FAILURE, `cannot find symbol: class KeycloakRoles` (and the other new types).

- [ ] **Step 4: Write the implementation**

`backend/security/src/main/java/com/example/security/PermissionResolver.java`:

```java
package com.example.security;

import java.util.Set;

import org.springframework.security.oauth2.jwt.Jwt;

/**
 * Turns a validated access token into the permission codes ({@code <module>:<action>}) its holder has.
 *
 * <p>It takes the whole token, not just role names, so an implementation in a split-out service can relay the user's
 * token to the {@code iam} module instead of reading the database.
 */
public interface PermissionResolver {

	Set<String> permissionsFor(Jwt jwt);

	/** Grants nothing; used when a deployment has no resolver, so a misconfiguration fails closed. */
	static PermissionResolver denyAll() {
		return jwt -> Set.of();
	}

}
```

`backend/security/src/main/java/com/example/security/KeycloakRoles.java`:

```java
package com.example.security;

import java.util.Collection;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

import org.springframework.security.oauth2.jwt.Jwt;

/** Reads Keycloak realm roles from the {@code realm_access.roles} claim of an access token. */
public final class KeycloakRoles {

	private KeycloakRoles() {
	}

	/** The realm roles in the token; empty when the claim is missing or not shaped as Keycloak writes it. */
	public static Set<String> of(Jwt jwt) {
		if (!(jwt.getClaims().get("realm_access") instanceof Map<?, ?> realmAccess)
				|| !(realmAccess.get("roles") instanceof Collection<?> roles)) {
			return Set.of();
		}
		return roles.stream()
				.filter(String.class::isInstance)
				.map(String.class::cast)
				.collect(Collectors.toUnmodifiableSet());
	}

}
```

`backend/security/src/main/java/com/example/security/KeycloakJwtAuthenticationConverter.java`:

```java
package com.example.security;

import java.util.List;

import org.springframework.core.convert.converter.Converter;
import org.springframework.security.authentication.AbstractAuthenticationToken;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/**
 * Builds the authentication for a Keycloak access token: authorities are permission codes (e.g. {@code task:write})
 * from the {@link PermissionResolver}, the name is {@code preferred_username}.
 */
public class KeycloakJwtAuthenticationConverter implements Converter<Jwt, AbstractAuthenticationToken> {

	private final PermissionResolver permissionResolver;

	public KeycloakJwtAuthenticationConverter(PermissionResolver permissionResolver) {
		this.permissionResolver = permissionResolver;
	}

	@Override
	public AbstractAuthenticationToken convert(Jwt jwt) {
		List<GrantedAuthority> authorities = permissionResolver.permissionsFor(jwt).stream()
				.sorted()
				.<GrantedAuthority>map(SimpleGrantedAuthority::new)
				.toList();
		String username = jwt.getClaimAsString("preferred_username");
		return new JwtAuthenticationToken(jwt, authorities, username != null ? username : jwt.getSubject());
	}

}
```

`backend/security/src/main/java/com/example/security/SecurityModule.java`:

```java
package com.example.security;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.ObjectProvider;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.security.config.annotation.method.configuration.EnableMethodSecurity;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.config.http.SessionCreationPolicy;
import org.springframework.security.web.SecurityFilterChain;

/**
 * Entry point of the shared resource-server wiring. Backend modules {@code @Import} it and protect their endpoints with
 * {@code @PreAuthorize("hasAuthority('<module>:<action>')")}.
 *
 * <p>Tokens are validated against {@code spring.security.oauth2.resourceserver.jwt.issuer-uri}. Permissions come from
 * the {@link PermissionResolver} bean of the deployment (the {@code iam} module in {@code backend/app}); without one,
 * every request is authenticated but has no permissions.
 */
@Configuration(proxyBeanMethods = false)
@EnableMethodSecurity
@ComponentScan(basePackageClasses = SecurityModule.class)
public class SecurityModule {

	private static final Logger log = LoggerFactory.getLogger(SecurityModule.class);

	@Bean
	KeycloakJwtAuthenticationConverter keycloakJwtAuthenticationConverter(ObjectProvider<PermissionResolver> resolvers) {
		return new KeycloakJwtAuthenticationConverter(resolvers.getIfAvailable(() -> {
			log.warn("No PermissionResolver bean: requests are authenticated but have no permissions");
			return PermissionResolver.denyAll();
		}));
	}

	@Bean
	SecurityFilterChain apiSecurity(HttpSecurity http, KeycloakJwtAuthenticationConverter converter) throws Exception {
		http.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**", "/actuator/info", "/v3/api-docs/**", "/swagger-ui/**",
								"/swagger-ui.html", "/error").permitAll()
						.anyRequest().authenticated())
				.oauth2ResourceServer(resourceServer -> resourceServer
						.jwt(jwt -> jwt.jwtAuthenticationConverter(converter)))
				.sessionManagement(session -> session.sessionCreationPolicy(SessionCreationPolicy.STATELESS))
				// Bearer tokens only: no cookies, so no CSRF exposure.
				.csrf(csrf -> csrf.disable());
		return http.build();
	}

}
```

`backend/security/src/main/java/com/example/security/AccessDeniedProblemHandler.java`:

```java
package com.example.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A missing permission ({@code @PreAuthorize} denial) is answered like every other error: an RFC 9457 problem. */
@RestControllerAdvice
class AccessDeniedProblemHandler {

	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail accessDenied(AccessDeniedException ex) {
		return ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
				"You do not have permission to perform this action");
	}

}
```

- [ ] **Step 5: Run the test to verify it passes**

Run: `./mvnw -q -pl backend/security -am test`
Expected: BUILD SUCCESS, `KeycloakJwtAuthenticationConverterTests` 5 tests passed.

- [ ] **Step 6: Commit**

```fish
git add pom.xml backend/pom.xml backend/security
git commit -m "Add backend security module with Keycloak JWT and PermissionResolver

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Enforce permissions in `backend/task`

**Files:**
- Modify: `backend/task/pom.xml`
- Modify: `backend/task/src/main/java/com/example/task/TaskModule.java`
- Modify: `backend/task/src/main/java/com/example/task/TaskController.java`
- Test: `backend/task/src/test/java/com/example/task/TaskApiTests.java`

**Interfaces:**
- Consumes: `SecurityModule` (Task 2).
- Produces: `GET /api/tasks`, `GET /api/tasks/{id}` need `task:read`; `POST`, `PUT /{id}` need `task:write`;
  `DELETE /{id}` needs `task:delete`.

- [ ] **Step 1: Add the test dependency**

In `backend/task/pom.xml`, after the `backend-common` dependency add:

```xml
		<dependency>
			<groupId>com.example</groupId>
			<artifactId>backend-security</artifactId>
		</dependency>
```

and after the `spring-boot-starter-webmvc-test` test dependency add:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Write the failing tests**

Replace `backend/task/src/test/java/com/example/task/TaskApiTests.java` with:

```java
package com.example.task;

import java.util.Arrays;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.SpringBootConfiguration;
import org.springframework.boot.autoconfigure.EnableAutoConfiguration;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Import;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

// The issuer is never contacted: jwt() below supplies the authentication, and Boot resolves the issuer lazily.
@SpringBootTest(classes = TaskApiTests.TestApp.class,
		properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test")
@AutoConfigureMockMvc
class TaskApiTests {

	/** Boots the task module on its own, the way a task-only microservice would. */
	@SpringBootConfiguration
	@EnableAutoConfiguration
	@Import({ TaskModule.class, TestcontainersConfiguration.class })
	static class TestApp {
	}

	private static final String READ = "task:read";
	private static final String WRITE = "task:write";
	private static final String DELETE = "task:delete";

	private static final String NEW_TASK = """
			{"title": "Write skeleton", "description": "ZK + Spring Boot", "done": false}
			""";

	@Autowired
	MockMvc mvc;

	/** A request carrying a valid token whose holder has exactly these permissions. */
	private static RequestPostProcessor as(String... permissions) {
		return jwt().authorities(Arrays.stream(permissions).map(SimpleGrantedAuthority::new)
				.toArray(GrantedAuthority[]::new));
	}

	private String createTask() throws Exception {
		return mvc.perform(post("/api/tasks").with(as(WRITE)).contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isCreated())
				.andReturn().getResponse().getHeader("Location");
	}

	@Test
	void createThenFetchThenDelete() throws Exception {
		String location = mvc.perform(post("/api/tasks").with(as(READ, WRITE, DELETE))
						.contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isCreated())
				.andExpect(header().exists("Location"))
				.andExpect(jsonPath("$.id", notNullValue()))
				.andExpect(jsonPath("$.createdAt", notNullValue()))
				.andReturn().getResponse().getHeader("Location");

		mvc.perform(get(location).with(as(READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Write skeleton"));

		mvc.perform(delete(location).with(as(DELETE))).andExpect(status().isNoContent());
		mvc.perform(get(location).with(as(READ))).andExpect(status().isNotFound());
	}

	@Test
	void rejectsBlankTitle() throws Exception {
		mvc.perform(post("/api/tasks").with(as(WRITE))
						.contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title": " ", "done": false}
								"""))
				.andExpect(status().isBadRequest());
	}

	@Test
	void requestsWithoutTokenAreUnauthorized() throws Exception {
		mvc.perform(get("/api/tasks"))
				.andExpect(status().isUnauthorized())
				.andExpect(header().string("WWW-Authenticate", containsString("Bearer")));
	}

	@Test
	void readerCanList() throws Exception {
		mvc.perform(get("/api/tasks").with(as(READ))).andExpect(status().isOk());
	}

	@Test
	void readerCannotChangeAnything() throws Exception {
		String location = createTask();

		mvc.perform(post("/api/tasks").with(as(READ)).contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isForbidden())
				.andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
				.andExpect(jsonPath("$.status").value(403));
		mvc.perform(put(location).with(as(READ)).contentType(MediaType.APPLICATION_JSON).content(NEW_TASK))
				.andExpect(status().isForbidden());
		mvc.perform(delete(location).with(as(READ))).andExpect(status().isForbidden());
		// Authorization comes before the lookup: a reader cannot probe which ids exist.
		mvc.perform(delete("/api/tasks/999999").with(as(READ))).andExpect(status().isForbidden());
	}

	@Test
	void editorCanCreateAndUpdateButNotDelete() throws Exception {
		String location = createTask();

		mvc.perform(put(location).with(as(READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
						.content("""
								{"title": "Renamed", "done": true}
								"""))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.title").value("Renamed"));
		mvc.perform(delete(location).with(as(READ, WRITE))).andExpect(status().isForbidden());
		mvc.perform(delete(location).with(as(DELETE))).andExpect(status().isNoContent());
	}

	@Test
	void writerWithoutReadCannotList() throws Exception {
		mvc.perform(get("/api/tasks").with(as(WRITE))).andExpect(status().isForbidden());
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/task -am test` (Docker reachable, see Prerequisites)
Expected: FAIL. With `backend-security` on the classpath but `SecurityModule` not imported, Boot's default
resource-server chain authenticates the `jwt()` requests but there is no method security, so
`readerCannotChangeAnything` gets 201 instead of 403, `editorCanCreateAndUpdateButNotDelete` gets 204 instead of 403
and `writerWithoutReadCannotList` gets 200 instead of 403.

- [ ] **Step 4: Import `SecurityModule` and annotate the controller**

In `backend/task/src/main/java/com/example/task/TaskModule.java`, add the import
`import com.example.security.SecurityModule;` and change the annotation to:

```java
@Import({ CommonModule.class, SecurityModule.class })
```

In `backend/task/src/main/java/com/example/task/TaskController.java`, add the import
`import org.springframework.security.access.prepost.PreAuthorize;` and annotate the methods:

```java
	@GetMapping
	@PreAuthorize("hasAuthority('task:read')")
	public List<TaskResponse> list() {
		return service.findAll();
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('task:read')")
	public TaskResponse get(@PathVariable Long id) {
		return service.findById(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('task:write')")
	public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskRequest request) {
		TaskResponse created = service.create(request);
		return ResponseEntity.created(URI.create("/api/tasks/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('task:write')")
	public TaskResponse update(@PathVariable Long id, @Valid @RequestBody TaskRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('task:delete')")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/task -am test`
Expected: BUILD SUCCESS, `TaskApiTests` 7 tests passed. The log shows the warning
`No PermissionResolver bean: requests are authenticated but have no permissions` (expected: the module runs alone).

- [ ] **Step 6: Commit**

```fish
git add backend/task
git commit -m "Protect task endpoints with task permissions

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: `backend/iam` module

**Files:**
- Create: `backend/iam/pom.xml`
- Create: `backend/iam/src/main/java/com/example/iam/IamModule.java`
- Create: `backend/iam/src/main/java/com/example/iam/RolePermissionRepository.java`
- Create: `backend/iam/src/main/java/com/example/iam/DbPermissionResolver.java`
- Create: `backend/iam/src/main/java/com/example/iam/IamController.java`
- Create: `backend/iam/src/main/java/com/example/iam/MeResponse.java`
- Create: `backend/iam/src/main/java/com/example/iam/RoleResponse.java`
- Create: `backend/iam/src/main/resources/db/migration/iam/V20261028_1__create_iam_tables.sql`
- Create: `backend/iam/src/main/resources/db/migration/iam/V20261028_2__seed_permissions.sql`
- Test: `backend/iam/src/test/java/com/example/iam/IamApiTests.java`
- Test: `backend/iam/src/test/java/com/example/iam/TestcontainersConfiguration.java`
- Modify: `pom.xml` (dependencyManagement), `backend/pom.xml` (modules)

**Interfaces:**
- Consumes: `PermissionResolver`, `KeycloakRoles`, `SecurityModule` (Task 2).
- Produces:
  - `public class IamModule` (`@Import(IamModule.class)` in an app).
  - `public class DbPermissionResolver implements PermissionResolver`; package-private
    `Set<String> permissionsForRoles(Set<String> roles)`.
  - `GET /api/iam/me` → `{"username": String, "name": String, "roles": [String], "permissions": [String]}` (lists
    sorted). Any valid token.
  - `GET /api/iam/roles` → `[{"role": String, "permissions": [String]}]` sorted by role. Needs `iam:read`.
  - Maven artifact `com.example:backend-iam`.

- [ ] **Step 1: Register the module and create the pom**

In `backend/pom.xml`, replace `<modules>` with:

```xml
	<modules>
		<module>common</module>
		<module>security</module>
		<module>iam</module>
		<module>task</module>
		<module>app</module>
	</modules>
```

In the root `pom.xml` `<dependencyManagement>`, after the `backend-security` entry, add:

```xml
			<dependency>
				<groupId>com.example</groupId>
				<artifactId>backend-iam</artifactId>
				<version>${project.version}</version>
			</dependency>
```

Create `backend/iam/pom.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<project xmlns="http://maven.apache.org/POM/4.0.0" xmlns:xsi="http://www.w3.org/2001/XMLSchema-instance"
	xsi:schemaLocation="http://maven.apache.org/POM/4.0.0 https://maven.apache.org/xsd/maven-4.0.0.xsd">
	<modelVersion>4.0.0</modelVersion>
	<parent>
		<groupId>com.example</groupId>
		<artifactId>backend</artifactId>
		<version>0.0.1-SNAPSHOT</version>
	</parent>

	<artifactId>backend-iam</artifactId>
	<name>backend-iam</name>
	<description>Identity and access: role to permission mappings, current-user API</description>

	<dependencies>
		<dependency>
			<groupId>com.example</groupId>
			<artifactId>backend-security</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-jdbc</artifactId>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway</artifactId>
		</dependency>
		<dependency>
			<groupId>com.github.ben-manes.caffeine</groupId>
			<artifactId>caffeine</artifactId>
		</dependency>

		<!-- The PostgreSQL driver and Flyway dialect come from the deployable app; tests need them here. -->
		<dependency>
			<groupId>org.postgresql</groupId>
			<artifactId>postgresql</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.flywaydb</groupId>
			<artifactId>flyway-database-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-jdbc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-flyway-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-resource-server-test</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-testcontainers</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-junit-jupiter</artifactId>
			<scope>test</scope>
		</dependency>
		<dependency>
			<groupId>org.testcontainers</groupId>
			<artifactId>testcontainers-postgresql</artifactId>
			<scope>test</scope>
		</dependency>
	</dependencies>
</project>
```

- [ ] **Step 2: Write the failing tests**

`backend/iam/src/test/java/com/example/iam/TestcontainersConfiguration.java`:

```java
package com.example.iam;

import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.context.annotation.Bean;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

@TestConfiguration(proxyBeanMethods = false)
class TestcontainersConfiguration {

	@Bean
	@ServiceConnection
	PostgreSQLContainer postgresContainer() {
		return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
	}

}
```

`backend/iam/src/test/java/com/example/iam/IamApiTests.java`:

```java
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
		properties = "spring.security.oauth2.resourceserver.jwt.issuer-uri=http://localhost:9/realms/test")
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
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/iam -am test`
Expected: compilation FAILURE, `cannot find symbol: class IamModule` / `DbPermissionResolver`.

- [ ] **Step 4: Write the migrations**

`backend/iam/src/main/resources/db/migration/iam/V20261028_1__create_iam_tables.sql`:

```sql
CREATE SCHEMA IF NOT EXISTS iam;

-- Permission codes are '<module>:<action>', e.g. 'task:read'. Modules add theirs with a migration in this folder.
CREATE TABLE iam.permission (
    code        VARCHAR(100) PRIMARY KEY,
    description VARCHAR(500) NOT NULL
);

-- Roles are Keycloak realm role names; there is no role table.
CREATE TABLE iam.role_permission (
    role            VARCHAR(100) NOT NULL,
    permission_code VARCHAR(100) NOT NULL REFERENCES iam.permission (code),
    PRIMARY KEY (role, permission_code)
);
```

`backend/iam/src/main/resources/db/migration/iam/V20261028_2__seed_permissions.sql`:

```sql
INSERT INTO iam.permission (code, description) VALUES
    ('task:read',   'List and view tasks'),
    ('task:write',  'Create and edit tasks'),
    ('task:delete', 'Delete tasks'),
    ('iam:read',    'View roles and their permissions');

INSERT INTO iam.role_permission (role, permission_code) VALUES
    ('VIEWER', 'task:read'),
    ('EDITOR', 'task:read'),
    ('EDITOR', 'task:write'),
    ('ADMIN',  'task:read'),
    ('ADMIN',  'task:write'),
    ('ADMIN',  'task:delete'),
    ('ADMIN',  'iam:read');
```

- [ ] **Step 5: Write the module code**

`backend/iam/src/main/java/com/example/iam/IamModule.java`:

```java
package com.example.iam;

import java.util.Arrays;

import org.flywaydb.core.api.Location;
import org.springframework.boot.flyway.autoconfigure.FlywayConfigurationCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.example.security.SecurityModule;

/**
 * Entry point of the identity-and-access context. A deployable app includes it with {@code @Import(IamModule.class)}.
 *
 * <p>Keycloak says who the user is and which realm roles they hold; this module owns which permissions each role
 * grants (Postgres schema {@code iam}, migrations in {@code db/migration/iam}) and provides the deployment's
 * {@link com.example.security.PermissionResolver}.
 */
@Configuration(proxyBeanMethods = false)
@Import(SecurityModule.class)
@ComponentScan(basePackageClasses = IamModule.class)
public class IamModule {

	@Bean
	FlywayConfigurationCustomizer iamMigrations() {
		return configuration -> {
			Location[] current = configuration.getLocations();
			Location[] all = Arrays.copyOf(current, current.length + 1);
			all[current.length] = new Location("classpath:db/migration/iam");
			configuration.locations(all);
		};
	}

}
```

`backend/iam/src/main/java/com/example/iam/RolePermissionRepository.java`:

```java
package com.example.iam;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.TreeMap;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Repository;

@Repository
class RolePermissionRepository {

	private final JdbcClient jdbc;

	RolePermissionRepository(JdbcClient jdbc) {
		this.jdbc = jdbc;
	}

	/** Union of the permissions of the given roles. */
	Set<String> permissionsFor(Set<String> roles) {
		if (roles.isEmpty()) {
			return Set.of(); // "IN ()" is not valid SQL
		}
		return Set.copyOf(jdbc.sql("SELECT DISTINCT permission_code FROM iam.role_permission WHERE role IN (:roles)")
				.param("roles", roles)
				.query(String.class)
				.list());
	}

	/** Every role that has at least one permission, with its permissions, both sorted. */
	Map<String, List<String>> permissionsByRole() {
		Map<String, List<String>> result = new TreeMap<>();
		jdbc.sql("SELECT role, permission_code FROM iam.role_permission ORDER BY role, permission_code")
				.query((rs, rowNum) -> Map.entry(rs.getString("role"), rs.getString("permission_code")))
				.list()
				.forEach(row -> result.computeIfAbsent(row.getKey(), role -> new ArrayList<>()).add(row.getValue()));
		return result;
	}

}
```

`backend/iam/src/main/java/com/example/iam/DbPermissionResolver.java`:

```java
package com.example.iam;

import java.time.Duration;
import java.util.Set;

import com.github.benmanes.caffeine.cache.Cache;
import com.github.benmanes.caffeine.cache.Caffeine;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.stereotype.Component;

import com.example.security.KeycloakRoles;
import com.example.security.PermissionResolver;

/**
 * Resolves permissions from the {@code iam} tables. Results are cached per role set for 60 seconds, so a changed
 * mapping reaches running instances within a minute without a restart.
 */
@Component
public class DbPermissionResolver implements PermissionResolver {

	private final RolePermissionRepository repository;

	private final Cache<Set<String>, Set<String>> cache = Caffeine.newBuilder()
			.expireAfterWrite(Duration.ofSeconds(60))
			.maximumSize(1_000)
			.build();

	DbPermissionResolver(RolePermissionRepository repository) {
		this.repository = repository;
	}

	@Override
	public Set<String> permissionsFor(Jwt jwt) {
		return permissionsForRoles(KeycloakRoles.of(jwt));
	}

	Set<String> permissionsForRoles(Set<String> roles) {
		// Set equality ignores order, so {EDITOR, VIEWER} and {VIEWER, EDITOR} share one entry.
		return cache.get(Set.copyOf(roles), repository::permissionsFor);
	}

}
```

`backend/iam/src/main/java/com/example/iam/MeResponse.java`:

```java
package com.example.iam;

import java.util.List;

public record MeResponse(String username, String name, List<String> roles, List<String> permissions) {
}
```

`backend/iam/src/main/java/com/example/iam/RoleResponse.java`:

```java
package com.example.iam;

import java.util.List;

public record RoleResponse(String role, List<String> permissions) {
}
```

`backend/iam/src/main/java/com/example/iam/IamController.java`:

```java
package com.example.iam;

import java.util.List;
import java.util.Objects;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.example.security.KeycloakRoles;

@RestController
@RequestMapping("/api/iam")
class IamController {

	private final RolePermissionRepository repository;

	IamController(RolePermissionRepository repository) {
		this.repository = repository;
	}

	/** Who the caller is and what they may do; the UI uses it to show or hide actions. */
	@GetMapping("/me")
	MeResponse me(JwtAuthenticationToken authentication) {
		Jwt jwt = authentication.getToken();
		String username = Objects.requireNonNullElse(jwt.getClaimAsString("preferred_username"), authentication.getName());
		String name = Objects.requireNonNullElse(jwt.getClaimAsString("name"), username);
		List<String> roles = KeycloakRoles.of(jwt).stream().sorted().toList();
		List<String> permissions = authentication.getAuthorities().stream()
				.map(GrantedAuthority::getAuthority)
				.sorted()
				.toList();
		return new MeResponse(username, name, roles, permissions);
	}

	@GetMapping("/roles")
	@PreAuthorize("hasAuthority('iam:read')")
	List<RoleResponse> roles() {
		return repository.permissionsByRole().entrySet().stream()
				.map(entry -> new RoleResponse(entry.getKey(), entry.getValue()))
				.toList();
	}

}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/iam -am test`
Expected: BUILD SUCCESS, `IamApiTests` 9 tests passed.

- [ ] **Step 7: Commit**

```fish
git add pom.xml backend/pom.xml backend/iam
git commit -m "Add iam module with role permissions, cached resolver and me endpoint

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Wire `iam` and the issuer into `backend/app`

**Files:**
- Modify: `backend/app/pom.xml`
- Modify: `backend/app/src/main/java/com/example/app/ApiApplication.java`
- Modify: `backend/app/src/main/resources/application.properties`
- Test: `backend/app/src/test/java/com/example/app/ApiApplicationTests.java`

**Interfaces:**
- Consumes: `IamModule`, `DbPermissionResolver` (Task 4); `PermissionResolver` (Task 2).
- Produces: `backend/app` reads `KEYCLOAK_ISSUER_URI`; health and Swagger public; `/api/**` needs a token.

- [ ] **Step 1: Add dependencies**

In `backend/app/pom.xml`, after the `backend-task` dependency add:

```xml
		<dependency>
			<groupId>com.example</groupId>
			<artifactId>backend-iam</artifactId>
		</dependency>
```

and after the `spring-boot-starter-actuator-test` test dependency add:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Write the failing tests**

Replace `backend/app/src/test/java/com/example/app/ApiApplicationTests.java` with:

```java
package com.example.app;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.testcontainers.service.connection.ServiceConnection;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.test.web.servlet.MockMvc;
import org.testcontainers.postgresql.PostgreSQLContainer;
import org.testcontainers.utility.DockerImageName;

import com.example.iam.DbPermissionResolver;
import com.example.security.PermissionResolver;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
@Import(ApiApplicationTests.Postgres.class)
class ApiApplicationTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class Postgres {

		@Bean
		@ServiceConnection
		PostgreSQLContainer postgres() {
			return new PostgreSQLContainer(DockerImageName.parse("postgres:17-alpine"));
		}

	}

	@Autowired
	MockMvc mvc;

	@Autowired
	PermissionResolver permissionResolver;

	@Test
	void permissionsComeFromIam() {
		assertThat(permissionResolver).isInstanceOf(DbPermissionResolver.class);
	}

	@Test
	void healthAndApiDocsArePublic() throws Exception {
		mvc.perform(get("/actuator/health")).andExpect(status().isOk());
		mvc.perform(get("/v3/api-docs")).andExpect(status().isOk());
	}

	@Test
	void apiNeedsAToken() throws Exception {
		mvc.perform(get("/api/tasks")).andExpect(status().isUnauthorized());
		mvc.perform(get("/api/iam/me")).andExpect(status().isUnauthorized());
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl backend/app -am test`
Expected: FAIL. Startup error `No qualifying bean of type 'com.example.security.PermissionResolver'` for
`permissionsComeFromIam` (IamModule not imported yet), or a missing `issuer-uri`.

- [ ] **Step 4: Import `IamModule` and configure the issuer**

`backend/app/src/main/java/com/example/app/ApiApplication.java`: add `import com.example.iam.IamModule;` and change
the import annotation to:

```java
@Import({ TaskModule.class, IamModule.class })
```

In `backend/app/src/main/resources/application.properties`, after the datasource lines add:

```properties

# Keycloak realm that issues the access tokens. Must equal the "iss" claim exactly (scheme, host, port).
spring.security.oauth2.resourceserver.jwt.issuer-uri=${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/zk-spring}
```

- [ ] **Step 5: Run the whole backend test suite**

Run: `./mvnw -q -pl backend/app -am verify`
Expected: BUILD SUCCESS; `ApiApplicationTests` 3, `IamApiTests` 9, `TaskApiTests` 7,
`KeycloakJwtAuthenticationConverterTests` 5 passed. The log shows Flyway applying `20261027.1` and `20261028.1`,
`20261028.2`.

- [ ] **Step 6: Commit**

```fish
git add backend/app
git commit -m "Deploy iam in backend app and validate tokens against Keycloak

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Frontend user model: `UiModule.requiredPermission`, `CurrentUser`, `ApiErrors`

**Files:**
- Modify: `frontend/common/pom.xml`
- Modify: `frontend/common/src/main/java/com/example/ui/common/UiModule.java`
- Create: `frontend/common/src/main/java/com/example/ui/common/MeDto.java`
- Create: `frontend/common/src/main/java/com/example/ui/common/IamApiClient.java`
- Create: `frontend/common/src/main/java/com/example/ui/common/CurrentUser.java`
- Create: `frontend/common/src/main/java/com/example/ui/common/ApiErrors.java`
- Test: `frontend/common/src/test/java/com/example/ui/common/UiModuleTests.java`
- Test: `frontend/common/src/test/java/com/example/ui/common/CurrentUserTests.java`
- Test: `frontend/common/src/test/java/com/example/ui/common/ApiErrorsTests.java`

**Interfaces:**
- Consumes: `GET /api/iam/me` JSON shape from Task 4.
- Produces:
  - `record UiModule(String id, String title, int order, String entry, String remoteUrl, String requiredPermission)`;
    `embedded(...)`/`remote(...)` unchanged signatures (permission `null`); `UiModule withPermission(String)`.
  - `record MeDto(String username, String name, List<String> roles, List<String> permissions)`.
  - `IamApiClient` (`@Component`, bean `iamApiClient`): `MeDto me()`, uses `ModuleRestClients.forModule("iam")`.
  - `CurrentUser` (`@Component @SessionScope`, bean `currentUser`): `MeDto me()`, `boolean can(String permission)`
    (`null` permission = allowed), `String getName()`. Failed fetches are not cached.
  - `ApiErrors`: `enum Kind { FORBIDDEN, REAUTHENTICATE, OTHER }`, `static Kind classify(RuntimeException)`,
    `static void show(RuntimeException)` (FORBIDDEN → warning notification; REAUTHENTICATE →
    `Executions.sendRedirect("/logout")`; OTHER → "API error: ..." notification).

- [ ] **Step 1: Add dependencies**

In `frontend/common/pom.xml`, after the `spring-boot-starter-restclient` dependency add:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-client</artifactId>
		</dependency>
```

and after the `zkbind` dependency add:

```xml

		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-client-test</artifactId>
			<scope>test</scope>
		</dependency>
```

Until Task 7 adds `UiSecurity`, the shell falls back to Spring Boot's default login form; that is expected between
tasks.

- [ ] **Step 2: Write the failing tests**

`frontend/common/src/test/java/com/example/ui/common/UiModuleTests.java`:

```java
package com.example.ui.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UiModuleTests {

	@Test
	void modulesNeedNoPermissionByDefault() {
		assertThat(UiModule.embedded("task", "Tasks", 10, "~./task/main.zul").requiredPermission()).isNull();
		assertThat(UiModule.remote("task", "Tasks", 10, "http://localhost:8083").requiredPermission()).isNull();
	}

	@Test
	void withPermissionKeepsEverythingElse() {
		UiModule module = UiModule.embedded("task", "Tasks", 10, "~./task/main.zul").withPermission("task:read");

		assertThat(module).isEqualTo(new UiModule("task", "Tasks", 10, "~./task/main.zul", null, "task:read"));
	}

}
```

`frontend/common/src/test/java/com/example/ui/common/CurrentUserTests.java`:

```java
package com.example.ui.common;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.web.client.ResourceAccessException;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.times;
import static org.mockito.Mockito.verify;
import static org.mockito.Mockito.verifyNoInteractions;
import static org.mockito.Mockito.when;

class CurrentUserTests {

	private static final MeDto EDITOR =
			new MeDto("editor", "Eddie Editor", List.of("EDITOR"), List.of("task:read", "task:write"));

	private final IamApiClient iamApiClient = mock(IamApiClient.class);

	private final CurrentUser currentUser = new CurrentUser(iamApiClient);

	@Test
	void checksPermissions() {
		when(iamApiClient.me()).thenReturn(EDITOR);

		assertThat(currentUser.can("task:write")).isTrue();
		assertThat(currentUser.can("task:delete")).isFalse();
		assertThat(currentUser.getName()).isEqualTo("Eddie Editor");
	}

	@Test
	void fetchesOncePerSession() {
		when(iamApiClient.me()).thenReturn(EDITOR);

		currentUser.can("task:read");
		currentUser.can("task:write");
		currentUser.getName();

		verify(iamApiClient, times(1)).me();
	}

	@Test
	void noRequiredPermissionIsAlwaysAllowed() {
		assertThat(currentUser.can(null)).isTrue();
		verifyNoInteractions(iamApiClient);
	}

	@Test
	void failedFetchIsNotCached() {
		when(iamApiClient.me()).thenThrow(new ResourceAccessException("Connection refused")).thenReturn(EDITOR);

		assertThatThrownBy(() -> currentUser.can("task:read")).isInstanceOf(ResourceAccessException.class);
		assertThat(currentUser.can("task:read")).isTrue();
	}

}
```

`frontend/common/src/test/java/com/example/ui/common/ApiErrorsTests.java`:

```java
package com.example.ui.common;

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

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl frontend/common -am test`
Expected: compilation FAILURE, `cannot find symbol: method requiredPermission()`, `class MeDto`, `class ApiErrors`.

- [ ] **Step 4: Write the implementation**

Replace `frontend/common/src/main/java/com/example/ui/common/UiModule.java` with:

```java
package com.example.ui.common;

/**
 * What a UI module tells the shell about itself. Each UI module contributes one bean of this type.
 *
 * <ul>
 * <li>{@code entry}: ZUL page rendered inside the shell, e.g. {@code ~./task/main.zul} (module is in the same app).</li>
 * <li>{@code remoteUrl}: URL of a module deployed as its own app, shown in an iframe (module was split out).</li>
 * <li>{@code requiredPermission}: permission the user needs to see the module in the navigation, or {@code null}.
 * Hiding is for clarity only; the backend enforces permissions on every call.</li>
 * </ul>
 */
public record UiModule(String id, String title, int order, String entry, String remoteUrl, String requiredPermission) {

	public static UiModule embedded(String id, String title, int order, String entry) {
		return new UiModule(id, title, order, entry, null, null);
	}

	public static UiModule remote(String id, String title, int order, String remoteUrl) {
		return new UiModule(id, title, order, null, remoteUrl, null);
	}

	public UiModule withPermission(String permission) {
		return new UiModule(id, title, order, entry, remoteUrl, permission);
	}

	// JavaBean getters: ZK's EL cannot read record accessors like title().

	public String getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public boolean isRemote() {
		return remoteUrl != null;
	}

}
```

`frontend/common/src/main/java/com/example/ui/common/MeDto.java`:

```java
package com.example.ui.common;

import java.util.List;

/** The signed-in user as returned by {@code GET /api/iam/me}. A UI-side copy: UI code never imports backend classes. */
public record MeDto(String username, String name, List<String> roles, List<String> permissions) {
}
```

`frontend/common/src/main/java/com/example/ui/common/IamApiClient.java`:

```java
package com.example.ui.common;

import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/** Client for the {@code iam} API; its base URL can be pointed elsewhere with {@code app.modules.iam.api-base-url}. */
@Component
public class IamApiClient {

	static final String MODULE_ID = "iam";

	private final RestClient client;

	public IamApiClient(ModuleRestClients clients) {
		this.client = clients.forModule(MODULE_ID);
	}

	public MeDto me() {
		return client.get().uri("/api/iam/me").retrieve().body(MeDto.class);
	}

}
```

`frontend/common/src/main/java/com/example/ui/common/CurrentUser.java`:

```java
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
```

`frontend/common/src/main/java/com/example/ui/common/ApiErrors.java`:

```java
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
```

- [ ] **Step 5: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/common -am test`
Expected: BUILD SUCCESS; `UiModuleTests` 2, `CurrentUserTests` 4, `ApiErrorsTests` 3 passed.

- [ ] **Step 6: Make sure the rest of the frontend still compiles**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: BUILD SUCCESS (`ShellApplicationTests` passes; `UiModule.embedded` keeps its signature, so `TaskUiModule`
compiles unchanged).

- [ ] **Step 7: Commit**

```fish
git add frontend/common
git commit -m "Add CurrentUser, ApiErrors and required permissions for UI modules

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Shell login through Keycloak and bearer relay

**Files:**
- Create: `frontend/common/src/main/java/com/example/ui/common/UiSecurity.java`
- Create: `frontend/common/src/main/java/com/example/ui/common/KeycloakLogoutSuccessHandler.java`
- Modify: `frontend/common/src/main/java/com/example/ui/common/ModuleRestClients.java`
- Create: `frontend/common/src/main/resources/metainfo/zk/zk.xml`
- Test: `frontend/common/src/test/java/com/example/ui/common/KeycloakLogoutSuccessHandlerTests.java`
- Test: `frontend/common/src/test/java/com/example/ui/common/ModuleRestClientsTests.java`
- Modify: `frontend/shell/pom.xml`
- Modify: `frontend/shell/src/main/resources/application.properties`
- Test: `frontend/shell/src/test/java/com/example/shell/ShellSecurityTests.java`

**Interfaces:**
- Consumes: Keycloak realm/client from Task 1.
- Produces:
  - `UiSecurity.REGISTRATION_ID = "keycloak"`; beans `SecurityFilterChain uiSecurity`,
    `OAuth2AuthorizedClientRepository` (HttpSession), `OAuth2AuthorizedClientManager` (authorization code + refresh
    token), `KeycloakLogoutSuccessHandler`.
  - `new KeycloakLogoutSuccessHandler(String issuerUri)`.
  - `ModuleRestClients(RestClient.Builder, Environment, OAuth2AuthorizedClientManager)`; `RestClient forModule(String)`
    sends `Authorization: Bearer <access token of the signed-in user>`.
  - Shell property `app.keycloak.issuer-uri`.

- [ ] **Step 1: Add the shell test dependency**

In `frontend/shell/pom.xml`, after the `spring-boot-starter-webmvc-test` dependency add:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-security-oauth2-client-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 2: Write the failing tests**

`frontend/common/src/test/java/com/example/ui/common/KeycloakLogoutSuccessHandlerTests.java`:

```java
package com.example.ui.common;

import java.util.List;

import org.junit.jupiter.api.Test;
import org.springframework.mock.web.MockHttpServletRequest;
import org.springframework.mock.web.MockHttpServletResponse;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.oauth2.core.oidc.OidcIdToken;
import org.springframework.security.oauth2.core.oidc.user.DefaultOidcUser;
import org.springframework.util.MultiValueMap;
import org.springframework.web.util.UriComponents;
import org.springframework.web.util.UriComponentsBuilder;

import static org.assertj.core.api.Assertions.assertThat;

class KeycloakLogoutSuccessHandlerTests {

	private final KeycloakLogoutSuccessHandler handler =
			new KeycloakLogoutSuccessHandler("http://localhost:8180/realms/zk-spring");

	private static MockHttpServletRequest request() {
		MockHttpServletRequest request = new MockHttpServletRequest("GET", "/logout");
		request.setServerPort(8081);
		return request;
	}

	@Test
	void endsTheKeycloakSessionAndComesBackHome() throws Exception {
		var idToken = OidcIdToken.withTokenValue("id-token-value").claim("sub", "u-1").build();
		var authentication = new TestingAuthenticationToken(new DefaultOidcUser(List.of(), idToken), null);
		var response = new MockHttpServletResponse();

		handler.onLogoutSuccess(request(), response, authentication);

		UriComponents redirect = UriComponentsBuilder.fromUriString(response.getRedirectedUrl()).build();
		assertThat(redirect.toUriString())
				.startsWith("http://localhost:8180/realms/zk-spring/protocol/openid-connect/logout?");
		MultiValueMap<String, String> query = redirect.getQueryParams();
		assertThat(query.getFirst("id_token_hint")).isEqualTo("id-token-value");
		assertThat(query.getFirst("post_logout_redirect_uri")).isEqualTo("http://localhost:8081/");
	}

	@Test
	void withoutAnOidcLoginItJustGoesHome() throws Exception {
		var response = new MockHttpServletResponse();

		handler.onLogoutSuccess(request(), response, null);

		assertThat(response.getRedirectedUrl()).isEqualTo("http://localhost:8081/");
	}

}
```

`frontend/common/src/test/java/com/example/ui/common/ModuleRestClientsTests.java`:

```java
package com.example.ui.common;

import java.time.Instant;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.mock.env.MockEnvironment;
import org.springframework.security.authentication.TestingAuthenticationToken;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClient;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.registration.ClientRegistration;
import org.springframework.security.oauth2.core.AuthorizationGrantType;
import org.springframework.security.oauth2.core.OAuth2AccessToken;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import static org.springframework.test.web.client.match.MockRestRequestMatchers.header;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class ModuleRestClientsTests {

	private static final ClientRegistration KEYCLOAK = ClientRegistration.withRegistrationId(UiSecurity.REGISTRATION_ID)
			.clientId("zk-shell")
			.authorizationGrantType(AuthorizationGrantType.AUTHORIZATION_CODE)
			.redirectUri("{baseUrl}/login/oauth2/code/{registrationId}")
			.authorizationUri("http://kc/auth")
			.tokenUri("http://kc/token")
			.build();

	/** Stands in for Spring's manager: hands out a fixed access token for whoever is signed in. */
	private final OAuth2AuthorizedClientManager authorizedClients = request -> new OAuth2AuthorizedClient(KEYCLOAK,
			request.getPrincipal().getName(), new OAuth2AccessToken(OAuth2AccessToken.TokenType.BEARER,
					"access-token-value", Instant.now(), Instant.now().plusSeconds(300)));

	private final RestClient.Builder builder = RestClient.builder();

	private final MockRestServiceServer server = MockRestServiceServer.bindTo(builder).build();

	@BeforeEach
	void signIn() {
		SecurityContextHolder.getContext().setAuthentication(new TestingAuthenticationToken("editor", null, "USER"));
	}

	@AfterEach
	void signOut() {
		SecurityContextHolder.clearContext();
	}

	@Test
	void callsTheApiAsTheSignedInUser() {
		var environment = new MockEnvironment().withProperty("app.api.base-url", "http://api");
		server.expect(requestTo("http://api/api/tasks"))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token-value"))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		new ModuleRestClients(builder, environment, authorizedClients)
				.forModule("task").get().uri("/api/tasks").retrieve().toBodilessEntity();

		server.verify();
	}

	@Test
	void aModuleCanHaveItsOwnBaseUrl() {
		var environment = new MockEnvironment().withProperty("app.api.base-url", "http://api")
				.withProperty("app.modules.task.api-base-url", "http://task-service");
		server.expect(requestTo("http://task-service/api/tasks"))
				.andExpect(header(HttpHeaders.AUTHORIZATION, "Bearer access-token-value"))
				.andRespond(withSuccess("[]", MediaType.APPLICATION_JSON));

		new ModuleRestClients(builder, environment, authorizedClients)
				.forModule("task").get().uri("/api/tasks").retrieve().toBodilessEntity();

		server.verify();
	}

}
```

`frontend/shell/src/test/java/com/example/shell/ShellSecurityTests.java`:

```java
package com.example.shell;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.test.web.servlet.MockMvc;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.oidcLogin;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.redirectedUrlPattern;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

@SpringBootTest
@AutoConfigureMockMvc
class ShellSecurityTests {

	@Autowired
	MockMvc mvc;

	@Test
	void pagesRequireLogin() throws Exception {
		mvc.perform(get("/"))
				.andExpect(status().isFound())
				.andExpect(redirectedUrlPattern("**/oauth2/authorization/keycloak"));
	}

	@Test
	void loginGoesToKeycloakWithoutDiscovery() throws Exception {
		mvc.perform(get("/oauth2/authorization/keycloak"))
				.andExpect(status().isFound())
				.andExpect(header().string("Location",
						startsWith("http://localhost:8180/realms/zk-spring/protocol/openid-connect/auth?")));
	}

	@Test
	void zkAjaxRequestsAreNotRedirectedToLogin() throws Exception {
		// MockMvc does not run ZK's servlets, so whatever MVC answers is fine as long as security neither redirected
		// to Keycloak (ZK cannot render that HTML) nor rejected the POST for a missing CSRF token.
		int status = mvc.perform(post("/zkau")).andReturn().getResponse().getStatus();

		assertThat(status).isNotIn(302, 401, 403);
	}

	@Test
	void logoutAlsoEndsTheKeycloakSession() throws Exception {
		mvc.perform(get("/logout").with(oidcLogin().idToken(token -> token.tokenValue("id-token-value"))))
				.andExpect(status().isFound())
				.andExpect(header().string("Location", startsWith(
						"http://localhost:8180/realms/zk-spring/protocol/openid-connect/logout?id_token_hint=id-token-value")));
	}

	@Test
	void framingIsAllowedFromTheSameOriginOnly() throws Exception {
		mvc.perform(get("/actuator/health"))
				.andExpect(status().isOk())
				.andExpect(header().string("X-Frame-Options", "SAMEORIGIN"));
	}

}
```

- [ ] **Step 3: Run the tests to verify they fail**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: compilation FAILURE in `frontend/common`: `cannot find symbol: class KeycloakLogoutSuccessHandler`,
`variable UiSecurity`, and `ModuleRestClients` constructor arity.

- [ ] **Step 4: Write `UiSecurity` and the logout handler**

`frontend/common/src/main/java/com/example/ui/common/UiSecurity.java`:

```java
package com.example.ui.common;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.http.HttpMethod;
import org.springframework.security.config.Customizer;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientProviderBuilder;
import org.springframework.security.oauth2.client.registration.ClientRegistrationRepository;
import org.springframework.security.oauth2.client.web.DefaultOAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.HttpSessionOAuth2AuthorizedClientRepository;
import org.springframework.security.oauth2.client.web.OAuth2AuthorizedClientRepository;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.security.web.servlet.util.matcher.PathPatternRequestMatcher;

/**
 * Login for every ZK app: OAuth2 authorization code flow against Keycloak. Picked up by {@link ZkSupport}'s component
 * scan, so the shell and any split-out UI app are protected the same way.
 *
 * <p>Access and refresh tokens stay in the HttpSession; the browser only holds the session cookie.
 */
@Configuration(proxyBeanMethods = false)
public class UiSecurity {

	/** The single client registration ({@code spring.security.oauth2.client.registration.keycloak.*}). */
	public static final String REGISTRATION_ID = "keycloak";

	@Bean
	SecurityFilterChain uiSecurity(HttpSecurity http, KeycloakLogoutSuccessHandler logoutSuccessHandler)
			throws Exception {
		http.authorizeHttpRequests(auth -> auth
						.requestMatchers("/actuator/health/**").permitAll()
						// ZK's AJAX endpoint. Without a session there is no ZK desktop, so ZK answers with its own
						// session-timeout handling (zk.xml timeout-uri) instead of the browser getting Keycloak's HTML.
						.requestMatchers(HttpMethod.POST, "/zkau").permitAll()
						.anyRequest().authenticated())
				.oauth2Login(Customizer.withDefaults())
				.logout(logout -> logout
						.logoutRequestMatcher(PathPatternRequestMatcher.withDefaults().matcher(HttpMethod.GET, "/logout"))
						.logoutSuccessHandler(logoutSuccessHandler))
				// ZK ties every AJAX request to a desktop in the user's session; Spring's CSRF token would reject them.
				.csrf(csrf -> csrf.ignoringRequestMatchers("/zkau", "/zkau/**"))
				.headers(headers -> headers.frameOptions(frame -> frame.sameOrigin()));
		return http.build();
	}

	@Bean
	OAuth2AuthorizedClientRepository authorizedClientRepository() {
		return new HttpSessionOAuth2AuthorizedClientRepository();
	}

	/** Gives {@link ModuleRestClients} a current access token, refreshing it with the refresh token when expired. */
	@Bean
	OAuth2AuthorizedClientManager authorizedClientManager(ClientRegistrationRepository registrations,
			OAuth2AuthorizedClientRepository authorizedClients) {
		var manager = new DefaultOAuth2AuthorizedClientManager(registrations, authorizedClients);
		manager.setAuthorizedClientProvider(
				OAuth2AuthorizedClientProviderBuilder.builder().authorizationCode().refreshToken().build());
		return manager;
	}

	@Bean
	KeycloakLogoutSuccessHandler keycloakLogoutSuccessHandler(@Value("${app.keycloak.issuer-uri}") String issuerUri) {
		return new KeycloakLogoutSuccessHandler(issuerUri);
	}

}
```

`frontend/common/src/main/java/com/example/ui/common/KeycloakLogoutSuccessHandler.java`:

```java
package com.example.ui.common;

import java.io.IOException;

import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.security.core.Authentication;
import org.springframework.security.oauth2.core.oidc.user.OidcUser;
import org.springframework.security.web.authentication.logout.LogoutSuccessHandler;
import org.springframework.web.servlet.support.ServletUriComponentsBuilder;
import org.springframework.web.util.UriComponentsBuilder;

/**
 * After the local session is gone, ends the Keycloak session too (OIDC RP-initiated logout), so the next visit shows
 * the login page instead of signing the user straight back in.
 *
 * <p>Built from the issuer URI rather than OIDC discovery, so the app starts while Keycloak is down.
 */
public class KeycloakLogoutSuccessHandler implements LogoutSuccessHandler {

	private final String issuerUri;

	public KeycloakLogoutSuccessHandler(String issuerUri) {
		this.issuerUri = issuerUri;
	}

	@Override
	public void onLogoutSuccess(HttpServletRequest request, HttpServletResponse response,
			Authentication authentication) throws IOException {
		String home = ServletUriComponentsBuilder.fromContextPath(request).path("/").toUriString();
		if (authentication == null || !(authentication.getPrincipal() instanceof OidcUser user)) {
			response.sendRedirect(home);
			return;
		}
		String keycloakLogout = UriComponentsBuilder.fromUriString(issuerUri)
				.path("/protocol/openid-connect/logout")
				.queryParam("id_token_hint", user.getIdToken().getTokenValue())
				.queryParam("post_logout_redirect_uri", home)
				.encode()
				.toUriString();
		response.sendRedirect(keycloakLogout);
	}

}
```

- [ ] **Step 5: Relay the bearer token in `ModuleRestClients`**

Replace `frontend/common/src/main/java/com/example/ui/common/ModuleRestClients.java` with:

```java
package com.example.ui.common;

import org.springframework.core.env.Environment;
import org.springframework.security.oauth2.client.OAuth2AuthorizedClientManager;
import org.springframework.security.oauth2.client.web.client.OAuth2ClientHttpRequestInterceptor;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

/**
 * Builds the {@link RestClient} a UI module uses to reach its backend. The base URL is looked up per module
 * ({@code app.modules.<id>.api-base-url}) and falls back to {@code app.api.base-url}, so pointing one module at its own
 * microservice is a configuration change only.
 *
 * <p>Every client calls the API as the signed-in user: it sends that user's Keycloak access token as a bearer token
 * and refreshes it when it has expired. This works because ZK runs view model commands on the servlet request thread,
 * where the user's security context and session are available.
 */
@Component
public class ModuleRestClients {

	private static final String DEFAULT_BASE_URL = "http://localhost:8080";

	private final RestClient.Builder builder;

	private final Environment environment;

	public ModuleRestClients(RestClient.Builder builder, Environment environment,
			OAuth2AuthorizedClientManager authorizedClients) {
		OAuth2ClientHttpRequestInterceptor bearerToken = new OAuth2ClientHttpRequestInterceptor(authorizedClients);
		bearerToken.setClientRegistrationIdResolver(request -> UiSecurity.REGISTRATION_ID);
		this.builder = builder.clone().requestInterceptor(bearerToken);
		this.environment = environment;
	}

	public RestClient forModule(String moduleId) {
		String fallback = environment.getProperty("app.api.base-url", DEFAULT_BASE_URL);
		String baseUrl = environment.getProperty("app.modules." + moduleId + ".api-base-url", fallback);
		return builder.clone().baseUrl(baseUrl).build();
	}

}
```

- [ ] **Step 6: Point ZK's session timeout at the home page**

`frontend/common/src/main/resources/metainfo/zk/zk.xml`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<!-- Merged into the configuration of every ZK app that has frontend-common on its classpath. -->
<zk>
	<!-- When the HTTP session has expired, reload the home page; that goes through Keycloak login again. -->
	<session-config>
		<device-type>ajax</device-type>
		<timeout-uri>/</timeout-uri>
	</session-config>
</zk>
```

- [ ] **Step 7: Configure the Keycloak client in the shell**

Replace `frontend/shell/src/main/resources/application.properties` with:

```properties
spring.application.name=shell
server.port=8081

# Default backend for every UI module; override one module with app.modules.<id>.api-base-url
app.api.base-url=${API_BASE_URL:http://localhost:8080}
# app.modules.task.api-base-url=http://localhost:8082

# Keycloak realm. Endpoints are spelled out instead of discovered, so the shell starts while Keycloak is down.
app.keycloak.issuer-uri=${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/zk-spring}
spring.security.oauth2.client.registration.keycloak.client-id=${KEYCLOAK_CLIENT_ID:zk-shell}
spring.security.oauth2.client.registration.keycloak.client-secret=${KEYCLOAK_CLIENT_SECRET:zk-shell-dev-secret}
spring.security.oauth2.client.registration.keycloak.authorization-grant-type=authorization_code
spring.security.oauth2.client.registration.keycloak.scope=openid,profile,email
spring.security.oauth2.client.provider.keycloak.authorization-uri=${app.keycloak.issuer-uri}/protocol/openid-connect/auth
spring.security.oauth2.client.provider.keycloak.token-uri=${app.keycloak.issuer-uri}/protocol/openid-connect/token
spring.security.oauth2.client.provider.keycloak.jwk-set-uri=${app.keycloak.issuer-uri}/protocol/openid-connect/certs
spring.security.oauth2.client.provider.keycloak.user-info-uri=${app.keycloak.issuer-uri}/protocol/openid-connect/userinfo
spring.security.oauth2.client.provider.keycloak.user-name-attribute=preferred_username

management.endpoints.web.exposure.include=health,info
```

- [ ] **Step 8: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: BUILD SUCCESS; in `frontend/common` `KeycloakLogoutSuccessHandlerTests` 2 and `ModuleRestClientsTests` 2
pass (plus Task 6's tests); in `frontend/shell` `ShellSecurityTests` 5 and `ShellApplicationTests` 1 pass.

- [ ] **Step 9: Commit**

```fish
git add frontend/common frontend/shell/pom.xml frontend/shell/src/main/resources/application.properties frontend/shell/src/test
git commit -m "Log in to the ZK shell with Keycloak and relay bearer tokens to the API

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Permission-aware shell and task UI, end-to-end check

**Files:**
- Modify: `frontend/shell/src/main/java/com/example/shell/ShellViewModel.java`
- Modify: `frontend/shell/src/main/resources/web/shell/index.zul`
- Modify: `frontend/shell/src/main/resources/web/shell/app.css`
- Modify: `frontend/task/src/main/java/com/example/ui/task/TaskUiModule.java`
- Modify: `frontend/task/src/main/java/com/example/ui/task/TaskViewModel.java`
- Modify: `frontend/task/src/main/resources/web/task/main.zul`

**Interfaces:**
- Consumes: `CurrentUser` (bean `currentUser`), `ApiErrors`, `UiModule.withPermission` (Task 6); login and token relay
  (Task 7); backend (Tasks 3–5); Keycloak (Task 1).
- Produces: `ShellViewModel.getUserName()`; `TaskViewModel.isCanWrite()`, `isCanDelete()` (ZUL: `vm.canWrite`,
  `vm.canDelete`).

The project's frontend tests are context-load only (see `CLAUDE.md`); view model behaviour is verified by the manual
checklist in Step 6, and the logic it relies on (`CurrentUser`, `ApiErrors`) is unit tested in Task 6.

- [ ] **Step 1: Filter the shell navigation and show the user**

Replace `frontend/shell/src/main/java/com/example/shell/ShellViewModel.java` with:

```java
package com.example.shell;

import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.ModuleRegistry;
import com.example.ui.common.SpringBeanVariableResolver;
import com.example.ui.common.UiModule;

/** Navigation between the UI modules registered in this deployment that the signed-in user may open. */
@VariableResolver(SpringBeanVariableResolver.class)
public class ShellViewModel {

	@WireVariable
	private ModuleRegistry moduleRegistry;

	@WireVariable
	private CurrentUser currentUser;

	private List<UiModule> modules = List.of();

	private String userName = "";

	private UiModule current;

	@Init
	public void init() {
		try {
			modules = moduleRegistry.modules().stream()
					.filter(module -> currentUser.can(module.requiredPermission()))
					.toList();
			userName = currentUser.getName();
		}
		catch (RuntimeException ex) {
			// Backend unreachable or login gone: render an empty shell and tell the user.
			ApiErrors.show(ex);
		}
		current = modules.isEmpty() ? null : modules.get(0);
	}

	@Command
	@NotifyChange({ "current", "entry", "remoteUrl" })
	public void open(@BindingParam("module") UiModule module) {
		current = module;
	}

	public List<UiModule> getModules() {
		return modules;
	}

	public String getUserName() {
		return userName;
	}

	public UiModule getCurrent() {
		return current;
	}

	public String getEntry() {
		return current == null || current.isRemote() ? null : current.entry();
	}

	public String getRemoteUrl() {
		return current == null ? null : current.remoteUrl();
	}

}
```

In `frontend/shell/src/main/resources/web/shell/index.zul`, insert after the closing `</hlayout>` (still inside
`shell-header`):

```xml
			<div sclass="shell-user">
				<label value="@load(vm.userName)"/>
				<a href="/logout" label="Log out"/>
			</div>
```

Append to `frontend/shell/src/main/resources/web/shell/app.css`:

```css
.shell-user { margin-left: auto; display: flex; align-items: center; gap: 12px; }
```

- [ ] **Step 2: Require `task:read` for the task module**

In `frontend/task/src/main/java/com/example/ui/task/TaskUiModule.java`, change the bean to:

```java
	@Bean
	UiModule taskUi() {
		return UiModule.embedded(ID, "Tasks", 10, "~./task/main.zul").withPermission("task:read");
	}
```

- [ ] **Step 3: Make the task view model permission-aware**

Replace `frontend/task/src/main/java/com/example/ui/task/TaskViewModel.java` with:

```java
package com.example.ui.task;

import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind main.zul. Controls the user may not use are hidden; the backend enforces regardless. */
@VariableResolver(SpringBeanVariableResolver.class)
public class TaskViewModel {

	static final String WRITE = "task:write";
	static final String DELETE = "task:delete";

	@WireVariable
	private TaskApiClient taskApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private List<TaskDto> tasks = List.of();
	private Long editingId;
	private String title = "";
	private String description = "";
	private boolean done;
	private boolean canWrite;
	private boolean canDelete;

	@Init
	public void init() {
		try {
			canWrite = currentUser.can(WRITE);
			canDelete = currentUser.can(DELETE);
		}
		catch (RuntimeException ex) {
			// The shell has already reported this failure; stay read-only and let reload() show the API state.
		}
		reload();
	}

	@Command
	@NotifyChange({ "tasks", "editingId", "title", "description", "done" })
	public void save() {
		if (title == null || title.isBlank()) {
			Clients.showNotification("Title is required", "warning", null, "end_center", 2000);
			return;
		}
		TaskRequest request = new TaskRequest(title.trim(), description, done);
		try {
			if (editingId == null) {
				taskApiClient.create(request);
			}
			else {
				taskApiClient.update(editingId, request);
			}
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			return;
		}
		clearForm();
		reload();
	}

	@Command
	@NotifyChange({ "editingId", "title", "description", "done" })
	public void edit(@BindingParam("task") TaskDto task) {
		editingId = task.id();
		title = task.title();
		description = task.description();
		done = task.done();
	}

	@Command
	@NotifyChange({ "editingId", "title", "description", "done" })
	public void cancel() {
		clearForm();
	}

	@Command
	@NotifyChange({ "tasks", "editingId", "title", "description", "done" })
	public void delete(@BindingParam("task") TaskDto task) {
		try {
			taskApiClient.delete(task.id());
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			return;
		}
		if (task.id().equals(editingId)) {
			clearForm();
		}
		reload();
	}

	@Command
	@NotifyChange("tasks")
	public void toggle(@BindingParam("task") TaskDto task) {
		try {
			taskApiClient.update(task.id(), new TaskRequest(task.title(), task.description(), !task.done()));
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			return;
		}
		reload();
	}

	private void reload() {
		try {
			tasks = taskApiClient.list();
		}
		catch (RuntimeException ex) {
			tasks = List.of();
			ApiErrors.show(ex);
		}
	}

	private void clearForm() {
		editingId = null;
		title = "";
		description = "";
		done = false;
	}

	public List<TaskDto> getTasks() {
		return tasks;
	}

	public Long getEditingId() {
		return editingId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isDone() {
		return done;
	}

	public void setDone(boolean done) {
		this.done = done;
	}

	public boolean isCanWrite() {
		return canWrite;
	}

	public boolean isCanDelete() {
		return canDelete;
	}

}
```

In `frontend/task/src/main/resources/web/task/main.zul` make four edits:

1. The form groupbox: `<groupbox mold="3d" sclass="card">` becomes
   `<groupbox mold="3d" sclass="card" visible="@load(vm.canWrite)">`
2. The done checkbox in the list:

```xml
						<checkbox checked="@load(task.done)" disabled="@load(not vm.canWrite)"
							onCheck="@command('toggle', task=task)"/>
```

3. and 4. The row buttons:

```xml
						<button label="Edit" visible="@load(vm.canWrite)" onClick="@command('edit', task=task)"/>
						<button label="Delete" visible="@load(vm.canDelete)" onClick="@command('delete', task=task)"/>
```

- [ ] **Step 4: Build the frontend**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Install and start everything**

```fish
./mvnw -q install -DskipTests
# terminal 1
env DB_URL=jdbc:postgresql://<homelab-ip>:5432/app KEYCLOAK_ISSUER_URI=http://<homelab-ip>:8180/realms/zk-spring \
    ./mvnw -pl backend/app spring-boot:run
# terminal 2
env KEYCLOAK_ISSUER_URI=http://<homelab-ip>:8180/realms/zk-spring ./mvnw -pl frontend/shell spring-boot:run
```

Expected: the backend log shows Flyway applying `20261028.1` and `20261028.2` to the home-lab database.

- [ ] **Step 6: Manual end-to-end checklist** (browser at `http://localhost:8081`; use a private window per user)

| # | Action | Expected |
|---|---|---|
| 1 | Open `http://localhost:8081` | Keycloak login page of realm `zk-spring` |
| 2 | Log in as `viewer`/`viewer` | Header shows "Vera Viewer"; "Tasks" in nav; list visible; no form, no Edit/Delete, checkboxes disabled |
| 3 | Log out | Back on the Keycloak login page (not signed in again automatically) |
| 4 | Log in as `editor`/`editor` | Form and Edit visible, no Delete; creating and editing a task works |
| 5 | Log in as `admin`/`admin` | Everything visible; delete works |
| 6 | As `editor`, in a second tab call `curl -i http://localhost:8080/api/tasks` | `401` with `WWW-Authenticate: Bearer` (no token) |
| 7 | Stop the backend, reload the shell | Page renders with an "API error" notification, no stack trace; start the backend and reload, it works again |
| 8 | Restart the shell with `env SERVER_SERVLET_SESSION_TIMEOUT=1m ...`, log in, wait 2 minutes, click a task's Edit | Page reloads through login (Keycloak may sign you straight back in); no raw HTML or error dialog |
| 9 | In Keycloak admin (`http://<homelab-ip>:8180`, `admin`/`admin`) end the `editor` session, then let the access token expire (5 min) and click Save | Sent to `/logout`, then the login page |

Fix anything that fails before committing.

- [ ] **Step 7: Commit**

```fish
git add frontend/shell frontend/task
git commit -m "Show only the modules and task actions the user is permitted

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 9: Documentation and final verification

**Files:**
- Modify: `README.md`
- Modify: `CLAUDE.md`
- Modify: `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md`

- [ ] **Step 1: README: layout, run, demo users**

In `README.md`, in the Layout code block, replace the `backend/` and `frontend/common` lines with:

```
backend/
  common/   shared kernel: error handling only, no domain code
  security/ shared resource-server wiring: JWT validation, PermissionResolver, method security
  iam/      "iam" bounded context: role -> permission mappings (own schema), /api/iam/me
  task/     "task" bounded context: controller, service, entity, own Postgres schema + migrations
  app/      Spring Boot app (port 8080): imports the context modules it deploys
frontend/
  common/   ZK servlet wiring, Keycloak login, UiModule contract, per-module REST client factory (bearer relay)
```

Replace the `## Run` section's code block and the sentence after it with:

````markdown
```
docker compose up -d                                  # PostgreSQL :5432, Keycloak :8180 (realm zk-spring)
./mvnw -pl backend/app spring-boot:run                # API  http://localhost:8080  (Swagger UI: /swagger-ui.html)
./mvnw -pl frontend/shell spring-boot:run             # UI   http://localhost:8081  (log in through Keycloak)
```

(After the first `./mvnw install -DskipTests`, so the module jars are in your local repo.)

Demo users (dev only, password = username): `admin` (everything), `editor` (read + write), `viewer` (read).
Keycloak admin console: `http://localhost:8180`, `admin`/`admin`.

Keycloak and the apps must agree on one URL for Keycloak, because tokens carry it as their issuer. When Keycloak runs on
another host (e.g. a home-lab), start compose there with `KEYCLOAK_URL=http://<host>:8180` and run both apps with
`KEYCLOAK_ISSUER_URI=http://<host>:8180/realms/zk-spring`.

Postgres creates the `keycloak` database only on an empty volume (`infra/postgres/init/`). On an existing volume run
once: `docker exec <postgres-container> psql -U app -d app -c 'CREATE DATABASE keycloak OWNER app;'`.

## Login and permissions

- Keycloak says who the user is and which realm roles they hold (`ADMIN`, `EDITOR`, `VIEWER`).
- The `iam` module maps roles to permissions named `<module>:<action>` (tables `iam.permission`,
  `iam.role_permission`). Endpoints check permissions only: `@PreAuthorize("hasAuthority('task:write')")`.
- The ZK shell is the OAuth2 client; tokens stay in its HttpSession and every `ModuleRestClients` client sends the
  user's access token. View models use `CurrentUser.can(...)` to hide what the user may not do; the backend enforces.
- Change what a role may do with a migration in `backend/iam/src/main/resources/db/migration/iam/`. Running backends
  pick it up within 60 seconds; the UI after the next login.

Manual check after changes: log in as each demo user and confirm viewer sees the list only, editor can create and edit
but not delete, admin can do everything; log out and confirm the Keycloak login page appears again.
````

- [ ] **Step 2: README: add-a-module and split notes**

In `## Add a feature module`, append a third item:

```markdown
3. Permissions: add `<name>:<action>` codes and their role mappings in a new migration under
   `backend/iam/src/main/resources/db/migration/iam/`, protect endpoints with `@PreAuthorize`, and give the UI module
   `.withPermission("<name>:read")`.
```

In `### A. Split a backend module into a microservice`, step 1, after "`TaskModule` already brings its own entities,
...", append:

```markdown
`TaskModule` also brings `SecurityModule`, but the new service has no `iam`, so it starts with the fail-closed
resolver (every call 403, with a startup warning). Before routing traffic to it, add the REST `PermissionResolver`
described in follow-up 2 of `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md`, and give the service
`KEYCLOAK_ISSUER_URI`.
```

- [ ] **Step 3: CLAUDE.md**

In `## Stack`, append a bullet:

```markdown
- Keycloak 26.8.1 (`compose.yaml`, realm file `infra/keycloak/zk-spring-realm.json`), Spring Security 7.
```

In `## Decisions that are deliberate`, append:

```markdown
- **Keycloak authenticates, `iam` authorizes.** Keycloak supplies realm roles; `backend/iam` maps them to
  `<module>:<action>` permissions. Modules check permissions only and never import `iam`; they get them through the
  `PermissionResolver` interface in `backend/security` (no resolver = fail closed). Its signature takes the whole `Jwt`
  so a split service can relay the user's token to `iam`.
- **The ZK app is the OAuth2 client.** Tokens stay in the HttpSession; `ModuleRestClients` adds the bearer token.
  `UiSecurity` is picked up by `ZkSupport`'s component scan, so every ZK app requires login.
- **CSRF is off for `/zkau/**` and `POST /zkau` is permitAll** in `UiSecurity`: ZK binds AJAX requests to a desktop in
  the session, and an expired session must reach ZK's timeout handling instead of a Keycloak redirect.
- **Keycloak endpoints are configured explicitly**, not discovered, so the shell starts while Keycloak is down.
```

In `## Gotchas`, append:

```markdown
- Token issuer must match exactly: `KC_HOSTNAME` (compose `KEYCLOAK_URL`) and the apps' `KEYCLOAK_ISSUER_URI` must use
  the same scheme, host and port, or every API call is 401.
- `infra/postgres/init/` only runs on an empty Postgres volume; create the `keycloak` database by hand otherwise.
- View models must catch `RuntimeException` around API calls and pass it to `ApiErrors.show(...)`: a failed token
  refresh is an `OAuth2AuthorizationException`, not a `RestClientException`.
- On this Mac Docker is the home-lab socket: run backend tests with `TESTCONTAINERS_HOST_OVERRIDE=<homelab-ip>`.
```

In `## Testing`, append:

```markdown
- Backend tests authenticate with `SecurityMockMvcRequestPostProcessors.jwt().authorities(...)` and a dummy
  `issuer-uri` property; no Keycloak is needed. Module tests still boot one module alone.
```

Replace the `## Status` section with:

```markdown
## Status

- Verified: whole build and all tests (`./mvnw verify`) including backend tests against Testcontainers Postgres;
  login, logout and the viewer/editor/admin checklist against Keycloak 26.8.1 on the home-lab.
- Not built yet: see "Follow-ups" in `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md` (admin UI for
  role permissions, REST PermissionResolver for split services, production Keycloak, ...), pagination, a second
  example module, ArchUnit/Spring Modulith boundary tests.
```

- [ ] **Step 4: Record the planning deviations in the spec**

In `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md`, under `### \`frontend/common\` (changed)`, replace
the `UiSecurity (new, imported by the shell next to ZkSupport):` line with
`UiSecurity (new, picked up by ZkSupport's component scan, so every ZK app gets login):`, add to its bullet list
`` `POST /zkau` permitted without login: without a session there is no ZK desktop, so ZK's timeout handling answers. ``,
and replace the "Error handling, configured once in ZK's `zk.xml` error pages for AU requests" bullet and its three
sub-bullets with:

```markdown
- Error handling: view models catch `RuntimeException` around API calls and call `ApiErrors.show(ex)`:
  - API 403 → notification "You don't have permission to do that", page stays usable.
  - API 401 or failed token refresh → redirect to `/logout`, which clears the shell session and goes through login.
  - ZK session timeout (`metainfo/zk/zk.xml` timeout URI) → `/`, which goes through login.
```

- [ ] **Step 5: Full verification**

Run (fish): `set -x TESTCONTAINERS_HOST_OVERRIDE <homelab-ip>; ./mvnw verify`
Expected: BUILD SUCCESS for all modules; test counts: security 5, iam 9, task 7, app 3, frontend-common 13,
shell 6.

- [ ] **Step 6: Commit**

```fish
git add README.md CLAUDE.md docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md
git commit -m "Document Keycloak login, iam permissions and their gotchas

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
