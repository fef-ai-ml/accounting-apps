# ZK + Spring Boot skeleton

Spring Boot 4.1 (Java 25), PostgreSQL, REST API backend and a ZK 10.3 frontend. ZK is wired into plain Spring Boot
by hand (`frontend/common/.../ZkSupport.java`); the `zkspringboot` starter is not used, so Spring Boot and ZK
versions are independent.

## Layout

One Maven module per bounded context on each side, assembled by a thin deployable app.

```
backend/
  common/   shared kernel: error handling only, no domain code
  security/ shared resource-server wiring: JWT validation, PermissionResolver, method security
  iam/      "iam" bounded context: role -> permission mappings (own schema), /api/iam/me
  task/     "task" bounded context: controller, service, entity, own Postgres schema + migrations
  app/      Spring Boot app (port 8080): imports the context modules it deploys
frontend/
  common/   ZK servlet wiring, Keycloak login, UiModule contract, per-module REST client factory (bearer relay)
  task/     "task" UI module: ZUL pages, view models, client for the task API
  shell/    Spring Boot app (port 8081): layout + navigation, hosts the UI modules
```

Rules that keep the modules separable:

- A module is entered through one class (`TaskModule`, `TaskUiModule`); apps compose with `@Import`, not package scanning.
- A backend module owns its tables (own schema `task`), its Flyway migrations (`db/migration/<module>/`, date-based
  versions so modules never collide) and its REST paths (`/api/<module>/...`). No cross-module joins or foreign keys;
  modules talk through REST or an interface in the caller's own code.
- A UI module owns its ZUL pages (`src/main/resources/web/<module>/`, referenced as `~./<module>/...`), its DTOs and
  its API client. It never imports backend classes; the REST API is the contract.

## Run

```
docker compose up -d                                  # PostgreSQL :5432, Keycloak :8180 (realm zk-spring)
./mvnw -pl backend/app spring-boot:run                # API  http://localhost:8080  (Swagger UI: /swagger-ui.html)
./mvnw -pl frontend/shell spring-boot:run             # UI   http://localhost:8081  (log in through Keycloak)
```

(After the first `./mvnw install -DskipTests`, so the module jars are in your local repo.)

Demo users (dev only, password = username):

| User | Role | Organization (tenant) |
|---|---|---|
| `admin` | `ADMIN` (everything) | `demo` |
| `editor` | `EDITOR` (read + write) | `demo` |
| `viewer` | `VIEWER` (read) | `demo` |
| `acme-admin` | `ADMIN` | `acme` |

Keycloak admin console: `http://localhost:8180`, `admin`/`admin`.

Keycloak and the apps must agree on one URL for Keycloak, because tokens carry it as their issuer. The defaults
everywhere are `http://localhost:8180`. When Keycloak runs on another host (e.g. a home-lab), keep those defaults and
forward the port: `ssh -fN -o ServerAliveInterval=30 -o ServerAliveCountMax=3 -L 8180:localhost:8180 <user>@<host>`
(without the keep-alive options a dropped connection leaves the local port open but silent, and the login page hangs
forever; kill the old `ssh` process and start the tunnel again). Pointing the apps at `http://<host>:8180` instead
only works if Keycloak sees the browser as a private address or is served over HTTPS: the realm keeps Keycloak's
default `sslRequired=external`. It also requires changing the `ports` binding of `keycloak` in `compose.yaml` to
`"8180:8080"` (it is bound to `127.0.0.1` by default). Then set `KEYCLOAK_URL=http://<host>:8180` for compose and
`KEYCLOAK_ISSUER_URI=http://<host>:8180/realms/zk-spring` for both apps.

`infra/keycloak/zk-spring-realm.json` is for development only (demo users whose password is their username, a fixed
client secret). Never import it into a production Keycloak.

Postgres creates the `keycloak` database only on an empty volume (`infra/postgres/init/`). On an existing volume run
once: `docker exec <postgres-container> psql -U app -d app -c 'CREATE DATABASE keycloak OWNER app;'`.

## Login and permissions

- Keycloak says who the user is and which realm roles they hold (`ADMIN`, `EDITOR`, `VIEWER`).
- The `iam` module maps roles to permissions named `<module>:<action>` (tables `iam.permission`,
  `iam.role_permission`). Endpoints check permissions only: `@PreAuthorize("hasAuthority('task:write')")`.
- The ZK shell is the OAuth2 client; tokens stay in its HttpSession and every `ModuleRestClients` client sends the
  user's access token. View models use `CurrentUser.can(...)` to hide what the user may not do; the backend enforces.
- Change what a role may do with a migration in `backend/iam/src/main/resources/db/migration/iam/` (applied at the
  next backend start). Rows edited directly in `iam.role_permission` reach running backends within 60 seconds; the UI
  picks them up at the next login.

### Manual end-to-end check

Run this after changes to login, permissions or the ZK wiring; the automated tests cannot cover the browser, Keycloak
and ZK's own servlets together. Start the apps only after `./mvnw install -DskipTests` has finished: a shell started
while the install is still running resolves stale jars and comes up without Spring Security.

```
./mvnw -q install -DskipTests
./mvnw -pl backend/app spring-boot:run       # add env DB_URL=... if Postgres is not on localhost
./mvnw -pl frontend/shell spring-boot:run
```

Use a private browser window per user, at `http://localhost:8081`:

| # | Action | Expected |
|---|---|---|
| 1 | Open `http://localhost:8081` | Keycloak login page of realm `zk-spring` |
| 2 | Log in as `viewer`/`viewer` | Header shows "Vera Viewer"; "Tasks" in the navigation; list visible; no form, no Edit/Delete, checkboxes disabled |
| 3 | Click **Log out** | Back on the Keycloak login page (not signed in again automatically) |
| 4 | Log in as `editor`/`editor` | Form and Edit visible, no Delete; creating and editing a task works |
| 5 | Log in as `admin`/`admin` | Everything visible; deleting a task works |
| 6 | `curl -i http://localhost:8080/api/tasks` | `401` with `WWW-Authenticate: Bearer` |
| 7 | Stop the backend, reload the shell | Page renders with an "API error" notification, no stack trace; after restarting the backend and reloading, it works again |
| 8 | Restart the shell with `env SERVER_SERVLET_SESSION_TIMEOUT=1m`, log in, wait 2 minutes, click Edit | Page reloads through login (Keycloak may sign you straight back in); no raw HTML or error dialog |
| 9 | Leave a page idle for more than 5 minutes (access-token lifetime), then act | It still works (the shell refreshes the token silently) |
| 10 | In the Keycloak admin console (`http://localhost:8180`, `admin`/`admin`) sign out the `editor` session, wait more than 5 minutes, click Save | Sent to `/logout`, then the Keycloak login page |
| 11 | Log in as `acme-admin`/`acme-admin` | None of demo's tasks are visible; its own task list is empty |
| 12 | `UPDATE iam.tenant SET status='SUSPENDED' WHERE alias='acme'` | Within 60 s `acme-admin` sees "Your organization has no access" (set it back to `ACTIVE` afterwards) |
| 13 | Log in as a demo user | Tasks created before the upgrade still show |
| 14 | Log in as a Keycloak user in no organization | "Your organization has no access" |

If you are sent back to the login page after every login, the shell log shows `API call unauthorized` with the
backend's `WWW-Authenticate` reason (usually an issuer mismatch, see above).

Tests: `./mvnw verify` (backend tests start PostgreSQL through Testcontainers, so Docker must be usable).

## Multi-tenancy

Each customer company is a tenant, which is one Keycloak organization. Tenant tables carry a `tenant_id` column; the
Hibernate tenant filter and Postgres row-level security (RLS) both enforce it. The API connects as the Postgres role
`app_runtime` (RLS applies to it), Flyway as the owner `app`. A user belongs to exactly one organization; the tenant
comes only from the token's `organization` claim.

Environment variables: `DB_RUNTIME_USER` / `DB_RUNTIME_PASSWORD` (the API; defaults are the dev password
`app_runtime`, so production must set them) and `DB_USER` / `DB_PASSWORD` (used only by Flyway).

### Onboard a customer

1. Keycloak admin console, realm `zk-spring` -> Organizations -> Create (alias, name, domain), add members, give them
   realm roles; copy the organization id.
2. As `app`: `INSERT INTO iam.tenant (id, alias, name, status) VALUES ('<organization id>', '<alias>', '<name>', 'ACTIVE');`

Until step 2, the organization's users log in but get no permissions. Changes reach the API within 60 seconds.

### Suspend a customer

`UPDATE iam.tenant SET status = 'SUSPENDED' WHERE alias = '<alias>';` (effective within 60 seconds).

### Upgrading an existing setup

1. Create the runtime role **before the backend starts**: the `iam` and `task` migrations `GRANT` to `app_runtime`, and
   Flyway fails if it is missing. `infra/postgres/init/02-app-runtime.sql` only runs on an empty volume. Postgres may be
   on a remote Docker; `docker exec` works through `DOCKER_HOST`.

   ```fish
   docker exec zk-spring-postgres-1 psql -U app -d app -c "CREATE ROLE app_runtime LOGIN PASSWORD 'app_runtime' NOSUPERUSER NOBYPASSRLS;"
   ```

2. Re-import the Keycloak realm (it now has organizations): admin console -> realm `zk-spring` -> Realm settings ->
   Action -> Delete; then `docker-compose build keycloak; docker-compose up -d keycloak`. The dev realm holds only demo
   data; manual changes to it are lost.
3. `./mvnw install -DskipTests`, then start the backend (Flyway adds `iam.tenant` and moves existing tasks to `demo`).

## Add a feature module

1. `backend/<name>`: copy `backend/task`, rename packages, schema, migration folder; import its `*Module` in `backend/app`.
2. `frontend/<name>`: copy `frontend/task`, rename; define a `UiModule` bean; import its `*UiModule` in `frontend/shell`.
3. Permissions: add `<name>:<action>` codes and their role mappings in a new migration under
   `backend/iam/src/main/resources/db/migration/iam/`, protect endpoints with `@PreAuthorize`, and give the UI module
   `.withPermission("<name>:read")`.

## Splitting a module out later

The backend split and the frontend split are independent: you can do either one, or both, for any module. The examples
use the `task` module. Do one module at a time and run the checklist at the end of each section before moving on.

### A. Split a backend module into a microservice

Goal: `task` runs as its own service on port 8082 with its own database; `backend/app` no longer contains it.

**0. Before you start: remove in-process coupling.** If another backend module calls task classes directly, replace that
call with an interface owned by the *caller* plus a REST client implementation. Nothing may import `com.example.task.*`
except the app that deploys it. (Today nothing does, so this is a no-op until you add a second module.)

**1. Create the service app.** Copy `backend/app` to `backend/task-service` and change:

- `backend/task-service/pom.xml`: `artifactId` to `backend-task-service`; keep only the `backend-task` module dependency
  (plus actuator, `flyway-database-postgresql`, the PostgreSQL driver, springdoc, and the same test dependencies).
- Add `<module>task-service</module>` to `backend/pom.xml`.
- Rename the package and main class, importing only this module:

  ```java
  package com.example.taskservice;

  @SpringBootApplication
  @Import(TaskModule.class)
  public class TaskServiceApplication {
      public static void main(String[] args) {
          SpringApplication.run(TaskServiceApplication.class, args);
      }
  }
  ```

- `application.properties`: `server.port=8082`, `spring.application.name=task-service`, and a datasource that points at
  the new database (default `jdbc:postgresql://localhost:5432/task`).

`TaskModule` already brings its own entities, repositories, controller, error handling and Flyway location, so no module
code changes. `backend/task`'s own tests (`TaskApiTests`) already boot the module on its own, which is the same shape as
this service.

`TaskModule` also brings `SecurityModule`, but the new service has no `iam`, so it starts with the fail-closed
resolver (every call 403, with a startup warning). Before routing traffic to it, add the REST `PermissionResolver`
described in follow-up 2 of `docs/superpowers/specs/2026-10-28-auth-keycloak-iam-design.md`, and give the service
`KEYCLOAK_ISSUER_URI`.

**2. Give it its own database.** Create the database and let Flyway build the schema from the module's migrations on first
start. To carry existing data over from the shared database:

```
createdb -h localhost -U app task
pg_dump -h localhost -U app --data-only --schema=task app | psql -h localhost -U app task
```

Start the service once before the `pg_dump | psql` step so the tables exist. For a production cutover, stop writes to the old
tables first (or run the copy during a maintenance window), then verify row counts match.

**3. Remove the module from the monolith.** In `backend/app`: delete the `backend-task` dependency from its `pom.xml` and
`TaskModule.class` from `@Import`. The `task` schema in the old database can be dropped after you have verified the new
service (keep a backup first).

**4. Route traffic to the new service.** Two options:

- *No gateway (what this skeleton assumes):* the UI module already reads its backend URL per module. In the shell's
  `application.properties`:

  ```
  app.modules.task.api-base-url=http://localhost:8082
  ```

  Every other module keeps using `app.api.base-url`.
- *With a gateway or reverse proxy:* route `/api/tasks/**` to the task service and everything else to `backend/app`. The
  UI needs no change because paths are already prefixed per module.

**Checklist**

- [ ] `./mvnw -pl backend/task-service -am verify` passes.
- [ ] Task service starts alone (`spring-boot:run`) and `GET :8082/api/tasks` works.
- [ ] `backend/app` starts without the task module and its other modules still work.
- [ ] The UI lists, creates, edits and deletes tasks through `:8082`.
- [ ] Nothing else in the repo imports `com.example.task.*`.

### B. Split a UI module into a micro-frontend

Goal: the task pages run as their own ZK app on port 8083; the shell shows them inside its layout.

**1. Create the standalone UI app.** Copy `frontend/shell` to `frontend/task-app` and change:

- `frontend/task-app/pom.xml`: `artifactId` to `frontend-task-app`; keep only the `frontend-task` dependency (plus actuator
  and test dependencies). Add `<module>task-app</module>` to `frontend/pom.xml`.
- The main class imports only this UI module:

  ```java
  @SpringBootApplication
  @Import(TaskUiModule.class)   // TaskUiModule already imports ZkSupport
  public class TaskAppApplication { ... }
  ```

- Serve the module's page as the home page, the same way the shell serves its own (`ShellWebConfig`):

  ```java
  registry.addViewController("/").setViewName("forward:/zkau/web/task/main.zul");
  ```

- `application.properties`: `server.port=8083`, `spring.application.name=task-app`, and the backend URL for the module
  (`app.modules.task.api-base-url=http://localhost:8082`, or `app.api.base-url` if it is still served by `backend/app`).

`main.zul` is a page fragment, so if the standalone app should look finished, wrap it in a small host page of its own
(`web/task-app/index.zul` that `<include>`s `~./task/main.zul` and adds a title and CSS) and forward `/` there instead.

**2. Remove the module from the shell and register it as remote.** In `frontend/shell`:

- Delete the `frontend-task` dependency from `pom.xml`.
- In `ShellApplication`, drop `TaskUiModule.class` from `@Import` (keep `ZkSupport.class`) and declare the remote module:

  ```java
  @Bean
  UiModule taskUi(@Value("${app.ui.task.url:http://localhost:8083}") String url) {
      return UiModule.remote("task", "Tasks", 10, url);
  }
  ```

The shell navigation and iframe already handle remote modules (`UiModule.isRemote()`); nothing else changes.

**3. Deploy behind one origin.** The iframe works across origins, but sessions, cookies and any future login are far easier
when the shell and the module share a domain. Put a reverse proxy in front, for example `/` to the shell and `/apps/task/`
to the task app, and set `app.ui.task.url=/apps/task/`. If the apps are on different origins instead, each app has its own
session; sharing a login across them needs SSO or tokens, which this skeleton does not set up. If you add Spring Security
later, allow framing from the shell's origin (`frame-ancestors` / `X-Frame-Options`), otherwise the iframe will be blank.

**Checklist**

- [ ] `./mvnw -pl frontend/task-app -am verify` passes.
- [ ] `task-app` runs alone and `http://localhost:8083/` shows the task page.
- [ ] The shell no longer has `frontend-task` on its classpath and still starts.
- [ ] Clicking "Tasks" in the shell shows the module in the iframe and all actions work.
- [ ] The iframe height in `frontend/shell/.../web/shell/app.css` (`.shell-remote`) suits the page.

### C. Splitting both

Do A first so the backend URL is stable, then B pointing `app.modules.task.api-base-url` at the new service. After both,
the task feature is two deployables (`task-service`, `task-app`) with no shared code except the REST API between them. The
DTOs are deliberately duplicated on each side; if the contract grows, generate the UI client from the service's OpenAPI
document (`/v3/api-docs`) instead of sharing a jar.

## ZK versions

Default is ZK 10.3.0.1 (latest stable, Maven Central). ZK 11 is not released; to try a nightly Eval build:
`./mvnw -Pzk11 package` (see the profile in the root `pom.xml`). Do not ship that to production.
