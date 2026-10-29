# Authentication and permissions: Keycloak + `iam` module

Date: 2026-10-28
Status: approved design, awaiting implementation plan

## Goal

Users log in to the ZK frontend through Keycloak (OAuth2 authorization code flow). The frontend calls the backend
with an OAuth2 bearer token (JWT). The backend validates the token and authorizes every endpoint by fine-grained
permissions. Users can hold several roles; which permissions a role grants is stored in our own database, so roles and
permissions can grow without code changes to the modules that enforce them.

### Success criteria

- Every ZK page requires login; unauthenticated visitors are sent to the Keycloak login page.
- Every `/api/**` request without a valid bearer token gets 401; with a valid token but missing permission, 403.
- Three demo users behave as follows:

  | User     | Keycloak role | Sees / can do                                  |
  |----------|---------------|------------------------------------------------|
  | `viewer` | `VIEWER`      | Task list only                                 |
  | `editor` | `EDITOR`      | List, create, edit; no delete                  |
  | `admin`  | `ADMIN`       | Everything                                     |

- Logout ends both the shell session and the Keycloak session.
- Module boundaries hold: `task` never imports `iam`; each backend module still boots alone in its tests.

### Decisions taken during design

| Question                         | Decision                                                                 |
|----------------------------------|--------------------------------------------------------------------------|
| Who issues tokens / owns users   | Keycloak, run from this repo's `compose.yaml` on the home-lab            |
| Role → permission mapping        | Our own Postgres schema `iam` (Keycloak only supplies role names)        |
| Where roles become permissions   | Shared `backend/security` module + `PermissionResolver` interface (approach A) |
| Managing mappings in v1          | Seeded by Flyway migrations; admin UI/write API is a follow-up           |

Rejected alternatives: modules calling `iam` directly (breaks the module rule), permissions baked into the token by a
custom Keycloak mapper SPI (a Keycloak plugin to maintain; changes only apply after token refresh), permissions as
Keycloak composite roles (rejected in favour of DB-managed mappings).

## Architecture

```
Browser ──(1) GET :8081/──▶ ZK shell ──(2) 302──▶ Keycloak login page (:8180, realm zk-spring)
        ◀──(3) code ─────────────────────────────┘
ZK shell ──(4) code→tokens (back channel)──▶ Keycloak
         access + refresh token kept in the HttpSession, never sent to the browser
ZK shell ──(5) Authorization: Bearer <JWT>──▶ backend/app :8080
backend/app: validate JWT (Keycloak JWKS) → realm roles → PermissionResolver (iam, cached) → authorities
             → @PreAuthorize("hasAuthority('task:write')")
```

The ZK shell is a server-side OAuth2 client (backend-for-frontend): the browser only holds the shell's session cookie.

## Backend

### `backend/security` (new module)

Shared resource-server wiring. No database, no domain code. Entered through `SecurityModule`.

- Stateless `SecurityFilterChain`: `/api/**` authenticated; `/actuator/health`, `/v3/api-docs/**`, `/swagger-ui/**`,
  `/swagger-ui.html` permitted. No HTTP session, CSRF disabled (bearer tokens only).
- JWT validation via `spring.security.oauth2.resourceserver.jwt.issuer-uri`. Boot resolves the JWKS lazily, so the app
  starts while Keycloak is down. Only access tokens are accepted (`typ` = `Bearer`); ID tokens are rejected.
- `@EnableMethodSecurity` so modules can use `@PreAuthorize`.
- `KeycloakJwtAuthenticationConverter`: calls `PermissionResolver.permissionsFor(jwt)` and returns a
  `JwtAuthenticationToken` whose authorities are the permission codes (e.g. `task:write`) and whose name is the
  `preferred_username` claim.
- `PermissionResolver` interface: `Set<String> permissionsFor(Jwt jwt)`. It takes the whole token, not just role names,
  so a future REST implementation can relay the user's token to `iam` (follow-up 2) without changing this interface.
  A static helper `KeycloakRoles.of(Jwt)` reads `realm_access.roles` (missing claim = no roles) for implementations.
- When no `PermissionResolver` bean is present the module falls back to one that grants nothing (fail closed:
  authenticated but forbidden everywhere) and logs a warning at startup.
- 401 responses carry `WWW-Authenticate: Bearer` (Spring default). 403 responses are RFC 9457 `ProblemDetail`
  bodies, matching `GlobalExceptionHandler`.

### `backend/iam` (new module)

Owns Postgres schema `iam` and migrations in `db/migration/iam/` (date-based versions). Entered through `IamModule`,
which imports `SecurityModule` and contributes its Flyway location the same way `TaskModule` does.

Schema:

```sql
CREATE SCHEMA IF NOT EXISTS iam;

CREATE TABLE iam.permission (
    code        VARCHAR(100) PRIMARY KEY,      -- '<module>:<action>', e.g. 'task:read'
    description VARCHAR(500) NOT NULL
);

CREATE TABLE iam.role_permission (
    role            VARCHAR(100) NOT NULL,     -- Keycloak realm role name, e.g. 'EDITOR'
    permission_code VARCHAR(100) NOT NULL REFERENCES iam.permission (code),
    PRIMARY KEY (role, permission_code)
);
```

There is no role table: roles are Keycloak's. The foreign key stays inside the `iam` schema, so the
"no cross-module foreign keys" rule holds.

Seed data (migration):

| Permission    | VIEWER | EDITOR | ADMIN |
|---------------|:------:|:------:|:-----:|
| `task:read`   |   ✓    |   ✓    |   ✓   |
| `task:write`  |        |   ✓    |   ✓   |
| `task:delete` |        |        |   ✓   |
| `iam:read`    |        |        |   ✓   |

Components:

- `DbPermissionResolver implements PermissionResolver`: takes the roles from the token (`KeycloakRoles.of(jwt)`), one
  query for the union of their permissions, cached per (sorted) role set for 60 seconds. Unknown roles contribute
  nothing.
- `GET /api/iam/me` (any authenticated user) → `{ "username", "name", "roles", "permissions" }`, from the JWT
  (`preferred_username`, `name`, `realm_access.roles`) and the resolved authorities.
- `GET /api/iam/roles` (`iam:read`) → each role with its permission codes. Read-only.

Rule for future modules: permissions are named `<module>:<action>`. A new module registers its permissions and default
role mappings by adding a migration under `backend/iam/src/main/resources/db/migration/iam/`.

### `backend/task` (changed)

- `TaskModule` imports `SecurityModule` (as it imports `CommonModule`).
- `TaskController`: `@PreAuthorize("hasAuthority('task:read')")` on both GETs, `task:write` on POST and PUT,
  `task:delete` on DELETE.
- No imports from `iam`.

### `backend/app` (changed)

- `@Import(IamModule.class)` alongside `TaskModule`.
- `spring.security.oauth2.resourceserver.jwt.issuer-uri=${KEYCLOAK_ISSUER_URI:http://localhost:8180/realms/zk-spring}`.

## Frontend

### `frontend/common` (changed)

- `UiSecurity` (new, picked up by `ZkSupport`'s component scan, so every ZK app gets login):
  - `oauth2Login` with client registration `keycloak` (client id `zk-shell`); every request authenticated except
    `/actuator/health`.
  - Provider endpoints configured explicitly from `KEYCLOAK_ISSUER_URI`
    (`/protocol/openid-connect/auth`, `/token`, `/certs`, `/userinfo`, `/logout`) instead of OIDC discovery, so the
    shell starts without Keycloak.
  - Logout on `GET /logout`: invalidates the session, then RP-initiated logout at Keycloak
    (own `KeycloakLogoutSuccessHandler`, because Spring's handler needs `end_session_endpoint` from discovery, which is not used), post-logout redirect to `/`.
  - CSRF ignored for `/zkau/**` (ZK binds every AU request to a desktop in the user's session; Spring's CSRF token
    would reject every ZK event). Kept for everything else.
  - `POST /zkau` permitted without login: without a session there is no ZK desktop, so ZK's timeout handling answers.
  - `X-Frame-Options: SAMEORIGIN`, so remote modules can still be framed behind one origin (README §B.3).
- `ModuleRestClients`: the shared builder gets Spring Security's `OAuth2ClientHttpRequestInterceptor` backed by an
  `OAuth2AuthorizedClientManager` (authorization code + refresh token). Every module client sends the current user's
  bearer token and refreshes it when expired. This relies on ZK running commands on the servlet request thread (ZK 10
  default: event threads off). `TaskApiClient` is unchanged.
- `CurrentUser` (new): fetches `GET /api/iam/me` once per HTTP session through `ModuleRestClients.forModule("iam")`
  (so `app.modules.iam.api-base-url` can point elsewhere later), stores it in the session; exposes `getName()`,
  `getUsername()`, `can(String permission)`. Frontend DTO is its own class (no backend imports), with JavaBean getters.
- `UiModule`: new optional `requiredPermission` (nullable). `embedded(...)` and `remote(...)` keep their signatures and
  default it to `null`; a `withPermission(String)` copy method sets it.
- Error handling: view models catch `RuntimeException` around API calls and call `ApiErrors.show(ex)`:
  - API 403 → notification "You don't have permission to do that", page stays usable.
  - API 401 or failed token refresh → redirect to `/logout`, which clears the shell session and goes through login.
  - ZK session timeout (`metainfo/zk/zk.xml` timeout URI) → `/`, which goes through login.

### `frontend/shell` (changed)

- Header: current user's name and a **Log out** button (`/logout`).
- Navigation lists only modules whose `requiredPermission` is `null` or `currentUser.can(...)`.
- `application.properties`: `KEYCLOAK_ISSUER_URI` (default `http://localhost:8180/realms/zk-spring`),
  `KEYCLOAK_CLIENT_ID` (default `zk-shell`), `KEYCLOAK_CLIENT_SECRET` (default: the dev secret from the realm file).

### `frontend/task` (changed)

- `TaskUiModule` registers with `requiredPermission = "task:read"`.
- `TaskViewModel` exposes `canWrite` (`task:write`) and `canDelete` (`task:delete`); `main.zul` hides the form, Save,
  Edit and Delete accordingly. The backend remains the enforcement point; hiding is UX only.

## Infrastructure

### `compose.yaml`

- New `keycloak` service, image `quay.io/keycloak/keycloak` pinned to the latest release at implementation time,
  port `8180`, command `start-dev --import-realm`.
- Storage: database `keycloak` on the existing `postgres` service (`KC_DB=postgres`), `depends_on` postgres healthy.
- `KC_BOOTSTRAP_ADMIN_USERNAME` / `KC_BOOTSTRAP_ADMIN_PASSWORD` from env, default `admin` / `admin` (dev only).
- `KC_HOSTNAME` from env (`KEYCLOAK_URL`, default `http://localhost:8180`). It must be the URL that both the browser
  and the backend use: the token's `iss` must equal the backend's `issuer-uri` exactly, or every call is 401.
- `infra/postgres/init/01-keycloak.sql`, copied into the
  `zk-spring-postgres` image built from `infra/postgres/Dockerfile`, creates database `keycloak`.
  This only runs on an empty volume; existing installs (including the current home-lab) run
  `CREATE DATABASE keycloak OWNER app;` once by hand.

### `infra/keycloak/zk-spring-realm.json`

- Realm `zk-spring`.
- Client `zk-shell`: confidential, standard flow only, redirect URIs `http://localhost:8081/*`, post-logout redirect
  `http://localhost:8081/*`, fixed dev client secret.
- Realm roles `ADMIN`, `EDITOR`, `VIEWER`.
- Users `admin`, `editor`, `viewer` (password = username, dev only), one role each.
- Copied into the `zk-spring-keycloak` image (`infra/keycloak/Dockerfile`) rather than bind-mounted, so compose works
  against a remote daemon.
- Keycloak imports a realm only if it does not exist, so changes made later in the admin console survive restarts.

## Testing

No test needs a running Keycloak.

- `backend/security`: unit tests for `KeycloakJwtAuthenticationConverter` (token → resolver → authorities, username
  claim), `KeycloakRoles.of` (roles present, missing `realm_access`) and the fail-closed fallback.
- `backend/iam`: Testcontainers Postgres; migrations apply; `DbPermissionResolver` returns the seeded permissions per
  role and the union for several roles; `GET /api/iam/me` and `GET /api/iam/roles` (403 without `iam:read`) with
  `SecurityMockMvcRequestPostProcessors.jwt()`.
- `backend/task`: `TaskApiTests` still boots the module alone (no `iam`), using `jwt().authorities(...)`:
  401 without token; 403 for `task:read`-only POST/PUT/DELETE; 2xx for `task:write` create/update; 403 for
  `task:write` DELETE; 204 with `task:delete`.
- `backend/app`: context load with a dummy issuer URI.
- `frontend/*`: context-load tests with the OAuth2 client configured (explicit endpoints, no discovery).
- Manual end-to-end checklist (README): log in as each demo user and confirm the success-criteria table; log out and
  confirm the Keycloak login page is shown again; stop the backend and confirm the UI shows an error rather than a
  stack trace.

## Documentation

- `README.md`: running with Keycloak (env vars, first-time `keycloak` database), demo users, "add a module" step for
  permissions, the end-to-end checklist, and in "Splitting a module out" how a split service gets permissions (see
  follow-up 2).
- `CLAUDE.md`: stack (Keycloak), deliberate decisions (`PermissionResolver`, permissions in `iam`, CSRF off for
  `/zkau/**`, explicit provider endpoints), gotchas (`KC_HOSTNAME`/issuer match, init script only on empty volume),
  status.

## Follow-ups (out of scope for this spec)

Each gets its own spec → plan → implementation cycle.

1. **Admin UI and write API for role → permission mappings.** `iam` write endpoints (assign/revoke permission for a
   role, protected by a new `iam:write` permission) and a ZK `frontend/iam` UI module to list roles and tick
   permissions. The 60 s resolver cache means changes apply without restart; consider explicit cache eviction on write.
2. **REST-backed `PermissionResolver` for split services (token relay).** When a module becomes its own microservice
   (README §A), it cannot use `DbPermissionResolver`. Add `RestPermissionResolver` to `backend/security`: it calls the
   existing `GET /api/iam/me` with the incoming user token and caches the permissions per role set for 60 seconds;
   select it with `app.security.permission-resolver=rest` and `app.security.iam-base-url`. No new `iam` endpoint, no new
   Keycloak client, no change to `task` or the frontend. Estimated size: one class, config, one test with a mocked
   `iam`. Decide at that time how to behave when `iam` is unreachable after the cache expires (proposed: fail closed
   with 503).

   Horizontal scaling of `backend/app` (more instances) needs none of this: the API is stateless and every instance
   uses `DbPermissionResolver`. Multiple shell instances need sticky sessions or a shared session store, as ZK
   desktops live in the HTTP session regardless of login.
3. **Production Keycloak.** `start` instead of `start-dev`, TLS (or TLS-terminating reverse proxy), real hostname,
   secrets from env/secret store instead of the realm file, admin bootstrap removed after first start, backups of the
   `keycloak` database.
4. **Login hardening.** MFA/OTP policy, password policy, brute-force detection, session and token lifetimes tuned in
   the realm; decide on self-registration and password reset (needs SMTP).
5. **Automated end-to-end test with a real Keycloak.** Testcontainers Keycloak (realm file imported) driving a token
   request, plus a backend call, to catch issuer/claim mismatches the mock-JWT tests cannot.
6. **Remote micro-frontends and SSO.** When a UI module runs as its own app (README §B), it needs its own OAuth2
   client registration in Keycloak (or shared client) and gets SSO through the Keycloak session; frame-ancestors for
   the shell origin.
7. **Audit trail.** Record who created/changed/deleted data (e.g. `created_by` from `preferred_username`) once there is
   a requirement for it.
8. **Service-to-service calls without a user.** Batch jobs or message consumers calling another module have no user
   token to relay; they need a Keycloak client-credentials client and a service-account role mapped in `iam`.
9. **Data-level authorization.** Per-record rules (e.g. users only edit their own tasks) are not covered by
   `<module>:<action>` permissions; design when such a requirement exists.
