# CLAUDE.md

ZK + Spring Boot skeleton. Architecture, run instructions and the guides for splitting modules into microservices /
micro-frontends are in `README.md`; read it first. This file holds only what is easy to get wrong.

## Stack

- Spring Boot 4.1.2, Java 25 (current LTS; Spring Boot has no official LTS line, so we track the latest release).
- ZK 10.3.0.1-jakarta (latest stable). ZK 11 is not released; `./mvnw -Pzk11 package` uses a nightly Eval build from
  ZK's evaluation repo for experiments only. Never ship it.
- PostgreSQL 17 (`compose.yaml`), Flyway migrations, Hibernate `ddl-auto=validate`.
- Spring Boot 4 specifics: starters are `spring-boot-starter-webmvc` / `-restclient` / `-flyway`; Jackson 3; test
  annotations live in new packages (e.g. `org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc`).
- Keycloak 26.8.1 (`compose.yaml`, realm file `infra/keycloak/zk-spring-realm.json`), Spring Security 7.

## Decisions that are deliberate

- **No `zkspringboot` starter.** ZK is wired into plain Spring Boot in `frontend/common/.../ZkSupport.java`
  (DHtmlLayoutServlet on `*.zul`, DHtmlUpdateServlet on `/zkau/*`, ZK HttpSessionListener). The starter pins Boot to
  4.0.x and 3.5.x; wiring it by hand keeps Spring Boot and ZK versions independent. Do not reintroduce it.
- **No `zkspring-core`.** `@WireVariable` Spring lookups use our own `SpringBeanVariableResolver`; view models declare
  `@VariableResolver(SpringBeanVariableResolver.class)`.
- **Modules are entered through one class and composed with `@Import`**, never package scanning across modules
  (`TaskModule`, `TaskUiModule`; apps are `backend/app`, `frontend/shell`). This is what makes a module splittable.
- **Each backend module owns its Postgres schema** (`task`), entity `@Table(schema = ...)`, and migrations in
  `db/migration/<module>/` with date-based versions (`V20261027_1__...`) so modules never collide. Modules add their
  migration folder through a `FlywayConfigurationCustomizer` bean. No cross-module joins or foreign keys.
- **Frontend DTOs are duplicated on purpose**; UI modules never import backend classes. The REST API is the contract.
- **Keycloak authenticates, `iam` authorizes.** Keycloak supplies realm roles; `backend/iam` maps them to
  `<module>:<action>` permissions. Modules check permissions only and never import `iam`; they get them through the
  `PermissionResolver` interface in `backend/security` (no resolver = fail closed). Its signature takes the whole `Jwt`
  so a split service can relay the user's token to `iam`.
- **The ZK app is the OAuth2 client.** Tokens stay in the HttpSession; `ModuleRestClients` adds the bearer token.
  `UiSecurity` is picked up by `ZkSupport`'s component scan, so every ZK app requires login.
- **CSRF is off for `/zkau/**` and `POST /zkau` is permitAll** in `UiSecurity`: ZK binds AJAX requests to a desktop in
  the session, and an expired session must reach ZK's timeout handling instead of a Keycloak redirect.
- **Keycloak endpoints are configured explicitly**, not discovered, so the shell starts while Keycloak is down.

## Gotchas

- ZUL must live on the classpath under `src/main/resources/web/<module>/` and be referenced as `~./<module>/...`.
  Putting ZUL in `META-INF/resources` works from the IDE but returns 404 from the fat jar. The shell's home page is
  served via `forward:/zkau/web/shell/index.zul`.
- ZK's EL cannot read record accessors (`title()`). Any record bound from ZUL needs JavaBean getters (`getTitle()`,
  `isDone()`); see `TaskDto` and `UiModule`.
- Jar names follow the artifactId: `backend-app-*.jar`, `frontend-shell-*.jar`.
- Run apps with `./mvnw install -DskipTests` first so module jars exist in the local repo, then
  `./mvnw -pl backend/app spring-boot:run` and `./mvnw -pl frontend/shell spring-boot:run`.
- Token issuer must match exactly: `KC_HOSTNAME` (compose `KEYCLOAK_URL`) and the apps' `KEYCLOAK_ISSUER_URI` must use
  the same scheme, host and port, or every API call is 401 and the shell sends you back to the login page after every
  login (the shell logs `API call unauthorized` with the backend's `WWW-Authenticate` reason). For a Keycloak on
  another host, forward port 8180 over SSH and keep the `localhost` defaults; the realm's `sslRequired=external` rejects plain HTTP from non-private addresses
  such as Tailscale's 100.x.
- `infra/postgres/init/` only runs on an empty Postgres volume; create the `keycloak` database by hand otherwise.
- View models must catch `RuntimeException` around API calls and pass it to `ApiErrors.show(...)`: a failed token
  refresh is an `OAuth2AuthorizationException`, not a `RestClientException`.
- Docker may be remote (on the maintainer's Mac it is the home-lab's rootless Podman, reached through an SSH-forwarded
  socket). So `compose.yaml` must not bind-mount repo files; copy them into an image under `infra/` instead. Backend
  tests against a remote daemon need `TESTCONTAINERS_HOST_OVERRIDE=<daemon host>` and
  `TESTCONTAINERS_DOCKER_SOCKET_OVERRIDE=<socket path on that host>` (`/run/user/1000/podman/podman.sock`).

## Testing

- Backend tests use Testcontainers (Postgres), so Docker must be usable by your user (`docker ps` works without sudo).
  A new `docker` group membership only applies after logging in again.
- `backend/task` tests boot the module alone (`TaskApiTests.TestApp`); keep it that way so the module stays standalone.
- Frontend tests are context-load only. To exercise the UI without a database, run any stub on :8080 returning
  `[{"id":1,"title":"x","description":"","done":false,"createdAt":"2026-01-01T00:00:00Z"}]` for `GET /api/tasks`.
- Backend tests authenticate with `SecurityMockMvcRequestPostProcessors.jwt().authorities(...)` and a dummy
  `issuer-uri` property; no Keycloak is needed. Module tests still boot one module alone.

## Status

- Verified: whole build and all tests (`./mvnw verify`), including backend tests against Testcontainers Postgres and a
  real signed token through decoder, converter, `iam` resolver and `@PreAuthorize` (`TokenToPermissionsTests`).
- Verified 2026-10-28 by hand against Keycloak 26.8.1 on the home-lab (README "Manual end-to-end check" rows 1–6):
  login redirect, viewer/editor/admin permissions in the UI, logout back to the Keycloak login page, 401 + Bearer from
  the API.
- Not yet verified by hand: README checklist rows 7–10 (backend down, ZK session timeout, silent token refresh,
  Keycloak session ended).
- Not built yet: see "Follow-ups" in `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md` (admin UI for
  role permissions, REST PermissionResolver for split services, production Keycloak, ...), pagination, a second
  example module, ArchUnit/Spring Modulith boundary tests.
