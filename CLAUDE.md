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
  migration folder through a `FlywayConfigurationCustomizer` bean that replaces Boot's default
  `classpath:db/migration` with its own folder (the default scans recursively and would run every module's migrations);
  never put migrations in `db/migration` directly. No cross-module joins or foreign keys.
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
- **Tenants are Keycloak organizations; isolation is `tenant_id` + Hibernate `@TenantId` + Postgres RLS.** The tenant
  is the organization **id** from the token's `tenant` claim (a membership mapper with "add organization id" on the
  `zk-shell` client), never the alias (a deleted alias can be re-created for another company). It is resolved once by
  `KeycloakJwtAuthenticationConverter`, confirmed ACTIVE through `TenantDirectory` (implemented by `iam`) and carried
  on `KeycloakAuthentication`. `backend/tenancy` sets it on Hibernate and on each pooled connection
  (`app.tenant_id`). One user = one tenant.
- **The API connects as `app_runtime`** (no superuser, owns nothing), Flyway as `app`; RLS does not apply to owners
  and superusers. `RuntimeRoleCheck` in `backend/tenancy` stops the app at startup when the connected role is a
  superuser, has BYPASSRLS, or owns a table directly or through a role it belongs to.

## Gotchas

- ZUL must live on the classpath under `src/main/resources/web/<module>/` and be referenced as `~./<module>/...`.
  Putting ZUL in `META-INF/resources` works from the IDE but returns 404 from the fat jar. The shell's home page is
  served via `forward:/zkau/web/shell/index.zul`.
- ZK's EL cannot read record accessors (`title()`). Any record bound from ZUL needs JavaBean getters (`getTitle()`,
  `isDone()`); see `TaskDto` and `UiModule`.
- Jar names follow the artifactId: `backend-app-*.jar`, `frontend-shell-*.jar`.
- Run apps with `./mvnw install -DskipTests` first so module jars exist in the local repo, then
  `./mvnw -pl backend/app spring-boot:run -Dspring-boot.run.profiles=dev` (demo tenants) and
  `./mvnw -pl frontend/shell spring-boot:run`.
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
- Every tenant table needs: `tenant_id UUID NOT NULL`, `@TenantId` on the entity, `ENABLE ROW LEVEL SECURITY` with a
  policy using `nullif(current_setting('app.tenant_id', true), '')::uuid` in both `USING` and `WITH CHECK`, grants to
  `app_runtime` in the module's migration, and indexes/uniques starting with `tenant_id`.
- Flyway versions are global across module folders: two modules must not use the same `V<date>_<n>` on the same day.
- Never declare `clientScopes` in the realm file: Keycloak then skips its built-in scopes and tokens lose `sub` and
  `preferred_username`. The `zk-shell` client needs the `organization` scope, or every user has no tenant.
- The `tenant` mapper on `zk-shell` must write its own claim: on `organization` Keycloak merges it with the built-in
  mapper into a mixed list (`[{"demo":{"id":…}}, "demo"]`) and every user has no tenant.
- Code running outside a request (future scheduled jobs, `@Async`) has no `KeycloakAuthentication` and therefore sees
  no tenant data; it must set an authentication for the tenant it works for. Set it before the transaction starts,
  use one transaction per tenant, and never switch tenants inside one (Hibernate fixes the tenant when the session
  opens and the connection keeps `app.tenant_id` until release, so both barriers would see the first tenant).
  `JdbcTemplate` or a raw `DataSource` outside a JPA transaction sees no tenant rows.
- Backend tests run the application as `app_runtime` (`app-runtime-role.sql` + `TestcontainersConfiguration` with a
  `DynamicPropertyRegistrar`); never use `@ServiceConnection` there, it would connect as the superuser and every RLS
  test would pass for the wrong reason.
- Tests for a tenant table must also prove RLS without Hibernate: `@TenantId` adds `tenant_id = ?` itself, so a
  cross-tenant test through repositories passes even with the RLS policy deleted. Use native SQL inside a tenant
  transaction (see `TenancyTests.rowLevelSecurityAloneHidesOtherTenants`,
  `TaskApiTests.rowLevelSecurityAloneHidesOtherTenantsTasks`), and keep the guard that the test connection is
  `app_runtime` (`ApiApplicationTests.connectsAsARoleThatRowLevelSecurityAppliesTo`).
- With the maintainer's remote Docker (SSH-forwarded Podman socket), a second Testcontainers Postgres in the same JVM
  fails (`localhost:2375 failed to respond`); test classes with different Spring contexts in one module carry
  `@DirtiesContext` so each container stops before the next starts.
- Dev-only data lives in `db/demo/<module>` as repeatable migrations behind a property (`iam.demo-tenants`, set by the
  backend's `dev` profile); test-only migrations live in `db/test/<module>`, added with `spring.flyway.locations` in
  the test (a module without a customizer, like `tenancy`, keeps Boot's default for its test migration).
- Journal lines and entries are never hard deleted: `app_runtime` has no `DELETE` on them and triggers refuse it even
  for the owner. Saving a draft marks its old lines `deleted_at`, then flushes, then adds new ones (Hibernate inserts
  before it updates, which would trip the unique index on live line numbers). `@SQLRestriction` hides removed rows
  from JPA, but every native query on `journal_line` or `journal_entry` must filter `deleted_at is null` by hand: a
  posted entry keeps the lines removed while it was a draft, so a missed filter makes a report wrong.
- `accounting` grants `app_runtime` per table, never with `ALTER DEFAULT PRIVILEGES`. Database triggers guard posted
  entries (`V20261102_4__accounting_guards.sql`): a service that breaks a rule gets a 500 logged as an invariant bug,
  so check every rule in the service first.
- Jackson 3 refuses a missing primitive record component in a request body, so optional request fields must be boxed
  (`Boolean`, not `boolean`).
- In ZK forms, bind an amount with `@load` plus `onChange="@command('setDebit', line=line, amount=self.value)"` rather
  than `@bind` plus a second command: the order of a prompt save and a command on the same event is not something to
  rely on.
- `value` is reserved in ZK's `@command(...)` annotation: do not name a command parameter `value`.
- Two ZK comboboxes must not share a `ListModels.toListSubModel` model: selection lives in the underlying list, so a
  pick shows on every row. Give each row its own with `@init` (see `EntryViewModel.getNewAccountModel`).
- An included page's `<?link?>` header never runs: load a module's CSS with `<style src="~./module/x.css"/>` inside the
  root. (`frontend/task/.../main.zul` still has the old pattern; left as is.)
- A ZK command must notify only what it changed: `@NotifyChange("*")` reloads a grid's model, which rebuilds its rows and
  drops keyboard focus to the page. See `EntryViewModel`: amounts notify the totals and their own line.

## Testing

- Backend tests use Testcontainers (Postgres), so Docker must be usable by your user (`docker ps` works without sudo).
  A new `docker` group membership only applies after logging in again.
- `backend/task` tests boot the module alone (`TaskApiTests.TestApp`); keep it that way so the module stays standalone.
- Frontend tests are context-load plus unit tests of plain classes. `AccountingScreensRenderTests` also renders ZUL
  pages through ZK's real servlets on a random port, with the REST client mocked: a wrong binding redirects instead of
  answering 200. Copy it for new screens; MockMvc does not run ZK's servlets. It passes on hidden components too (ZK
  sends them in its JSON) and does not click.
- `AccountingClickPathTests` clicks: `ZkDriver` sends real AU requests to the test server, so it pins commands, which row
  a change reached, and what is `visible`. It needs no Docker. It cannot see focus or client-side validation (a
  constraint's error bubble, `w:onClick`), because no browser runs: those are README checklist rows 33 and 34.
- To exercise the UI without a database, run any stub on :8080 returning
  `[{"id":1,"title":"x","description":"","done":false,"createdAt":"2026-01-01T00:00:00Z"}]` for `GET /api/tasks`.
- Backend tests authenticate with `SecurityMockMvcRequestPostProcessors.jwt().authorities(...)` and a dummy
  `issuer-uri` property; no Keycloak is needed. Module tests still boot one module alone.

## Status

- Verified: whole build and all tests (`./mvnw verify`), including backend tests against Testcontainers Postgres and a
  real signed token through decoder, converter, `iam` resolver and `@PreAuthorize` (`TokenToPermissionsTests`).
- Verified 2026-11-01 (`./mvnw verify`): tenant isolation as `app_runtime` through Hibernate and through RLS alone,
  tenants resolved from real signed tokens end to end, suspended/unknown/multiple organizations get no permissions.
- Verified 2026-11-02 (`./mvnw verify`): accounting backend, including database guards bypassing the services, 20
  parallel posts with gapless numbers, and the bookkeeper/accountant path with real signed tokens.
- Verified 2026-11-03 (`./mvnw verify`): the accounting screens render, their logic is unit-tested, and their click
  paths run through real ZK over AU.
- Verified 2026-11-03 (`./mvnw verify`): ledger hardening, including no hard deletes as `app_runtime` and as the
  owner, a migration over existing Phase 1 data, and the delete-versus-post race. README rows 37-40 are still to be
  done by hand.
- Verified 2026-10-28 by hand against Keycloak 26.8.1 on the home-lab (README "Manual end-to-end check" rows 1–6):
  login redirect, viewer/editor/admin permissions in the UI, logout back to the Keycloak login page, 401 + Bearer from
  the API.
- Verified 2026-11-02 by hand on the home-lab (rows 11–14): `acme-admin` sees none of demo's tasks, a suspended tenant
  and a user in no organization see "no access", tasks from before the upgrade still show for demo users.
- Verified 2026-11-03 by hand on the home-lab (rows 15–36): the accounting screens through the shell with Keycloak,
  including keyboard-only keying, a refused invalid amount, a form left past the session timeout, and CSV in Excel for
  Mac (region Indonesia). Not run: CSV in Windows Excel with Indonesian settings.
- Not yet verified by hand: README checklist rows 7–10 (backend down, ZK session timeout, silent token refresh,
  Keycloak session ended).
- Not built yet: see "Follow-ups" in `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md` (admin UI for
  role permissions, REST PermissionResolver for split services, production Keycloak, ...), pagination, a second
  example module, ArchUnit/Spring Modulith boundary tests.
