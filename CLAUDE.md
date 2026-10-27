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

## Gotchas

- ZUL must live on the classpath under `src/main/resources/web/<module>/` and be referenced as `~./<module>/...`.
  Putting ZUL in `META-INF/resources` works from the IDE but returns 404 from the fat jar. The shell's home page is
  served via `forward:/zkau/web/shell/index.zul`.
- ZK's EL cannot read record accessors (`title()`). Any record bound from ZUL needs JavaBean getters (`getTitle()`,
  `isDone()`); see `TaskDto` and `UiModule`.
- Jar names follow the artifactId: `backend-app-*.jar`, `frontend-shell-*.jar`.
- Run apps with `./mvnw install -DskipTests` first so module jars exist in the local repo, then
  `./mvnw -pl backend/app spring-boot:run` and `./mvnw -pl frontend/shell spring-boot:run`.

## Testing

- Backend tests use Testcontainers (Postgres), so Docker must be usable by your user (`docker ps` works without sudo).
  A new `docker` group membership only applies after logging in again.
- `backend/task` tests boot the module alone (`TaskApiTests.TestApp`); keep it that way so the module stays standalone.
- Frontend tests are context-load only. To exercise the UI without a database, run any stub on :8080 returning
  `[{"id":1,"title":"x","description":"","done":false,"createdAt":"2026-01-01T00:00:00Z"}]` for `GET /api/tasks`.

## Status

- Verified: whole build compiles on Boot 4.1.2; frontend tests pass; shell renders the ZK page and the task list over
  REST against a stub API, on both ZK 10.3.0.1 and the ZK 11 Eval build.
- **Not yet verified:** `backend/*` tests and the API against real Postgres (Flyway migration, `task` schema mapping,
  `@Generated` `createdAt`, endpoints). Run `./mvnw verify` once Docker access works, then fix anything it finds.
- Not built yet: authentication, pagination, a second example module, ArchUnit/Spring Modulith boundary tests.
