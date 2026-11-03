# Accounting Core (Phase 1) Frontend Implementation Plan

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** The ZK screens for accounting Phase 1: Journal, Accounts, Periods and Reports, inside the existing shell, on the merged REST API.

**Architecture:** One new UI module, `frontend/accounting`, entered through `AccountingUiModule`, which registers four `UiModule` beans. View models stay thin: they call one REST client and show errors; bookkeeping logic (totals, balance, row validation, tree order, CSV) lives in plain classes tested without ZK. The entry form is one self-contained modal page, opened from the journal list and from the periods screen. One small backend addition tells the screen when maker/checker blocks the current user.

**Tech Stack:** ZK 10.3 MVVM, Spring Boot 4.1.2, `RestClient`, Jackson 3, JUnit 5, AssertJ, Mockito.

**Spec:** `docs/superpowers/specs/2026-10-29-accounting-core-design.md`, section "Frontend" plus "Settled by the backend plan".

## Global Constraints

- UI modules never import backend classes; DTOs are duplicated on purpose. The REST API is the contract.
- ZK's EL cannot read record accessors: anything a ZUL page binds needs a JavaBean getter (`getCode()`, `isActive()`).
- ZUL lives under `src/main/resources/web/accounting/` and is referenced as `~./accounting/...`.
- View models catch `RuntimeException` around API calls and pass it to `ApiErrors.show(...)`.
- View models declare `@VariableResolver(SpringBeanVariableResolver.class)` and wire with `@WireVariable`. Beans are found by name: `accountingApiClient`, `currentUser`.
- Controls the user may not use are hidden or disabled; the backend enforces regardless.
- Amounts are `BigDecimal`, sent as JSON strings; shown with format `#,##0.00` and Indonesian separators (`1.665.000,00`).
- `POST .../journal-entries/{id}/post` and `DELETE .../journal-entries/{id}` take a required `?version=`; `PUT` carries `version` in its body. The version sent is the one on screen.
- All four `UiModule` beans require `accounting:read`, with orders Journal 20, Accounts 21, Periods 22, Reports 23. `ShellViewModel` needs no change.
- Keep today's behaviour on the open product questions: an earlier editor may post, a reversal may be reversed, no audit screen, Jakarta time on the server.
- Code style: tabs, existing import grouping, comments only where they explain why; tests use AssertJ.
- Frontend tests need no Docker. Task 1 is a backend change and does. Never touch the home-lab Postgres or Keycloak from a task.
- Run with `./mvnw -q -pl frontend/shell -am test`; backend module with `./mvnw -q -pl backend/accounting -am test`; the whole build with `./mvnw verify`.
- Commits: explicit paths only (never `.bloop`, `.metals`, `graphify-out`, `.superpowers`); messages end with a blank line and a `Co-Authored-By` line. Commits are signed through 1Password; never bypass signing.

## Decisions this plan settles (the spec is silent or differs)

- **Backend addition:** `EntryResponse` gains `posterBlocked`, computed for the current user by `GET`, `POST` and `PUT` on entries. The hint "You prepared this entry; another user must post it" must not compare display names, and subs stay out of the API.
- **Posting needs a saved draft:** Post is refused on screen while the form has unsaved changes, because the backend posts the saved version, not what the screen shows.
- **The entry form is a modal window**, not a page in the shell: the periods screen can open it for a blocking draft without the shell knowing, which keeps `ShellViewModel` unchanged.
- **The accounts tree is an indented list**, not a ZK `<tree>`: the same information with far less state, and the whole chart is visible at once.
- **The account combobox** binds its text and handles selection through a command rather than `selectedItem`, because a filtered model need not contain the selected account. Text matching no account leaves the line without one, and Save refuses it.
- **CSV cells starting with `=`, `+`, `-`, `@`, tab or carriage return are prefixed with `'`** when they are text, so a memo cannot run as a spreadsheet formula. Amounts stay numeric, with a dot. Files start with a UTF-8 BOM so Excel reads them correctly.
- **There is no delete button on accounts.** The spec's Accounts screen lists add child, edit, activate and deactivate; deactivating covers it.
- **`ApiErrors`** shows the problem `detail` for every 4xx that has one, not only 409 and 422: a 400 `invalid-amount` is just as useful to the user.

## Review Focus

- Text typed into the account box that matches no account: the line loses its account and Save names the line, rather than saving the previous account silently. Pinned in Task 3.
- Post pressed with unsaved edits on screen: refused with a message, never posting something other than what is shown. Pinned in Task 3 (the snapshot) and used in Task 4.
- A problem response that is not JSON or has no `detail` (a proxy's HTML page, an empty body): the generic message, not a crash inside the error handler. Pinned in Task 2.
- A memo or account name beginning with `=` in a CSV export: never a live formula, while negative amounts stay numeric. Pinned in Task 3.
- An account whose parent is missing or deactivated: still listed in the tree. Pinned in Task 3.

---

### Task 1: Tell the screen when maker/checker blocks this user

**Files:**
- Modify: `backend/accounting/src/main/java/com/example/accounting/EntryResponse.java`, `JournalService.java`
- Test: `backend/accounting/src/test/java/com/example/accounting/PostingTests.java`

**Interfaces:**
- Consumes: `JournalRules.mayPost(String, String, String, boolean)`, `AccountingSettings.current()`, `Actor.current()`.
- Produces: JSON field `posterBlocked` (boolean) on entries, consumed as `EntryDto.posterBlocked` in Task 2.

- [ ] **Step 1: Write the failing test**

Add to `PostingTests`:

```java
	@Test
	void anEntryTellsTheUserWhetherMakerCheckerBlocksThem() throws Exception {
		long id = balancedDraft("maker", DATE);
		String path = "/api/accounting/journal-entries/" + id;

		mvc.perform(get(path).with(as("maker", READ))).andExpect(jsonPath("$.posterBlocked").value(true));
		mvc.perform(get(path).with(as("checker", READ))).andExpect(jsonPath("$.posterBlocked").value(false));

		setSeparatePoster(false);
		mvc.perform(get(path).with(as("maker", READ))).andExpect(jsonPath("$.posterBlocked").value(false));

		setSeparatePoster(true);
		mvc.perform(post(path + "/post").param("version", String.valueOf(versionOf(id))).with(as("checker", READ, POST)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.posterBlocked").value(false));
		mvc.perform(get(path).with(as("maker", READ))).andExpect(jsonPath("$.posterBlocked").value(false));
	}
```

`versionOf` is in `AccountingTestSupport`; use its existing signature.

- [ ] **Step 2: Run the test to verify it fails**

Run: `./mvnw -q -pl backend/accounting -am test -Dtest=PostingTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: `No value at JSON path "$.posterBlocked"`.

- [ ] **Step 3: Implement**

In `EntryResponse.java`, add `boolean posterBlocked` as the last record component, after `lines`, and replace `from`:

```java
	static EntryResponse from(JournalEntry entry) {
		return from(entry, false);
	}

	/** {@code posterBlocked}: maker/checker stops the current user posting this draft, so the screen can say why. */
	static EntryResponse from(JournalEntry entry, boolean posterBlocked) {
		return new EntryResponse(entry.getId(), entry.getEntryNo(), entry.getEntryDate(), entry.getPeriodId(),
				entry.getReference(), entry.getDescription(), entry.getStatus(), entry.getReversalOf(),
				entry.getReversedBy(), entry.getCreatedByName(), entry.getCreatedAt(), entry.getLastModifiedByName(),
				entry.getLastModifiedAt(), entry.getPostedByName(), entry.getPostedAt(), entry.getVersion(),
				entry.totalDebit(), entry.getLines().stream().map(LineResponse::from).toList(), posterBlocked);
	}
```

In `JournalService.java`, make `get`, `create` and `update` return `response(entry)` instead of `EntryResponse.from(entry)` (for `create` and `update`, `response(entries.saveAndFlush(entry))`), and add:

```java
	private EntryResponse response(JournalEntry entry) {
		boolean separate = settings.current().map(Settings::isRequireSeparatePoster).orElse(true);
		boolean blocked = entry.getStatus() == EntryStatus.DRAFT && !JournalRules.mayPost(Actor.current().sub(),
				entry.getCreatedBySub(), entry.getLastModifiedBySub(), separate);
		return EntryResponse.from(entry, blocked);
	}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl backend/accounting -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add backend/accounting
git commit -m "Tell the screen when maker/checker blocks the current user

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 2: Module, REST client, DTOs and problem details

**Files:**
- Modify: `pom.xml` (dependencyManagement), `frontend/pom.xml` (module), `frontend/shell/pom.xml`, `frontend/shell/src/main/java/com/example/shell/ShellApplication.java`
- Modify: `frontend/common/src/main/java/com/example/ui/common/ApiErrors.java`
- Test: `frontend/common/src/test/java/com/example/ui/common/ApiErrorsTests.java`
- Create: `frontend/accounting/pom.xml`
- Create in `frontend/accounting/src/main/java/com/example/ui/accounting/`: `AccountingUiModule.java`, `AccountingApiClient.java`, `Idr.java`, `AccountDto.java`, `AccountRequest.java`, `SettingsDto.java`, `PeriodDto.java`, `LineDto.java`, `LineRequest.java`, `EntryDto.java`, `EntryRequest.java`, `EntrySummaryDto.java`, `EntryPageDto.java`, `EntryFilter.java`, `ReverseResultDto.java`, `TrialBalanceDto.java`, `GeneralLedgerDto.java`
- Test: `frontend/accounting/src/test/java/com/example/ui/accounting/AccountingApiClientTests.java`

**Interfaces:**
- Consumes: `ModuleRestClients.forModule(String)`, `UiModule.embedded(...)`.
- Produces:
  - `ApiErrors.code(RuntimeException)` → the problem's `code` or `""`; `ApiErrors.message(RuntimeException)` → the text to show.
  - `Idr.format(BigDecimal)` → `"1.665.000,00"`, or `""` for null.
  - `AccountingApiClient` (bean `accountingApiClient`), methods listed in Step 5.
  - DTO records as in Step 5.

- [ ] **Step 1: Write the failing `ApiErrors` tests**

Add to `ApiErrorsTests`:

```java
	private static HttpClientErrorException clientError(HttpStatus status, String body) {
		return HttpClientErrorException.create(status, status.getReasonPhrase(), new HttpHeaders(),
				body.getBytes(StandardCharsets.UTF_8), StandardCharsets.UTF_8);
	}

	@Test
	void aProblemsDetailAndCodeAreShown() {
		RuntimeException ex = clientError(HttpStatus.CONFLICT,
				"{\"detail\": \"Someone else changed this entry\", \"code\": \"version-conflict\"}");

		assertThat(ApiErrors.code(ex)).isEqualTo("version-conflict");
		assertThat(ApiErrors.message(ex)).isEqualTo("Someone else changed this entry");
	}

	@Test
	void aResponseThatIsNotAProblemFallsBackToTheGenericMessage() {
		for (String body : new String[] { "<html>Bad gateway</html>", "", "[1, 2]", "{\"detail\": \"\"}" }) {
			RuntimeException ex = clientError(HttpStatus.BAD_REQUEST, body);

			assertThat(ApiErrors.code(ex)).isEmpty();
			assertThat(ApiErrors.message(ex)).startsWith("API error: ");
		}
	}

	@Test
	void anErrorThatIsNotAClientErrorHasNoCode() {
		RuntimeException ex = new ResourceAccessException("Connection refused");

		assertThat(ApiErrors.code(ex)).isEmpty();
		assertThat(ApiErrors.message(ex)).isEqualTo("API error: Connection refused");
	}
```

Add `import java.nio.charset.StandardCharsets;` above the existing imports.

Run: `./mvnw -q -pl frontend/common -am test -Dtest=ApiErrorsTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile error, `cannot find symbol: method code`.

- [ ] **Step 2: Implement `ApiErrors`**

Add imports `org.springframework.web.client.HttpClientErrorException` (already there), `tools.jackson.core.JacksonException`, `tools.jackson.databind.JsonNode`, `tools.jackson.databind.json.JsonMapper`, `tools.jackson.databind.node.MissingNode`. Add these methods, and replace the `OTHER` branch of `show`:

```java
	/** The stable {@code code} of an RFC 9457 problem response, or empty. */
	public static String code(RuntimeException ex) {
		return problem(ex).path("code").asString("");
	}

	/** What to tell the user: the problem's {@code detail} when the API gave one. */
	public static String message(RuntimeException ex) {
		String detail = problem(ex).path("detail").asString("");
		return detail.isBlank() ? "API error: " + ex.getMessage() : detail;
	}

	private static JsonNode problem(RuntimeException ex) {
		if (ex instanceof HttpClientErrorException clientError) {
			try {
				return JsonMapper.shared().readTree(clientError.getResponseBodyAsString());
			}
			catch (JacksonException notJson) {
				return MissingNode.getInstance();
			}
		}
		return MissingNode.getInstance();
	}
```

```java
			case OTHER -> {
				if (code(ex).isEmpty()) {
					log.error("API call failed", ex);
					Clients.showNotification(message(ex), "error", null, "middle_center", 4000);
				}
				else {
					log.info("API refused the request: {}", ex.getMessage());
					Clients.showNotification(message(ex), "warning", null, "middle_center", 5000);
				}
			}
```

Run the same command. Expected: PASS.

- [ ] **Step 3: Wire the module into the build**

In `pom.xml`, after the `frontend-task` dependencyManagement entry:

```xml
			<dependency>
				<groupId>com.example</groupId>
				<artifactId>frontend-accounting</artifactId>
				<version>${project.version}</version>
			</dependency>
```

In `frontend/pom.xml`, add `<module>accounting</module>` between `task` and `shell`. In `frontend/shell/pom.xml`, after the `frontend-task` dependency:

```xml
		<dependency>
			<groupId>com.example</groupId>
			<artifactId>frontend-accounting</artifactId>
		</dependency>
```

In `ShellApplication.java`, add `import com.example.ui.accounting.AccountingUiModule;` above the `ZkSupport` import and change the annotation to `@Import({ ZkSupport.class, TaskUiModule.class, AccountingUiModule.class })`.

Create `frontend/accounting/pom.xml` as a copy of `frontend/task/pom.xml` with these changes:

```xml
	<artifactId>frontend-accounting</artifactId>
	<name>frontend-accounting</name>
	<description>Accounting UI module: ZUL pages, view models and the client for the accounting API</description>
```

and add inside `<dependencies>`, after `frontend-common`:

```xml
		<dependency>
			<groupId>org.springframework.boot</groupId>
			<artifactId>spring-boot-starter-webmvc-test</artifactId>
			<scope>test</scope>
		</dependency>
```

- [ ] **Step 4: Write the failing client test**

`AccountingApiClientTests.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.test.web.client.MockRestServiceServer;
import org.springframework.web.client.RestClient;

import com.example.ui.common.ModuleRestClients;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.when;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.content;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.method;
import static org.springframework.test.web.client.match.MockRestRequestMatchers.requestTo;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withStatus;
import static org.springframework.test.web.client.response.MockRestResponseCreators.withSuccess;

class AccountingApiClientTests {

	private static final String BASE = "http://api/api/accounting";

	private MockRestServiceServer server;

	private AccountingApiClient client;

	@BeforeEach
	void setUp() {
		RestClient.Builder builder = RestClient.builder().baseUrl("http://api");
		server = MockRestServiceServer.bindTo(builder).build();
		ModuleRestClients clients = mock(ModuleRestClients.class);
		when(clients.forModule("accounting")).thenReturn(builder.build());
		client = new AccountingApiClient(clients);
	}

	@Test
	void amountsArriveAsDecimalsAndFormatTheIndonesianWay() {
		server.expect(requestTo(BASE + "/journal-entries/7")).andRespond(withSuccess("""
				{"id": 7, "date": "2026-03-15", "periodId": 3, "description": "Sale", "status": "DRAFT", "version": 2,
				 "totalDebit": "1665000.00", "posterBlocked": true, "lines": [
				 {"lineNo": 1, "accountId": 11, "debit": "1665000.00", "credit": "0.00"}]}
				""", MediaType.APPLICATION_JSON));

		EntryDto entry = client.entry(7);

		assertThat(entry.lines().get(0).debit()).isEqualByComparingTo("1665000.00");
		assertThat(entry.getTotalDebitText()).isEqualTo("1.665.000,00");
		assertThat(entry.posterBlocked()).isTrue();
		server.verify();
	}

	@Test
	void postAndDeleteSendTheVersionOnScreen() {
		server.expect(requestTo(BASE + "/journal-entries/7/post?version=4")).andExpect(method(HttpMethod.POST))
				.andRespond(withSuccess("{\"id\": 7, \"status\": \"POSTED\", \"lines\": [], \"posterBlocked\": false}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/journal-entries/8?version=5")).andExpect(method(HttpMethod.DELETE))
				.andRespond(withStatus(org.springframework.http.HttpStatus.NO_CONTENT));

		assertThat(client.postEntry(7, 4).status()).isEqualTo("POSTED");
		client.deleteEntry(8, 5);

		server.verify();
	}

	@Test
	void amountsAreSentAsStrings() {
		server.expect(requestTo(BASE + "/journal-entries")).andExpect(method(HttpMethod.POST))
				.andExpect(content().json("""
						{"date": "2026-03-15", "description": "Sale",
						 "lines": [{"accountId": 11, "debit": "100.50", "credit": null, "memo": null}]}
						"""))
				.andRespond(withSuccess("{\"id\": 9, \"lines\": [], \"posterBlocked\": false}", MediaType.APPLICATION_JSON));

		client.createEntry(new EntryRequest(LocalDate.of(2026, 3, 15), null, "Sale",
				List.of(new LineRequest(11L, new BigDecimal("100.50"), null, null)), null));

		server.verify();
	}

	@Test
	void onlyTheFiltersInUseAreSent() {
		server.expect(requestTo(BASE + "/journal-entries?page=0&size=50")).andRespond(
				withSuccess("{\"items\": [], \"total\": 0, \"page\": 0, \"size\": 50}", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/journal-entries?from=2026-01-01&status=DRAFT&q=a%20b&page=1&size=50")).andRespond(
				withSuccess("{\"items\": [], \"total\": 0, \"page\": 1, \"size\": 50}", MediaType.APPLICATION_JSON));

		client.entries(new EntryFilter(null, null, null, null, null, 0, 50));
		client.entries(new EntryFilter(LocalDate.of(2026, 1, 1), null, "DRAFT", null, "a b", 1, 50));

		server.verify();
	}

}
```

Run: `./mvnw -q -pl frontend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile error, `cannot find symbol: class AccountingApiClient`.

- [ ] **Step 5: Write the module, DTOs and client**

`AccountingUiModule.java`:

```java
package com.example.ui.accounting;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.example.ui.common.UiModule;
import com.example.ui.common.ZkSupport;

/** Entry point of the accounting UI module. A deployable UI app includes it with {@code @Import}. */
@Configuration(proxyBeanMethods = false)
@Import(ZkSupport.class)
@ComponentScan(basePackageClasses = AccountingUiModule.class)
public class AccountingUiModule {

	static final String ID = "accounting";

	private static final String READ = "accounting:read";

	@Bean
	UiModule accountingJournalUi() {
		return UiModule.embedded("accounting-journal", "Journal", 20, "~./accounting/journal.zul").withPermission(READ);
	}

	@Bean
	UiModule accountingAccountsUi() {
		return UiModule.embedded("accounting-accounts", "Accounts", 21, "~./accounting/accounts.zul").withPermission(READ);
	}

	@Bean
	UiModule accountingPeriodsUi() {
		return UiModule.embedded("accounting-periods", "Periods", 22, "~./accounting/periods.zul").withPermission(READ);
	}

	@Bean
	UiModule accountingReportsUi() {
		return UiModule.embedded("accounting-reports", "Reports", 23, "~./accounting/reports.zul").withPermission(READ);
	}

}
```

`Idr.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.util.Locale;

/** Amounts as Indonesian readers expect them: 1.665.000,00. */
public final class Idr {

	private Idr() {
	}

	public static String format(BigDecimal amount) {
		if (amount == null) {
			return "";
		}
		// DecimalFormat is not thread-safe, so each call makes its own.
		return new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.forLanguageTag("id-ID"))).format(amount);
	}

}
```

`AccountDto.java`:

```java
package com.example.ui.accounting;

/** An account as returned by the REST API. */
public record AccountDto(Long id, String code, String name, String type, Long parentId, boolean postable,
		boolean active) {

	// JavaBean getters: ZK's EL cannot read record accessors.

	public Long getId() {
		return id;
	}

	public String getCode() {
		return code;
	}

	public String getName() {
		return name;
	}

	public String getType() {
		return type;
	}

	public boolean isPostable() {
		return postable;
	}

	public boolean isActive() {
		return active;
	}

	public String getLabel() {
		return code + " " + name;
	}

}
```

`AccountRequest.java`:

```java
package com.example.ui.accounting;

public record AccountRequest(String code, String name, String type, Long parentId, boolean postable, Boolean active) {
}
```

`SettingsDto.java`:

```java
package com.example.ui.accounting;

public record SettingsDto(boolean initialized, boolean requireSeparatePoster) {
}
```

`PeriodDto.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;
import java.time.Month;
import java.time.format.TextStyle;
import java.util.Locale;

public record PeriodDto(Long id, int year, int month, LocalDate startDate, LocalDate endDate, String status,
		String closedByName) {

	public Long getId() {
		return id;
	}

	public String getLabel() {
		return Month.of(month).getDisplayName(TextStyle.SHORT, Locale.ENGLISH) + " " + year;
	}

	public String getStatus() {
		return status;
	}

	public String getClosedByName() {
		return closedByName;
	}

	public boolean isOpen() {
		return "OPEN".equals(status);
	}

}
```

`LineDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;

public record LineDto(int lineNo, Long accountId, BigDecimal debit, BigDecimal credit, String memo) {
}
```

`LineRequest.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;

import com.fasterxml.jackson.annotation.JsonFormat;

public record LineRequest(Long accountId,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal debit,
		@JsonFormat(shape = JsonFormat.Shape.STRING) BigDecimal credit,
		String memo) {
}
```

`EntryDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record EntryDto(Long id, String entryNo, LocalDate date, Long periodId, String reference, String description,
		String status, Long reversalOf, Long reversedBy, String createdByName, String postedByName, Long version,
		BigDecimal totalDebit, List<LineDto> lines, boolean posterBlocked) {

	public String getTotalDebitText() {
		return Idr.format(totalDebit);
	}

}
```

`EntryRequest.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;

/** {@code version} is the one on screen when updating, null when creating. */
public record EntryRequest(LocalDate date, String reference, String description, List<LineRequest> lines,
		Long version) {
}
```

`EntrySummaryDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;

public record EntrySummaryDto(Long id, String entryNo, LocalDate date, String reference, String description,
		String status, BigDecimal totalDebit) {

	public Long getId() {
		return id;
	}

	public String getEntryNo() {
		return entryNo;
	}

	public String getDateText() {
		return date == null ? "" : date.toString();
	}

	public String getReference() {
		return reference;
	}

	public String getDescription() {
		return description;
	}

	public String getStatus() {
		return status;
	}

	public String getTotalDebitText() {
		return Idr.format(totalDebit);
	}

}
```

`EntryPageDto.java`:

```java
package com.example.ui.accounting;

import java.util.List;

public record EntryPageDto(List<EntrySummaryDto> items, long total, int page, int size) {
}
```

`EntryFilter.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;

/** Null or blank values are not sent. */
public record EntryFilter(LocalDate from, LocalDate to, String status, Long accountId, String q, int page, int size) {
}
```

`ReverseResultDto.java`:

```java
package com.example.ui.accounting;

public record ReverseResultDto(EntryDto reversal, Long draftId) {
}
```

`TrialBalanceDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record TrialBalanceDto(LocalDate asOf, List<Row> rows, BigDecimal totalDebit, BigDecimal totalCredit,
		BigDecimal difference) {

	public record Row(Long accountId, String code, String name, String type, BigDecimal debit, BigDecimal credit,
			BigDecimal balance) {

		public String getCode() {
			return code;
		}

		public String getName() {
			return name;
		}

		public String getDebitText() {
			return Idr.format(debit);
		}

		public String getCreditText() {
			return Idr.format(credit);
		}

		public String getBalanceText() {
			return Idr.format(balance);
		}

	}

}
```

`GeneralLedgerDto.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

public record GeneralLedgerDto(Long accountId, String accountCode, String accountName, String type, LocalDate from,
		LocalDate to, BigDecimal openingBalance, List<Line> lines, BigDecimal closingBalance) {

	public record Line(LocalDate date, Long entryId, String entryNo, String description, String memo, BigDecimal debit,
			BigDecimal credit, BigDecimal runningBalance) {

		public String getDateText() {
			return date == null ? "" : date.toString();
		}

		public String getEntryNo() {
			return entryNo;
		}

		public String getDescription() {
			return description;
		}

		public String getMemo() {
			return memo;
		}

		public String getDebitText() {
			return Idr.format(debit);
		}

		public String getCreditText() {
			return Idr.format(credit);
		}

		public String getRunningBalanceText() {
			return Idr.format(runningBalance);
		}

	}

}
```

`AccountingApiClient.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.Optional;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.ui.common.ModuleRestClients;

@Component
public class AccountingApiClient {

	private static final String PATH = "/api/accounting";

	private static final String ENTRIES = PATH + "/journal-entries";

	private final RestClient client;

	public AccountingApiClient(ModuleRestClients clients) {
		this.client = clients.forModule(AccountingUiModule.ID);
	}

	public SettingsDto settings() {
		return client.get().uri(PATH + "/settings").retrieve().body(SettingsDto.class);
	}

	public SettingsDto setup() {
		return client.post().uri(PATH + "/setup").retrieve().body(SettingsDto.class);
	}

	public SettingsDto updateSettings(boolean requireSeparatePoster) {
		return client.put().uri(PATH + "/settings").body(Map.of("requireSeparatePoster", requireSeparatePoster))
				.retrieve().body(SettingsDto.class);
	}

	public List<AccountDto> accounts() {
		return client.get().uri(PATH + "/accounts").retrieve().body(new ParameterizedTypeReference<>() {
		});
	}

	public AccountDto createAccount(AccountRequest request) {
		return client.post().uri(PATH + "/accounts").body(request).retrieve().body(AccountDto.class);
	}

	public AccountDto updateAccount(long id, AccountRequest request) {
		return client.put().uri(PATH + "/accounts/{id}", id).body(request).retrieve().body(AccountDto.class);
	}

	public List<PeriodDto> periods(int year) {
		return client.get().uri(PATH + "/periods?year={year}", year).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
	}

	public List<PeriodDto> openYear(int year) {
		return client.post().uri(PATH + "/periods/year/{year}", year).retrieve()
				.body(new ParameterizedTypeReference<>() {
				});
	}

	public PeriodDto closePeriod(long id) {
		return client.post().uri(PATH + "/periods/{id}/close", id).retrieve().body(PeriodDto.class);
	}

	public PeriodDto reopenPeriod(long id, String reason) {
		return client.post().uri(PATH + "/periods/{id}/reopen", id).body(Map.of("reason", reason)).retrieve()
				.body(PeriodDto.class);
	}

	public EntryPageDto entries(EntryFilter filter) {
		return client.get().uri(uri -> uri.path(ENTRIES)
				.queryParamIfPresent("from", Optional.ofNullable(filter.from()))
				.queryParamIfPresent("to", Optional.ofNullable(filter.to()))
				.queryParamIfPresent("status", Optional.ofNullable(filter.status()).filter(s -> !s.isBlank()))
				.queryParamIfPresent("accountId", Optional.ofNullable(filter.accountId()))
				.queryParamIfPresent("q", Optional.ofNullable(filter.q()).filter(q -> !q.isBlank()))
				.queryParam("page", filter.page())
				.queryParam("size", filter.size())
				.build()).retrieve().body(EntryPageDto.class);
	}

	public EntryDto entry(long id) {
		return client.get().uri(ENTRIES + "/{id}", id).retrieve().body(EntryDto.class);
	}

	public EntryDto createEntry(EntryRequest request) {
		return client.post().uri(ENTRIES).body(request).retrieve().body(EntryDto.class);
	}

	public EntryDto updateEntry(long id, EntryRequest request) {
		return client.put().uri(ENTRIES + "/{id}", id).body(request).retrieve().body(EntryDto.class);
	}

	public void deleteEntry(long id, long version) {
		client.delete().uri(ENTRIES + "/{id}?version={version}", id, version).retrieve().toBodilessEntity();
	}

	public EntryDto postEntry(long id, long version) {
		return client.post().uri(ENTRIES + "/{id}/post?version={version}", id, version).retrieve().body(EntryDto.class);
	}

	public ReverseResultDto reverseEntry(long id, LocalDate date, boolean copyToDraft) {
		Map<String, Object> body = date == null ? Map.of("copyToDraft", copyToDraft)
				: Map.of("date", date.toString(), "copyToDraft", copyToDraft);
		return client.post().uri(ENTRIES + "/{id}/reverse", id).body(body).retrieve().body(ReverseResultDto.class);
	}

	public TrialBalanceDto trialBalance(LocalDate asOf) {
		return client.get().uri(PATH + "/reports/trial-balance?asOf={asOf}", asOf).retrieve().body(TrialBalanceDto.class);
	}

	public GeneralLedgerDto generalLedger(long accountId, LocalDate from, LocalDate to) {
		return client.get().uri(PATH + "/reports/general-ledger?accountId={account}&from={from}&to={to}", accountId, from, to)
				.retrieve().body(GeneralLedgerDto.class);
	}

}
```

- [ ] **Step 6: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: BUILD SUCCESS, including `ApiErrorsTests`, `AccountingApiClientTests`, and the shell's context-load tests with the new module imported.

- [ ] **Step 7: Commit**

```bash
git add pom.xml frontend/pom.xml frontend/shell/pom.xml frontend/shell/src/main/java/com/example/shell/ShellApplication.java frontend/common/src/main/java/com/example/ui/common/ApiErrors.java frontend/common/src/test/java/com/example/ui/common/ApiErrorsTests.java frontend/accounting
git commit -m "Add the accounting UI module, its REST client and problem details in errors

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 3: Bookkeeping logic without ZK

**Files:**
- Create in `frontend/accounting/src/main/java/com/example/ui/accounting/`: `EntryLine.java`, `EntryForm.java`, `AccountMatcher.java`, `AccountRow.java`, `AccountTree.java`, `Csv.java`
- Test in `frontend/accounting/src/test/java/com/example/ui/accounting/`: `EntryFormTests.java`, `AccountMatcherTests.java`, `AccountTreeTests.java`, `CsvTests.java`

**Interfaces:**
- Consumes: DTOs and `Idr` from Task 2.
- Produces:
  - `EntryLine`: public getters and setters `account`, `debit`, `credit`, `memo`; `getAccountLabel()`; `pickByLabel(String, List<AccountDto>)`; `isBlank()`.
  - `EntryForm`: `getDate/setDate`, `getReference/setReference`, `getDescription/setDescription`, `getLines()`; `addLine()`, `totalDebit()`, `totalCredit()`, `difference()`, `isBalanced()`, `balance(EntryLine)`, `problem()` → first error text or null, `toRequest(Long version)`, `snapshot()`; static `from(EntryDto, Map<Long, AccountDto>)`, `blank(LocalDate)`.
  - `AccountMatcher implements Comparator<Object>`.
  - `AccountRow(AccountDto account, int depth)` with `getAccount()`, `getIndentStyle()`; `AccountTree.rows(List<AccountDto>)`.
  - `Csv.trialBalance(TrialBalanceDto)`, `Csv.generalLedger(GeneralLedgerDto)` → `byte[]`.

- [ ] **Step 1: Write the failing tests**

`EntryFormTests.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class EntryFormTests {

	private static final AccountDto CASH = new AccountDto(11L, "1110", "Kas", "ASSET", 10L, true, true);

	private static final AccountDto SALES = new AccountDto(41L, "4100", "Penjualan", "REVENUE", 40L, true, true);

	private static final LocalDate DATE = LocalDate.of(2026, 3, 15);

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private static EntryLine line(AccountDto account, String debit, String credit) {
		EntryLine line = new EntryLine();
		line.setAccount(account);
		line.setDebit(debit == null ? null : money(debit));
		line.setCredit(credit == null ? null : money(credit));
		return line;
	}

	private static EntryForm form(EntryLine... lines) {
		EntryForm form = EntryForm.blank(DATE);
		form.setDescription("Sale");
		form.getLines().clear();
		form.getLines().addAll(List.of(lines));
		return form;
	}

	@Test
	void aBlankFormStartsWithTwoEmptyLines() {
		assertThat(EntryForm.blank(DATE).getLines()).hasSize(2).allMatch(EntryLine::isBlank);
	}

	@Test
	void typingOneSideClearsTheOther() {
		EntryLine line = line(CASH, "100", null);

		line.setCredit(money("5"));
		assertThat(line.getDebit()).isNull();
		assertThat(line.getCredit()).isEqualByComparingTo("5");

		line.setDebit(money("7"));
		assertThat(line.getCredit()).isNull();
	}

	@Test
	void clearingOrZeroingASideLeavesTheOtherAlone() {
		EntryLine line = line(CASH, "100", null);

		line.setCredit(null);
		assertThat(line.getDebit()).isEqualByComparingTo("100");
		line.setCredit(BigDecimal.ZERO);
		assertThat(line.getDebit()).isEqualByComparingTo("100");
		assertThat(line.getCredit()).isNull();
	}

	@Test
	void totalsAndBalanceCountEmptySidesAsZero() {
		EntryForm form = form(line(CASH, "100.50", null), line(SALES, null, "100.5"), new EntryLine());

		assertThat(form.totalDebit()).isEqualByComparingTo("100.50");
		assertThat(form.totalCredit()).isEqualByComparingTo("100.50");
		assertThat(form.difference()).isEqualByComparingTo("0");
		assertThat(form.isBalanced()).isTrue();
	}

	@Test
	void nothingEnteredIsNotBalanced() {
		assertThat(EntryForm.blank(DATE).isBalanced()).isFalse();
	}

	@Test
	void theBalanceButtonPutsTheDifferenceOnTheCurrentLine() {
		EntryLine sales = line(SALES, null, null);
		EntryForm form = form(line(CASH, "100", null), sales);

		form.balance(sales);
		assertThat(sales.getCredit()).isEqualByComparingTo("100");
		assertThat(form.isBalanced()).isTrue();

		EntryLine cash = line(CASH, null, null);
		EntryForm other = form(line(SALES, null, "40"), cash);
		other.balance(cash);
		assertThat(cash.getDebit()).isEqualByComparingTo("40");
	}

	@Test
	void theBalanceButtonReplacesWhatTheCurrentLineHeld() {
		EntryLine sales = line(SALES, "999", null);
		EntryForm form = form(line(CASH, "100", null), sales);

		form.balance(sales);

		assertThat(sales.getDebit()).isNull();
		assertThat(sales.getCredit()).isEqualByComparingTo("100");
	}

	@Test
	void theBalanceButtonClearsTheLineWhenTheOthersAlreadyBalance() {
		EntryLine extra = line(SALES, "5", null);
		EntryForm form = form(line(CASH, "100", null), line(SALES, null, "100"), extra);

		form.balance(extra);

		assertThat(extra.getDebit()).isNull();
		assertThat(extra.getCredit()).isNull();
		assertThat(form.isBalanced()).isTrue();
	}

	@Test
	void textMatchingNoAccountLeavesTheLineWithoutOne() {
		EntryLine line = line(CASH, "100", null);

		line.pickByLabel("nothing like this", List.of(CASH, SALES));
		assertThat(line.getAccount()).isNull();
		assertThat(line.getAccountLabel()).isEmpty();

		line.pickByLabel("4100 Penjualan", List.of(CASH, SALES));
		assertThat(line.getAccount()).isEqualTo(SALES);
	}

	@Test
	void problemsNameTheLine() {
		assertThat(form(line(CASH, "1", null), line(SALES, null, "1")).problem()).isNull();
		assertThat(form(line(null, "1", null)).problem()).isEqualTo("Line 1: choose an account");
		assertThat(form(new EntryLine(), line(CASH, null, null)).problem()).isEqualTo("Line 2: enter a debit or a credit");

		EntryForm noDescription = form(line(CASH, "1", null));
		noDescription.setDescription("  ");
		assertThat(noDescription.problem()).isEqualTo("Enter a description");

		EntryForm noDate = form(line(CASH, "1", null));
		noDate.setDate(null);
		assertThat(noDate.problem()).isEqualTo("Enter a date");
	}

	@Test
	void theRequestDropsBlankLinesAndCarriesTheVersion() {
		EntryForm form = form(new EntryLine(), line(CASH, "1.5", null), new EntryLine());
		form.getLines().get(1).setMemo("note");

		EntryRequest request = form.toRequest(4L);

		assertThat(request.version()).isEqualTo(4L);
		assertThat(request.description()).isEqualTo("Sale");
		assertThat(request.lines()).containsExactly(new LineRequest(11L, money("1.5"), null, "note"));
	}

	@Test
	void anEntryLoadsWithItsAccountsAndZeroSidesEmpty() {
		EntryDto dto = new EntryDto(7L, null, DATE, 3L, "R", "Sale", "DRAFT", null, null, "maker", null, 2L, money("5.00"),
				List.of(new LineDto(1, 11L, money("5.00"), money("0.00"), "m"), new LineDto(2, 41L, money("0.00"), money("5.00"), null)),
				false);

		EntryForm form = EntryForm.from(dto, Map.of(11L, CASH, 41L, SALES));

		assertThat(form.getReference()).isEqualTo("R");
		assertThat(form.getLines()).hasSize(2);
		assertThat(form.getLines().get(0).getAccount()).isEqualTo(CASH);
		assertThat(form.getLines().get(0).getCredit()).isNull();
		assertThat(form.getLines().get(1).getCredit()).isEqualByComparingTo("5");
		assertThat(form.isBalanced()).isTrue();
	}

	@Test
	void theSnapshotChangesWithAnyEditAndIgnoresAmountScale() {
		EntryForm form = form(line(CASH, "5", null), line(SALES, null, "5"));
		String saved = form.snapshot();

		assertThat(form(line(CASH, "5.00", null), line(SALES, null, "5.0")).snapshot()).isEqualTo(saved);

		form.getLines().get(0).setDebit(money("6"));
		assertThat(form.snapshot()).isNotEqualTo(saved);
		form.getLines().get(0).setDebit(money("5"));
		assertThat(form.snapshot()).isEqualTo(saved);

		form.setReference("x");
		assertThat(form.snapshot()).isNotEqualTo(saved);
		form.setReference(null);

		form.getLines().get(1).setMemo("x");
		assertThat(form.snapshot()).isNotEqualTo(saved);
	}

}
```

`AccountMatcherTests.java`:

```java
package com.example.ui.accounting;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountMatcherTests {

	private static final AccountDto CASH = new AccountDto(11L, "1110", "Kas Kecil", "ASSET", 10L, true, true);

	private final AccountMatcher matcher = new AccountMatcher();

	@Test
	void matchesByCodeOrNameAnywhereIgnoringCase() {
		assertThat(matcher.compare("1110", CASH)).isZero();
		assertThat(matcher.compare("11", CASH)).isZero();
		assertThat(matcher.compare("kecil", CASH)).isZero();
		assertThat(matcher.compare(" KAS ", CASH)).isZero();
		assertThat(matcher.compare("1110 Kas Kecil", CASH)).isZero();
		assertThat(matcher.compare("", CASH)).isZero();
		assertThat(matcher.compare(null, CASH)).isZero();
	}

	@Test
	void anythingElseDoesNotMatch() {
		assertThat(matcher.compare("bank", CASH)).isNotZero();
	}

}
```

`AccountTreeTests.java`:

```java
package com.example.ui.accounting;

import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class AccountTreeTests {

	private static AccountDto account(long id, String code, Long parent) {
		return new AccountDto(id, code, "N" + code, "ASSET", parent, parent != null, true);
	}

	private static List<String> codes(List<AccountRow> rows) {
		return rows.stream().map(row -> row.getAccount().code() + ":" + row.depth()).toList();
	}

	@Test
	void childrenFollowTheirParentInCodeOrder() {
		List<AccountRow> rows = AccountTree.rows(List.of(account(4, "2000", null), account(3, "1120", 2L),
				account(5, "1110", 2L), account(2, "1100", 1L), account(1, "1000", null), account(6, "2100", 4L)));

		assertThat(codes(rows)).containsExactly("1000:0", "1100:1", "1110:2", "1120:2", "2000:0", "2100:1");
	}

	@Test
	void anAccountWhoseParentIsMissingIsStillListed() {
		assertThat(codes(AccountTree.rows(List.of(account(1, "1000", null), account(9, "9000", 99L)))))
				.containsExactly("1000:0", "9000:0");
	}

	@Test
	void indentationGrowsWithDepth() {
		assertThat(new AccountRow(account(1, "1", null), 0).getIndentStyle()).isEqualTo("padding-left: 0px");
		assertThat(new AccountRow(account(1, "1", null), 2).getIndentStyle()).isEqualTo("padding-left: 40px");
	}

}
```

`CsvTests.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class CsvTests {

	private static BigDecimal money(String value) {
		return new BigDecimal(value);
	}

	private static String text(byte[] csv) {
		return new String(csv, StandardCharsets.UTF_8);
	}

	private static TrialBalanceDto trialBalance(String name, String balance) {
		return new TrialBalanceDto(LocalDate.of(2026, 3, 31),
				List.of(new TrialBalanceDto.Row(1L, "1290", name, "ASSET", money("0.00"), money("5.00"), money(balance))),
				money("0.00"), money("5.00"), money("0.00"));
	}

	@Test
	void trialBalanceHasAHeaderRowsAndTotals() {
		String csv = text(Csv.trialBalance(trialBalance("Akumulasi", "-5.00")));

		assertThat(csv).startsWith("﻿");
		assertThat(csv.substring(1).split("\r\n")).containsExactly("Code,Name,Type,Debit,Credit,Balance",
				"1290,Akumulasi,ASSET,0.00,5.00,-5.00", "Total,,,0.00,5.00,0.00");
	}

	@Test
	void textThatLooksLikeAFormulaCannotRun() {
		for (String risky : new String[] { "=HYPERLINK(1)", "+1", "-1", "@x", "\tx", "\rx" }) {
			String csv = text(Csv.trialBalance(trialBalance(risky, "1.00")));

			assertThat(csv).as(risky).contains("'" + risky.charAt(0));
		}
	}

	@Test
	void negativeAmountsStayNumeric() {
		assertThat(text(Csv.trialBalance(trialBalance("x", "-5.00")))).contains(",-5.00").doesNotContain("'-");
	}

	@Test
	void separatorsAndQuotesAreEscaped() {
		assertThat(text(Csv.trialBalance(trialBalance("a,b\"c\nd", "1.00")))).contains("\"a,b\"\"c\nd\"");
	}

	@Test
	void generalLedgerHasOpeningLinesAndClosing() {
		GeneralLedgerDto ledger = new GeneralLedgerDto(1L, "1110", "Kas", "ASSET", LocalDate.of(2026, 2, 1),
				LocalDate.of(2026, 2, 28), money("100.00"),
				List.of(new GeneralLedgerDto.Line(LocalDate.of(2026, 2, 5), 9L, "JE-2026-000002", "Sale", null, money("40.00"),
						money("0.00"), money("140.00"))),
				money("140.00"));

		assertThat(text(Csv.generalLedger(ledger)).substring(1).split("\r\n")).containsExactly(
				"Date,Entry,Description,Memo,Debit,Credit,Balance", "Opening balance,,,,,,100.00",
				"2026-02-05,JE-2026-000002,Sale,,40.00,0.00,140.00", "Closing balance,,,,,,140.00");
	}

}
```

- [ ] **Step 2: Run the tests to verify they fail**

Run: `./mvnw -q -pl frontend/accounting -am test -Dsurefire.failIfNoSpecifiedTests=false`
Expected: compile error, `cannot find symbol: class EntryLine`.

- [ ] **Step 3: Write the classes**

`EntryLine.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.util.List;

/** One editable row of the entry form. A JavaBean, because ZK's EL cannot read record accessors. */
public class EntryLine {

	private AccountDto account;

	private BigDecimal debit;

	private BigDecimal credit;

	private String memo;

	public AccountDto getAccount() {
		return account;
	}

	public void setAccount(AccountDto account) {
		this.account = account;
	}

	public String getAccountLabel() {
		return account == null ? "" : account.getLabel();
	}

	/** Text matching no account leaves the line without one, so Save refuses it instead of keeping the old account. */
	public void pickByLabel(String text, List<AccountDto> options) {
		account = options.stream().filter(option -> option.getLabel().equals(text)).findFirst().orElse(null);
	}

	public BigDecimal getDebit() {
		return debit;
	}

	/** Typing an amount on one side clears the other; an entry line has exactly one side. */
	public void setDebit(BigDecimal value) {
		debit = positiveOrNull(value);
		if (debit != null) {
			credit = null;
		}
	}

	public BigDecimal getCredit() {
		return credit;
	}

	public void setCredit(BigDecimal value) {
		credit = positiveOrNull(value);
		if (credit != null) {
			debit = null;
		}
	}

	public String getMemo() {
		return memo;
	}

	public void setMemo(String memo) {
		this.memo = memo;
	}

	public boolean isBlank() {
		return account == null && debit == null && credit == null && (memo == null || memo.isBlank());
	}

	private static BigDecimal positiveOrNull(BigDecimal value) {
		return value == null || value.signum() <= 0 ? null : value;
	}

}
```

`EntryForm.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

/** What the entry form holds and the rules that need no ZK: totals, the Balance button, row checks. */
public class EntryForm {

	private LocalDate date;

	private String reference;

	private String description;

	private final List<EntryLine> lines = new ArrayList<>();

	public static EntryForm blank(LocalDate date) {
		EntryForm form = new EntryForm();
		form.date = date;
		form.addLine();
		form.addLine();
		return form;
	}

	public static EntryForm from(EntryDto entry, Map<Long, AccountDto> accounts) {
		EntryForm form = new EntryForm();
		form.date = entry.date();
		form.reference = entry.reference();
		form.description = entry.description();
		for (LineDto dto : entry.lines()) {
			EntryLine line = new EntryLine();
			line.setAccount(accounts.get(dto.accountId()));
			line.setDebit(dto.debit());
			line.setCredit(dto.credit());
			line.setMemo(dto.memo());
			form.lines.add(line);
		}
		return form;
	}

	public EntryLine addLine() {
		EntryLine line = new EntryLine();
		lines.add(line);
		return line;
	}

	public BigDecimal totalDebit() {
		return sum(lines, EntryLine::getDebit);
	}

	public BigDecimal totalCredit() {
		return sum(lines, EntryLine::getCredit);
	}

	/** Debit minus credit. */
	public BigDecimal difference() {
		return totalDebit().subtract(totalCredit());
	}

	public boolean isBalanced() {
		return difference().signum() == 0 && totalDebit().signum() > 0;
	}

	/** Makes the whole entry balance by changing only this line. */
	public void balance(EntryLine line) {
		List<EntryLine> others = lines.stream().filter(other -> other != line).toList();
		BigDecimal gap = sum(others, EntryLine::getDebit).subtract(sum(others, EntryLine::getCredit));
		line.setDebit(null);
		line.setCredit(null);
		if (gap.signum() > 0) {
			line.setCredit(gap);
		}
		else if (gap.signum() < 0) {
			line.setDebit(gap.negate());
		}
	}

	/** The first thing stopping a save, in words a bookkeeper can act on, or null. */
	public String problem() {
		if (date == null) {
			return "Enter a date";
		}
		if (description == null || description.isBlank()) {
			return "Enter a description";
		}
		for (int i = 0; i < lines.size(); i++) {
			EntryLine line = lines.get(i);
			if (line.isBlank()) {
				continue;
			}
			if (line.getAccount() == null) {
				return "Line " + (i + 1) + ": choose an account";
			}
			if (line.getDebit() == null && line.getCredit() == null) {
				return "Line " + (i + 1) + ": enter a debit or a credit";
			}
		}
		return null;
	}

	public EntryRequest toRequest(Long version) {
		List<LineRequest> requests = lines.stream().filter(line -> !line.isBlank())
				.map(line -> new LineRequest(line.getAccount().id(), line.getDebit(), line.getCredit(), line.getMemo()))
				.toList();
		return new EntryRequest(date, reference, description.strip(), requests, version);
	}

	/** Compares what is on screen with what was saved, so Post can refuse unsaved edits. */
	public String snapshot() {
		String rows = lines.stream().filter(line -> !line.isBlank())
				.map(line -> line.getAccountLabel() + "|" + plain(line.getDebit()) + "|" + plain(line.getCredit()) + "|"
						+ orEmpty(line.getMemo()))
				.collect(Collectors.joining("\n"));
		return date + "\n" + orEmpty(reference) + "\n" + orEmpty(description) + "\n" + rows;
	}

	public LocalDate getDate() {
		return date;
	}

	public void setDate(LocalDate date) {
		this.date = date;
	}

	public String getReference() {
		return reference;
	}

	public void setReference(String reference) {
		this.reference = reference;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public List<EntryLine> getLines() {
		return lines;
	}

	private static BigDecimal sum(List<EntryLine> lines, Function<EntryLine, BigDecimal> side) {
		return lines.stream().map(side).filter(amount -> amount != null).reduce(BigDecimal.ZERO, BigDecimal::add);
	}

	private static String plain(BigDecimal amount) {
		return amount == null ? "" : amount.stripTrailingZeros().toPlainString();
	}

	private static String orEmpty(String text) {
		return text == null ? "" : text;
	}

}
```

`AccountMatcher.java`:

```java
package com.example.ui.accounting;

import java.util.Comparator;

/**
 * Lets a combobox search accounts by code or name. ZK calls {@code compare(typedValue, option)} and keeps options
 * that return 0.
 */
public class AccountMatcher implements Comparator<Object> {

	@Override
	public int compare(Object typed, Object option) {
		String key = typed == null ? "" : typed.toString().strip().toLowerCase();
		return ((AccountDto) option).getLabel().toLowerCase().contains(key) ? 0 : 1;
	}

}
```

`AccountRow.java`:

```java
package com.example.ui.accounting;

public record AccountRow(AccountDto account, int depth) {

	public AccountDto getAccount() {
		return account;
	}

	public String getIndentStyle() {
		return "padding-left: " + depth * 20 + "px";
	}

}
```

`AccountTree.java`:

```java
package com.example.ui.accounting;

import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.stream.Collectors;

/** Orders the flat account list so each account follows its parent, with its depth for indentation. */
final class AccountTree {

	private AccountTree() {
	}

	static List<AccountRow> rows(List<AccountDto> accounts) {
		Set<Long> ids = accounts.stream().map(AccountDto::id).collect(Collectors.toSet());
		// An account whose parent is not in the list is still shown, as a root.
		Map<Long, List<AccountDto>> children = accounts.stream()
				.filter(account -> ids.contains(account.parentId()))
				.collect(Collectors.groupingBy(AccountDto::parentId));
		List<AccountRow> rows = new ArrayList<>();
		accounts.stream()
				.filter(account -> !ids.contains(account.parentId()))
				.sorted(Comparator.comparing(AccountDto::code))
				.forEach(root -> add(root, 0, children, rows));
		return rows;
	}

	private static void add(AccountDto account, int depth, Map<Long, List<AccountDto>> children, List<AccountRow> rows) {
		rows.add(new AccountRow(account, depth));
		children.getOrDefault(account.id(), List.of()).stream()
				.sorted(Comparator.comparing(AccountDto::code))
				.forEach(child -> add(child, depth + 1, children, rows));
	}

}
```

`Csv.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

/** Report exports that open safely and correctly in a spreadsheet. */
final class Csv {

	private static final String BOM = "﻿";

	private static final String RISKY_START = "=+-@\t\r";

	private Csv() {
	}

	static byte[] trialBalance(TrialBalanceDto report) {
		List<String> rows = new ArrayList<>();
		rows.add("Code,Name,Type,Debit,Credit,Balance");
		for (TrialBalanceDto.Row row : report.rows()) {
			rows.add(line(text(row.code()), text(row.name()), text(row.type()), amount(row.debit()), amount(row.credit()),
					amount(row.balance())));
		}
		rows.add(line("Total", "", "", amount(report.totalDebit()), amount(report.totalCredit()), amount(report.difference())));
		return bytes(rows);
	}

	static byte[] generalLedger(GeneralLedgerDto report) {
		List<String> rows = new ArrayList<>();
		rows.add("Date,Entry,Description,Memo,Debit,Credit,Balance");
		rows.add(line("Opening balance", "", "", "", "", "", amount(report.openingBalance())));
		for (GeneralLedgerDto.Line row : report.lines()) {
			rows.add(line(row.getDateText(), text(row.entryNo()), text(row.description()), text(row.memo()), amount(row.debit()),
					amount(row.credit()), amount(row.runningBalance())));
		}
		rows.add(line("Closing balance", "", "", "", "", "", amount(report.closingBalance())));
		return bytes(rows);
	}

	private static byte[] bytes(List<String> rows) {
		return (BOM + String.join("\r\n", rows) + "\r\n").getBytes(StandardCharsets.UTF_8);
	}

	private static String line(String... cells) {
		return String.join(",", cells);
	}

	private static String amount(BigDecimal value) {
		return value == null ? "" : value.toPlainString();
	}

	/** A cell a spreadsheet would run as a formula gets a leading apostrophe; separators and quotes are escaped. */
	private static String text(String value) {
		if (value == null || value.isEmpty()) {
			return "";
		}
		String safe = RISKY_START.indexOf(value.charAt(0)) >= 0 ? "'" + value : value;
		return safe.contains(",") || safe.contains("\"") || safe.contains("\n") || safe.contains("\r")
				? "\"" + safe.replace("\"", "\"\"") + "\""
				: safe;
	}

}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/accounting -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add frontend/accounting
git commit -m "Add the entry form rules, account search, tree order and CSV export

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 4: Journal list and entry form

**Files:**
- Create in `frontend/accounting/src/main/java/com/example/ui/accounting/`: `JournalViewModel.java`, `EntryViewModel.java`
- Create in `frontend/accounting/src/main/resources/web/accounting/`: `accounting.css`, `journal.zul`, `entry.zul`
- Test: `frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java`

**Interfaces:**
- Consumes: `AccountingApiClient`, `EntryForm`, `EntryLine`, `AccountMatcher`, `ApiErrors.code/message/show`, `CurrentUser.can(String)`.
- Produces: global command `accountingEntryChanged` (posted after any change to an entry; Task 6 listens to it); `EntryViewModel.open(Long entryId)` static, which opens the entry modal from any view model (Task 6 uses it); test class `AccountingScreensRenderTests` with `render(String)` and shared mocks (Tasks 5–7 add tests).

The render test starts the real shell on a random port, with the REST client and current user mocked, and fetches each page through ZK's servlets. A broken binding makes ZK answer with a redirect instead of 200, and data from the mocks must appear in the HTML. This was tried on the existing task page before writing this plan. It is the only automated check that a ZUL page works at all; MockMvc does not run ZK's servlets.

- [ ] **Step 1: Write the failing render test**

`AccountingScreensRenderTests.java`:

```java
package com.example.shell;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.LocalDate;
import java.util.List;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Import;
import org.springframework.core.annotation.Order;
import org.springframework.security.config.annotation.web.builders.HttpSecurity;
import org.springframework.security.web.SecurityFilterChain;
import org.springframework.test.context.bean.override.mockito.MockitoBean;

import com.example.ui.accounting.AccountDto;
import com.example.ui.accounting.AccountingApiClient;
import com.example.ui.accounting.EntryPageDto;
import com.example.ui.accounting.EntrySummaryDto;
import com.example.ui.common.CurrentUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyString;
import static org.mockito.Mockito.when;

/**
 * Renders each page through ZK's real servlets. ZUL pages fail only at runtime: a wrong binding redirects instead of
 * answering 200. Data from the mocks must appear in the page, so rows and their bindings are evaluated too.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@Import(AccountingScreensRenderTests.OpenPages.class)
class AccountingScreensRenderTests {

	@TestConfiguration(proxyBeanMethods = false)
	static class OpenPages {

		/** Only these test-only page URLs skip the Keycloak login. */
		@Bean
		@Order(0)
		SecurityFilterChain openAccountingPages(HttpSecurity http) throws Exception {
			http.securityMatcher("/zkau/web/accounting/**").authorizeHttpRequests(auth -> auth.anyRequest().permitAll());
			return http.build();
		}

	}

	static final AccountDto HEADER = new AccountDto(1L, "1100", "Aset Lancar", "ASSET", null, false, true);

	static final AccountDto CASH = new AccountDto(2L, "1110", "Kas Kecil", "ASSET", 1L, true, true);

	@LocalServerPort
	int port;

	@MockitoBean
	AccountingApiClient accountingApiClient;

	@MockitoBean(name = "currentUser")
	CurrentUser currentUser;

	@BeforeEach
	void commonData() {
		when(currentUser.can(anyString())).thenReturn(true);
		when(accountingApiClient.accounts()).thenReturn(List.of(HEADER, CASH));
	}

	String render(String page) throws Exception {
		HttpResponse<String> response = HttpClient.newHttpClient().send(
				HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/zkau/web/accounting/" + page)).build(),
				HttpResponse.BodyHandlers.ofString());
		assertThat(response.statusCode()).as(page).isEqualTo(200);
		return response.body();
	}

	@Test
	void journalListRenders() throws Exception {
		when(accountingApiClient.entries(any())).thenReturn(new EntryPageDto(List.of(new EntrySummaryDto(7L, "JE-2026-000001",
				LocalDate.of(2026, 3, 15), "REF-1", "Penjualan tunai", "POSTED", new BigDecimal("1665000.00"))), 1, 0, 50));

		assertThat(render("journal.zul")).contains("Penjualan tunai", "JE-2026-000001", "1.665.000,00", "New entry");
	}

	@Test
	void aNewEntryFormRenders() throws Exception {
		assertThat(render("entry.zul")).contains("Save draft", "Debit", "Credit");
	}

}
```

Run: `./mvnw -q -pl frontend/shell -am test -Dtest=AccountingScreensRenderTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL: status 404 or 302 for `journal.zul`; the pages do not exist yet.

- [ ] **Step 2: Write the styles and journal list**

`accounting.css`:

```css
.accounting .card { margin-bottom: 16px; }
.accounting .amount { text-align: right; }
.accounting .filters > * { margin-right: 8px; }
.accounting .inactive { color: #888; font-style: italic; }
.accounting .banner { display: inline-block; padding: 4px 10px; border-radius: 3px; }
.accounting .banner-ok { background: #e3f4e3; color: #1d6b1d; }
.accounting .banner-off { background: #fbe7e7; color: #9a1f1f; }
.accounting .hint { color: #9a5b00; }
```

`journal.zul`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?link rel="stylesheet" type="text/css" href="~./accounting/accounting.css"?>
<zk>
	<div viewModel="@id('vm') @init('com.example.ui.accounting.JournalViewModel')" sclass="accounting">
		<hlayout sclass="filters card">
			<datebox valueInLocalDate="@bind(vm.from)" format="yyyy-MM-dd" placeholder="From" width="120px"/>
			<datebox valueInLocalDate="@bind(vm.to)" format="yyyy-MM-dd" placeholder="To" width="120px"/>
			<combobox value="@bind(vm.status)" model="@load(vm.statuses)" readonly="true" placeholder="Any status" width="120px">
				<template name="model"><comboitem label="@load(each)"/></template>
			</combobox>
			<combobox value="@bind(vm.accountText)" model="@load(vm.accountModel)" autodrop="true" placeholder="Any account" width="220px">
				<template name="model"><comboitem label="@load(each.label)"/></template>
			</combobox>
			<textbox value="@bind(vm.q)" placeholder="Search text" onOK="@command('search')" width="180px"/>
			<button label="Search" iconSclass="z-icon-search" onClick="@command('search')"/>
			<button label="New entry" iconSclass="z-icon-plus" visible="@load(vm.canWrite)" onClick="@command('newEntry')"/>
		</hlayout>

		<listbox model="@load(vm.entries)" emptyMessage="No journal entries" sclass="card">
			<listhead>
				<listheader label="Date" width="110px"/>
				<listheader label="Number" width="160px"/>
				<listheader label="Reference" width="130px"/>
				<listheader label="Description"/>
				<listheader label="Status" width="100px"/>
				<listheader label="Amount" width="150px" align="right"/>
			</listhead>
			<template name="model" var="entry">
				<listitem onClick="@command('open', id=entry.id)">
					<listcell label="@load(entry.dateText)"/>
					<listcell label="@load(entry.entryNo)"/>
					<listcell label="@load(entry.reference)"/>
					<listcell label="@load(entry.description)"/>
					<listcell label="@load(entry.status)"/>
					<listcell label="@load(entry.totalDebitText)"/>
				</listitem>
			</template>
		</listbox>
		<paging pageSize="@load(vm.pageSize)" totalSize="@load(vm.total)" activePage="@load(vm.page)" onPaging="@command('goToPage', page=self.activePage)"/>
	</div>
</zk>
```

`JournalViewModel.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;
import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.GlobalCommand;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zul.ListModel;
import org.zkoss.zul.ListModelList;
import org.zkoss.zul.ListModels;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind journal.zul: the filtered, server-side paged list. */
@VariableResolver(SpringBeanVariableResolver.class)
public class JournalViewModel {

	static final int PAGE_SIZE = 50;

	private static final int ACCOUNT_CHOICES = 50;

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private LocalDate from;

	private LocalDate to;

	private String status = "";

	private String accountText = "";

	private String q = "";

	private int page;

	private long total;

	private boolean canWrite;

	private List<EntrySummaryDto> entries = List.of();

	private List<AccountDto> accounts = List.of();

	private ListModel<Object> accountModel = new ListModelList<>();

	@Init
	public void init() {
		try {
			canWrite = currentUser.can("accounting:write");
			accounts = accountingApiClient.accounts();
			accountModel = ListModels.toListSubModel(new ListModelList<Object>(accounts), new AccountMatcher(), ACCOUNT_CHOICES);
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	@Command
	@NotifyChange({ "entries", "total", "page" })
	public void search() {
		page = 0;
		load();
	}

	@Command
	@NotifyChange({ "entries", "total", "page" })
	public void goToPage(@BindingParam("page") int page) {
		this.page = page;
		load();
	}

	@GlobalCommand("accountingEntryChanged")
	@NotifyChange({ "entries", "total", "page" })
	public void reload() {
		load();
	}

	@Command
	public void newEntry() {
		EntryViewModel.open(null);
	}

	@Command
	public void open(@BindingParam("id") Long id) {
		EntryViewModel.open(id);
	}

	private void load() {
		Long accountId = accounts.stream().filter(account -> account.getLabel().equals(accountText)).map(AccountDto::id)
				.findFirst().orElse(null);
		try {
			EntryPageDto result = accountingApiClient.entries(new EntryFilter(from, to, status, accountId, q, page, PAGE_SIZE));
			entries = result.items();
			total = result.total();
		}
		catch (RuntimeException ex) {
			entries = List.of();
			total = 0;
			ApiErrors.show(ex);
		}
	}

	public LocalDate getFrom() {
		return from;
	}

	public void setFrom(LocalDate from) {
		this.from = from;
	}

	public LocalDate getTo() {
		return to;
	}

	public void setTo(LocalDate to) {
		this.to = to;
	}

	public String getStatus() {
		return status;
	}

	public void setStatus(String status) {
		this.status = status;
	}

	public List<String> getStatuses() {
		return List.of("", "DRAFT", "POSTED", "REVERSED");
	}

	public String getAccountText() {
		return accountText;
	}

	public void setAccountText(String accountText) {
		this.accountText = accountText;
	}

	public ListModel<Object> getAccountModel() {
		return accountModel;
	}

	public String getQ() {
		return q;
	}

	public void setQ(String q) {
		this.q = q;
	}

	public int getPage() {
		return page;
	}

	public int getPageSize() {
		return PAGE_SIZE;
	}

	public int getTotal() {
		return (int) total;
	}

	public boolean isCanWrite() {
		return canWrite;
	}

	public List<EntrySummaryDto> getEntries() {
		return entries;
	}

}
```

- [ ] **Step 3: Write the entry form**

`entry.zul`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?link rel="stylesheet" type="text/css" href="~./accounting/accounting.css"?>
<zk>
	<window viewModel="@id('vm') @init('com.example.ui.accounting.EntryViewModel')" mode="modal" closable="true"
		title="@load(vm.title)" width="960px" sclass="accounting">

		<grid sclass="card">
			<columns>
				<column width="110px"/>
				<column/>
				<column width="110px"/>
				<column/>
			</columns>
			<rows>
				<row>
					<label value="Date"/>
					<datebox valueInLocalDate="@load(vm.form.date)" format="yyyy-MM-dd" disabled="@load(vm.readOnly)"
						onChange="@command('setDate', date=self.valueInLocalDate)"/>
					<label value="Period"/>
					<label value="@load(vm.periodText)"/>
				</row>
				<row>
					<label value="Reference"/>
					<textbox value="@bind(vm.form.reference)" maxlength="100" hflex="1" readonly="@load(vm.readOnly)"/>
					<label value="Description"/>
					<textbox value="@bind(vm.form.description)" maxlength="500" hflex="1" readonly="@load(vm.readOnly)"/>
				</row>
			</rows>
		</grid>

		<grid model="@load(vm.lines)" sclass="card">
			<columns>
				<column label="Account"/>
				<column label="Memo" width="190px"/>
				<column label="Debit" width="150px"/>
				<column label="Credit" width="150px"/>
				<column label="" width="130px"/>
			</columns>
			<template name="model" var="line">
				<row>
					<combobox value="@load(line.accountLabel)" model="@load(vm.accountModel)" autodrop="true" hflex="1"
						disabled="@load(vm.readOnly)" placeholder="Search code or name"
						onChange="@command('typedAccount', line=line, text=self.value)">
						<template name="model"><comboitem label="@load(each.label)"/></template>
					</combobox>
					<textbox value="@bind(line.memo)" maxlength="200" hflex="1" readonly="@load(vm.readOnly)"/>
					<decimalbox value="@load(line.debit)" format="#,##0.00" locale="id" constraint="no negative" hflex="1"
						disabled="@load(vm.readOnly)" onChange="@command('setDebit', line=line, amount=self.value)"/>
					<decimalbox value="@load(line.credit)" format="#,##0.00" locale="id" constraint="no negative" hflex="1"
						disabled="@load(vm.readOnly)" onChange="@command('setCredit', line=line, amount=self.value)"/>
					<hlayout visible="@load(not vm.readOnly)">
						<button label="Balance" onClick="@command('balance', line=line)"/>
						<button iconSclass="z-icon-times" onClick="@command('removeLine', line=line)"/>
					</hlayout>
				</row>
			</template>
		</grid>

		<hlayout sclass="card" valign="middle">
			<button label="Add line" iconSclass="z-icon-plus" visible="@load(not vm.readOnly)" onClick="@command('addLine')"/>
			<label value="@load(vm.totalsText)"/>
			<label value="@load(vm.balanceText)" sclass="@load(vm.balanced ? 'banner banner-ok' : 'banner banner-off')"/>
		</hlayout>

		<hlayout valign="middle">
			<button label="Save draft" iconSclass="z-icon-save" visible="@load(vm.draft and vm.canWrite)" onClick="@command('save')"/>
			<button label="Delete" visible="@load(vm.saved and vm.draft and vm.canWrite)" onClick="@command('delete')"/>
			<button label="Post" iconSclass="z-icon-check" visible="@load(vm.saved and vm.draft and vm.canPost)"
				disabled="@load(not vm.balanced or vm.posterBlocked)" onClick="@command('post')"/>
			<label value="You prepared this entry; another user must post it" sclass="hint" visible="@load(vm.posterBlocked)"/>

			<datebox valueInLocalDate="@bind(vm.reverseDate)" format="yyyy-MM-dd" placeholder="Reversal date"
				visible="@load(vm.posted and vm.canPost)" width="130px"/>
			<button label="Reverse" visible="@load(vm.posted and vm.canPost)" onClick="@command('reverse', copy=false)"/>
			<button label="Reverse &amp; copy to draft" visible="@load(vm.posted and vm.canPost)" onClick="@command('reverse', copy=true)"/>

			<button label="@load(vm.linkText)" visible="@load(not empty vm.linkText)" onClick="@command('openLinked')"/>
		</hlayout>
	</window>
</zk>
```

`EntryViewModel.java`:

```java
package com.example.ui.accounting;

import java.math.BigDecimal;
import java.time.Clock;
import java.time.LocalDate;
import java.time.ZoneId;
import java.util.Collections;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.function.Function;
import java.util.stream.Collectors;

import org.zkoss.bind.BindUtils;
import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.ContextParam;
import org.zkoss.bind.annotation.ContextType;
import org.zkoss.bind.annotation.ExecutionArgParam;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.Component;
import org.zkoss.zk.ui.Executions;
import org.zkoss.zk.ui.event.Event;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.ListModel;
import org.zkoss.zul.ListModelList;
import org.zkoss.zul.ListModels;
import org.zkoss.zul.Messagebox;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind entry.zul, a modal so any screen can open an entry. Rules live in {@link EntryForm}. */
@VariableResolver(SpringBeanVariableResolver.class)
public class EntryViewModel {

	private static final Clock JAKARTA = Clock.system(ZoneId.of("Asia/Jakarta"));

	private static final int ACCOUNT_CHOICES = 50;

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private Long id;

	private EntryDto entry;

	private EntryForm form = EntryForm.blank(LocalDate.now(JAKARTA));

	private ListModelList<EntryLine> lines = new ListModelList<>();

	private String savedSnapshot = "";

	private String periodText = "";

	private LocalDate reverseDate;

	private boolean canWrite;

	private boolean canPost;

	private List<AccountDto> options = List.of();

	private Map<Long, AccountDto> accountsById = Map.of();

	private ListModel<Object> accountModel = new ListModelList<>();

	private Component view;

	/** Opens the entry in a modal window; {@code entryId} null starts a new draft. */
	public static void open(Long entryId) {
		Map<String, Object> args = new HashMap<>();
		args.put("entryId", entryId);
		Executions.createComponents("~./accounting/entry.zul", null, args);
	}

	@Init
	public void init(@ExecutionArgParam("entryId") Long entryId, @ContextParam(ContextType.VIEW) Component view) {
		this.view = view;
		try {
			canWrite = currentUser.can("accounting:write");
			canPost = currentUser.can("accounting:post");
			List<AccountDto> accounts = accountingApiClient.accounts();
			accountsById = accounts.stream().collect(Collectors.toMap(AccountDto::id, Function.identity()));
			options = accounts.stream().filter(account -> account.postable() && account.active()).toList();
			accountModel = ListModels.toListSubModel(new ListModelList<Object>(options), new AccountMatcher(), ACCOUNT_CHOICES);
			if (entryId != null) {
				show(accountingApiClient.entry(entryId));
				return;
			}
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		showForm();
		refreshPeriod();
	}

	@Command
	@NotifyChange("*")
	public void setDate(@BindingParam("date") LocalDate date) {
		form.setDate(date);
		refreshPeriod();
	}

	@Command
	@NotifyChange("*")
	public void addLine() {
		lines.add(new EntryLine());
	}

	@Command
	@NotifyChange("*")
	public void removeLine(@BindingParam("line") EntryLine line) {
		lines.remove(line);
	}

	@Command
	@NotifyChange("*")
	public void setDebit(@BindingParam("line") EntryLine line, @BindingParam("amount") BigDecimal amount) {
		line.setDebit(amount);
		BindUtils.postNotifyChange(line, "*");
	}

	@Command
	@NotifyChange("*")
	public void setCredit(@BindingParam("line") EntryLine line, @BindingParam("amount") BigDecimal amount) {
		line.setCredit(amount);
		BindUtils.postNotifyChange(line, "*");
	}

	@Command
	@NotifyChange("*")
	public void typedAccount(@BindingParam("line") EntryLine line, @BindingParam("text") String text) {
		line.pickByLabel(text, options);
		BindUtils.postNotifyChange(line, "*");
	}

	@Command
	@NotifyChange("*")
	public void balance(@BindingParam("line") EntryLine line) {
		form.balance(line);
		BindUtils.postNotifyChange(line, "*");
	}

	@Command
	@NotifyChange("*")
	public void save() {
		String problem = form.problem();
		if (problem != null) {
			warn(problem);
			return;
		}
		try {
			EntryRequest request = form.toRequest(entry == null ? null : entry.version());
			show(id == null ? accountingApiClient.createEntry(request) : accountingApiClient.updateEntry(id, request));
			entryChanged();
		}
		catch (RuntimeException ex) {
			handle(ex);
		}
	}

	@Command
	public void delete() {
		Messagebox.show("Delete this draft?", "Delete", Messagebox.YES | Messagebox.NO, Messagebox.QUESTION, (Event event) -> {
			if (Messagebox.ON_YES.equals(event.getName())) {
				try {
					accountingApiClient.deleteEntry(id, entry.version());
					entryChanged();
					view.detach();
				}
				catch (RuntimeException ex) {
					handle(ex);
				}
			}
		});
	}

	@Command
	@NotifyChange("*")
	public void post() {
		// The backend posts the saved draft, so what is on screen must be what was saved.
		if (isDirty()) {
			warn("Save the draft first: it has changes that are not saved");
			return;
		}
		try {
			show(accountingApiClient.postEntry(id, entry.version()));
			entryChanged();
		}
		catch (RuntimeException ex) {
			handle(ex);
		}
	}

	@Command
	@NotifyChange("*")
	public void reverse(@BindingParam("copy") boolean copy) {
		try {
			ReverseResultDto result = accountingApiClient.reverseEntry(id, reverseDate, copy);
			show(accountingApiClient.entry(copy ? result.draftId() : result.reversal().id()));
			entryChanged();
		}
		catch (RuntimeException ex) {
			handle(ex);
		}
	}

	@Command
	@NotifyChange("*")
	public void openLinked() {
		Long linked = entry.reversedBy() != null ? entry.reversedBy() : entry.reversalOf();
		try {
			show(accountingApiClient.entry(linked));
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	private void handle(RuntimeException ex) {
		if ("version-conflict".equals(ApiErrors.code(ex))) {
			Messagebox.show("Someone else changed this entry. Reload?", "Changed", Messagebox.YES | Messagebox.NO,
					Messagebox.QUESTION, (Event event) -> {
						if (Messagebox.ON_YES.equals(event.getName())) {
							reload();
						}
					});
			return;
		}
		ApiErrors.show(ex);
	}

	private void reload() {
		try {
			show(accountingApiClient.entry(id));
			BindUtils.postNotifyChange(this, "*");
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	private void show(EntryDto loaded) {
		entry = loaded;
		id = loaded.id();
		form = EntryForm.from(loaded, accountsById);
		showForm();
		refreshPeriod();
	}

	private void showForm() {
		lines = new ListModelList<>(form.getLines(), true);
		savedSnapshot = entry == null ? "" : form.snapshot();
	}

	private void refreshPeriod() {
		LocalDate date = form.getDate();
		if (date == null) {
			periodText = "";
			return;
		}
		try {
			periodText = accountingApiClient.periods(date.getYear()).stream()
					.filter(period -> period.month() == date.getMonthValue())
					.map(period -> period.getLabel() + " · " + period.status())
					.findFirst().orElse("No period for this date");
		}
		catch (RuntimeException ex) {
			periodText = "";
			ApiErrors.show(ex);
		}
	}

	private static void entryChanged() {
		BindUtils.postGlobalCommand(null, null, "accountingEntryChanged", Collections.emptyMap());
	}

	private static void warn(String message) {
		Clients.showNotification(message, "warning", null, "middle_center", 4000);
	}

	private boolean isDirty() {
		return !form.snapshot().equals(savedSnapshot);
	}

	public String getTitle() {
		if (entry == null) {
			return "New journal entry";
		}
		return (entry.entryNo() == null ? "Draft entry" : entry.entryNo()) + " · " + entry.status();
	}

	public EntryForm getForm() {
		return form;
	}

	public ListModelList<EntryLine> getLines() {
		return lines;
	}

	public ListModel<Object> getAccountModel() {
		return accountModel;
	}

	public String getPeriodText() {
		return periodText;
	}

	public String getTotalsText() {
		return "Debit " + Idr.format(form.totalDebit()) + " · Credit " + Idr.format(form.totalCredit());
	}

	public String getBalanceText() {
		return form.isBalanced() ? "Balanced" : "Difference " + Idr.format(form.difference().abs());
	}

	public boolean isBalanced() {
		return form.isBalanced();
	}

	public boolean isSaved() {
		return id != null;
	}

	public boolean isDraft() {
		return entry == null || "DRAFT".equals(entry.status());
	}

	public boolean isPosted() {
		return entry != null && "POSTED".equals(entry.status());
	}

	public boolean isReadOnly() {
		return !isDraft() || !canWrite;
	}

	public boolean isCanWrite() {
		return canWrite;
	}

	public boolean isCanPost() {
		return canPost;
	}

	public boolean isPosterBlocked() {
		return entry != null && entry.posterBlocked();
	}

	public String getLinkText() {
		if (entry != null && entry.reversedBy() != null) {
			return "Open the reversal";
		}
		return entry != null && entry.reversalOf() != null ? "Open the reversed entry" : "";
	}

	public LocalDate getReverseDate() {
		return reverseDate;
	}

	public void setReverseDate(LocalDate reverseDate) {
		this.reverseDate = reverseDate;
	}

}
```

- [ ] **Step 4: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/shell -am test -Dtest=AccountingScreensRenderTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: PASS. If a page answers 302, read the log for `PropertyNotFoundException` or a ZUL parse error; that is the test doing its job. Fix the page or the getter, never the assertion.

Then run `./mvnw -q -pl frontend/shell -am test`. Expected: BUILD SUCCESS.

- [ ] **Step 5: Commit**

```bash
git add frontend/accounting frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java
git commit -m "Add the journal list and the entry form, and render them in a test

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 5: Accounts and settings

**Files:**
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/AccountsViewModel.java`, `frontend/accounting/src/main/resources/web/accounting/accounts.zul`
- Modify: `frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java`

**Interfaces:**
- Consumes: `AccountingApiClient.settings/setup/updateSettings/accounts/createAccount/updateAccount`, `AccountTree.rows`, `AccountRow`, `AccountRequest`, `ApiErrors.show`.
- Produces: nothing for later tasks.

- [ ] **Step 1: Write the failing render tests**

Add to `AccountingScreensRenderTests`, with an import of `com.example.ui.accounting.SettingsDto`:

```java
	@Test
	void accountsBeforeSetupOfferTheTemplate() throws Exception {
		when(accountingApiClient.settings()).thenReturn(new SettingsDto(false, true));
		when(accountingApiClient.accounts()).thenReturn(List.of());

		assertThat(render("accounts.zul")).contains("Initialize from template");
	}

	@Test
	void accountsAfterSetupShowTheTreeAndTheSetting() throws Exception {
		when(accountingApiClient.settings()).thenReturn(new SettingsDto(true, true));

		assertThat(render("accounts.zul")).contains("Kas Kecil", "Aset Lancar", "Require a different user to post entries");
	}
```

Run: `./mvnw -q -pl frontend/shell -am test -Dtest=AccountingScreensRenderTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on `accounts.zul` with a non-200 status.

- [ ] **Step 2: Write the page and view model**

`accounts.zul`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?link rel="stylesheet" type="text/css" href="~./accounting/accounting.css"?>
<zk>
	<div viewModel="@id('vm') @init('com.example.ui.accounting.AccountsViewModel')" sclass="accounting">

		<groupbox mold="3d" sclass="card" visible="@load(not vm.initialized)">
			<caption label="Accounting is not set up yet"/>
			<label value="Start from the Indonesian chart of accounts template. You can edit your copy afterwards."/>
			<separator/>
			<button label="Initialize from template" visible="@load(vm.canManage)" onClick="@command('setup')"/>
		</groupbox>

		<groupbox mold="3d" sclass="card" visible="@load(vm.initialized)">
			<caption label="Settings"/>
			<checkbox label="Require a different user to post entries" checked="@load(vm.requireSeparatePoster)"
				disabled="@load(not vm.canManage)" onCheck="@command('setSeparatePoster', value=self.checked)"/>
		</groupbox>

		<groupbox mold="3d" sclass="card" visible="@load(vm.editing)">
			<caption label="@load(vm.editingId eq null ? 'New account' : 'Edit account')"/>
			<grid>
				<columns>
					<column width="120px"/>
					<column/>
				</columns>
				<rows>
					<row>
						<label value="Code"/>
						<textbox value="@bind(vm.code)" maxlength="20"/>
					</row>
					<row>
						<label value="Name"/>
						<textbox value="@bind(vm.name)" maxlength="200" hflex="1"/>
					</row>
					<row>
						<label value="Type"/>
						<combobox value="@bind(vm.type)" model="@load(vm.types)" readonly="true">
							<template name="model"><comboitem label="@load(each)"/></template>
						</combobox>
					</row>
					<row>
						<label value="Parent"/>
						<label value="@load(vm.parentLabel)"/>
					</row>
					<row>
						<label value="Receives entries"/>
						<checkbox checked="@bind(vm.postable)"/>
					</row>
				</rows>
			</grid>
			<hbox>
				<button label="Save" iconSclass="z-icon-save" onClick="@command('save')"/>
				<button label="Cancel" onClick="@command('cancel')"/>
			</hbox>
		</groupbox>

		<listbox model="@load(vm.rows)" emptyMessage="No accounts yet" sclass="card" visible="@load(vm.initialized)">
			<listhead>
				<listheader label="Account"/>
				<listheader label="Type" width="110px"/>
				<listheader label="Receives entries" width="130px" align="center"/>
				<listheader label="Active" width="70px" align="center"/>
				<listheader label="" width="280px" visible="@load(vm.canManage)"/>
			</listhead>
			<template name="model" var="row">
				<listitem>
					<listcell style="@load(row.indentStyle)" sclass="@load(row.account.active ? '' : 'inactive')"
						label="@load(row.account.label)"/>
					<listcell label="@load(row.account.type)"/>
					<listcell label="@load(row.account.postable ? 'Yes' : 'No')"/>
					<listcell label="@load(row.account.active ? 'Yes' : 'No')"/>
					<listcell>
						<button label="Add child" visible="@load(not row.account.postable)" onClick="@command('addChild', account=row.account)"/>
						<button label="Edit" onClick="@command('edit', account=row.account)"/>
						<button label="@load(row.account.active ? 'Deactivate' : 'Activate')" onClick="@command('toggleActive', account=row.account)"/>
					</listcell>
				</listitem>
			</template>
		</listbox>
		<button label="Add top-level account" visible="@load(vm.initialized and vm.canManage)" onClick="@command('addChild', account=null)"/>
	</div>
</zk>
```

`AccountsViewModel.java`:

```java
package com.example.ui.accounting;

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

/** MVVM view model behind accounts.zul: setup, the settings panel and the chart of accounts. */
@VariableResolver(SpringBeanVariableResolver.class)
public class AccountsViewModel {

	private static final List<String> TYPES = List.of("ASSET", "LIABILITY", "EQUITY", "REVENUE", "EXPENSE");

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private boolean initialized;

	private boolean requireSeparatePoster = true;

	private boolean canManage;

	private List<AccountRow> rows = List.of();

	private boolean editing;

	private Long editingId;

	private Long parentId;

	private String parentLabel = "";

	private String code = "";

	private String name = "";

	private String type = "ASSET";

	private boolean postable = true;

	private boolean active = true;

	@Init
	public void init() {
		try {
			canManage = currentUser.can("accounting:account-manage");
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	@Command
	@NotifyChange("*")
	public void setup() {
		try {
			accountingApiClient.setup();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	@Command
	@NotifyChange("*")
	public void setSeparatePoster(@BindingParam("value") boolean value) {
		try {
			requireSeparatePoster = accountingApiClient.updateSettings(value).requireSeparatePoster();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			load();
		}
	}

	@Command
	@NotifyChange("*")
	public void addChild(@BindingParam("account") AccountDto parent) {
		editing = true;
		editingId = null;
		parentId = parent == null ? null : parent.id();
		parentLabel = parent == null ? "None (top level)" : parent.getLabel();
		code = "";
		name = "";
		type = parent == null ? "ASSET" : parent.type();
		postable = true;
		active = true;
	}

	@Command
	@NotifyChange("*")
	public void edit(@BindingParam("account") AccountDto account) {
		editing = true;
		editingId = account.id();
		parentId = account.parentId();
		parentLabel = rows.stream().map(AccountRow::getAccount).filter(other -> other.id().equals(account.parentId()))
				.map(AccountDto::getLabel).findFirst().orElse("None (top level)");
		code = account.code();
		name = account.name();
		type = account.type();
		postable = account.postable();
		active = account.active();
	}

	@Command
	@NotifyChange("*")
	public void cancel() {
		editing = false;
	}

	@Command
	@NotifyChange("*")
	public void save() {
		if (code == null || code.isBlank() || name == null || name.isBlank()) {
			Clients.showNotification("Enter a code and a name", "warning", null, "middle_center", 3000);
			return;
		}
		AccountRequest request = new AccountRequest(code.strip(), name.strip(), type, parentId, postable, active);
		try {
			if (editingId == null) {
				accountingApiClient.createAccount(request);
			}
			else {
				accountingApiClient.updateAccount(editingId, request);
			}
			editing = false;
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			return;
		}
		load();
	}

	@Command
	@NotifyChange("*")
	public void toggleActive(@BindingParam("account") AccountDto account) {
		try {
			accountingApiClient.updateAccount(account.id(), new AccountRequest(account.code(), account.name(), account.type(),
					account.parentId(), account.postable(), !account.active()));
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	private void load() {
		try {
			SettingsDto settings = accountingApiClient.settings();
			initialized = settings.initialized();
			requireSeparatePoster = settings.requireSeparatePoster();
			rows = AccountTree.rows(accountingApiClient.accounts());
		}
		catch (RuntimeException ex) {
			rows = List.of();
			ApiErrors.show(ex);
		}
	}

	public boolean isInitialized() {
		return initialized;
	}

	public boolean isRequireSeparatePoster() {
		return requireSeparatePoster;
	}

	public boolean isCanManage() {
		return canManage;
	}

	public List<AccountRow> getRows() {
		return rows;
	}

	public boolean isEditing() {
		return editing;
	}

	public Long getEditingId() {
		return editingId;
	}

	public String getParentLabel() {
		return parentLabel;
	}

	public String getCode() {
		return code;
	}

	public void setCode(String code) {
		this.code = code;
	}

	public String getName() {
		return name;
	}

	public void setName(String name) {
		this.name = name;
	}

	public String getType() {
		return type;
	}

	public void setType(String type) {
		this.type = type;
	}

	public List<String> getTypes() {
		return TYPES;
	}

	public boolean isPostable() {
		return postable;
	}

	public void setPostable(boolean postable) {
		this.postable = postable;
	}

}
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add frontend/accounting frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java
git commit -m "Add the accounts screen: setup, settings and the chart of accounts

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 6: Periods

**Files:**
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/PeriodsViewModel.java`, `frontend/accounting/src/main/resources/web/accounting/periods.zul`
- Modify: `frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java`

**Interfaces:**
- Consumes: `AccountingApiClient.periods/openYear/closePeriod/reopenPeriod/entries`, `EntryViewModel.open(Long)`, `PeriodDto`, `EntryFilter`, `ApiErrors.code/show`, global command `accountingEntryChanged`.
- Produces: nothing for later tasks.

- [ ] **Step 1: Write the failing render test**

Add to `AccountingScreensRenderTests`, with an import of `com.example.ui.accounting.PeriodDto` and `org.mockito.ArgumentMatchers.anyInt`:

```java
	@Test
	void periodsRender() throws Exception {
		when(accountingApiClient.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, 2026, 3, LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31), "CLOSED", "Ani Accountant")));

		assertThat(render("periods.zul")).contains("Mar 2026", "CLOSED", "Ani Accountant", "Reopen");
	}
```

Run: `./mvnw -q -pl frontend/shell -am test -Dtest=AccountingScreensRenderTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on `periods.zul` with a non-200 status.

- [ ] **Step 2: Write the page and view model**

`periods.zul`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?link rel="stylesheet" type="text/css" href="~./accounting/accounting.css"?>
<zk>
	<div viewModel="@id('vm') @init('com.example.ui.accounting.PeriodsViewModel')" sclass="accounting">
		<hlayout sclass="filters card" valign="middle">
			<label value="Year"/>
			<intbox value="@load(vm.year)" width="80px" onChange="@command('changeYear', year=self.value)"/>
			<button label="@load(vm.openYearLabel)" visible="@load(vm.canManage)" onClick="@command('openYear')"/>
		</hlayout>

		<groupbox mold="3d" sclass="card" visible="@load(not empty vm.blockingDrafts)">
			<caption label="@load(vm.blockedText)"/>
			<listbox model="@load(vm.blockingDrafts)">
				<template name="model" var="draft">
					<listitem onClick="@command('openDraft', id=draft.id)">
						<listcell label="@load(draft.dateText)"/>
						<listcell label="@load(draft.description)"/>
						<listcell label="@load(draft.totalDebitText)"/>
					</listitem>
				</template>
			</listbox>
		</groupbox>

		<groupbox mold="3d" sclass="card" visible="@load(vm.reopening ne null)">
			<caption label="@load(vm.reopenText)"/>
			<textbox value="@bind(vm.reason)" maxlength="500" placeholder="Reason" hflex="1"/>
			<hbox>
				<button label="Confirm reopen" onClick="@command('confirmReopen')"/>
				<button label="Cancel" onClick="@command('cancelReopen')"/>
			</hbox>
		</groupbox>

		<listbox model="@load(vm.periods)" emptyMessage="No periods for this year" sclass="card">
			<listhead>
				<listheader label="Month"/>
				<listheader label="Status" width="110px"/>
				<listheader label="Closed by"/>
				<listheader label="" width="130px" visible="@load(vm.canManage)"/>
			</listhead>
			<template name="model" var="period">
				<listitem>
					<listcell label="@load(period.label)"/>
					<listcell label="@load(period.status)"/>
					<listcell label="@load(period.closedByName)"/>
					<listcell>
						<button label="Close" visible="@load(period.open)" onClick="@command('close', period=period)"/>
						<button label="Reopen" visible="@load(not period.open)" onClick="@command('startReopen', period=period)"/>
					</listcell>
				</listitem>
			</template>
		</listbox>
	</div>
</zk>
```

`PeriodsViewModel.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.GlobalCommand;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind periods.zul: a year's months, close (with blocking drafts) and reopen (with a reason). */
@VariableResolver(SpringBeanVariableResolver.class)
public class PeriodsViewModel {

	@WireVariable
	private AccountingApiClient accountingApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private Integer year = LocalDate.now(ZoneId.of("Asia/Jakarta")).getYear();

	private boolean canManage;

	private List<PeriodDto> periods = List.of();

	private List<EntrySummaryDto> blockingDrafts = List.of();

	private PeriodDto blockedPeriod;

	private PeriodDto reopening;

	private String reason = "";

	@Init
	public void init() {
		try {
			canManage = currentUser.can("accounting:period-manage");
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		fetch();
	}

	@Command
	@NotifyChange("*")
	public void changeYear(@BindingParam("year") Integer year) {
		this.year = year;
		load();
	}

	private void load() {
		blockingDrafts = List.of();
		blockedPeriod = null;
		reopening = null;
		fetch();
	}

	@Command
	@NotifyChange("*")
	public void openYear() {
		try {
			accountingApiClient.openYear(nextYear());
			year = nextYear();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		load();
	}

	@Command
	@NotifyChange("*")
	public void close(@BindingParam("period") PeriodDto period) {
		try {
			accountingApiClient.closePeriod(period.id());
			load();
		}
		catch (RuntimeException ex) {
			if ("period-has-drafts".equals(ApiErrors.code(ex))) {
				showBlockingDrafts(period);
				return;
			}
			ApiErrors.show(ex);
		}
	}

	@Command
	public void openDraft(@BindingParam("id") Long id) {
		EntryViewModel.open(id);
	}

	/** A draft posted or deleted from the modal may unblock the close. */
	@GlobalCommand("accountingEntryChanged")
	@NotifyChange("*")
	public void entryChanged() {
		if (blockedPeriod != null) {
			showBlockingDrafts(blockedPeriod);
		}
	}

	@Command
	@NotifyChange("*")
	public void startReopen(@BindingParam("period") PeriodDto period) {
		reopening = period;
		reason = "";
	}

	@Command
	@NotifyChange("*")
	public void cancelReopen() {
		reopening = null;
	}

	@Command
	@NotifyChange("*")
	public void confirmReopen() {
		if (reason == null || reason.isBlank()) {
			Clients.showNotification("Enter a reason", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			accountingApiClient.reopenPeriod(reopening.id(), reason.strip());
			load();
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
	}

	private void showBlockingDrafts(PeriodDto period) {
		blockedPeriod = period;
		try {
			blockingDrafts = accountingApiClient.entries(new EntryFilter(period.startDate(), period.endDate(), "DRAFT", null, null,
					0, JournalViewModel.PAGE_SIZE)).items();
		}
		catch (RuntimeException ex) {
			blockingDrafts = List.of();
			ApiErrors.show(ex);
		}
	}

	private void fetch() {
		if (year == null) {
			periods = List.of();
			return;
		}
		try {
			periods = accountingApiClient.periods(year);
		}
		catch (RuntimeException ex) {
			periods = List.of();
			ApiErrors.show(ex);
		}
	}

	/** The year on screen when it has no periods, so a skipped year can be opened; otherwise the following one. */
	private int nextYear() {
		return periods.isEmpty() ? year : year + 1;
	}

	public Integer getYear() {
		return year;
	}

	public String getOpenYearLabel() {
		return year == null ? "Open year" : "Open year " + nextYear();
	}

	public boolean isCanManage() {
		return canManage;
	}

	public List<PeriodDto> getPeriods() {
		return periods;
	}

	public List<EntrySummaryDto> getBlockingDrafts() {
		return blockingDrafts;
	}

	public String getBlockedText() {
		return blockedPeriod == null ? "" : "Post or delete these drafts to close " + blockedPeriod.getLabel();
	}

	public PeriodDto getReopening() {
		return reopening;
	}

	public String getReopenText() {
		return reopening == null ? "" : "Reopen " + reopening.getLabel();
	}

	public String getReason() {
		return reason;
	}

	public void setReason(String reason) {
		this.reason = reason;
	}

}
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add frontend/accounting frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java
git commit -m "Add the periods screen: close with blocking drafts, reopen with a reason

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 7: Reports

**Files:**
- Create: `frontend/accounting/src/main/java/com/example/ui/accounting/ReportsViewModel.java`, `frontend/accounting/src/main/resources/web/accounting/reports.zul`
- Modify: `frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java`

**Interfaces:**
- Consumes: `AccountingApiClient.trialBalance/generalLedger/accounts`, `Csv`, `AccountMatcher`, `TrialBalanceDto`, `GeneralLedgerDto`.
- Produces: nothing for later tasks.

- [ ] **Step 1: Write the failing render test**

Add to `AccountingScreensRenderTests`, with an import of `com.example.ui.accounting.TrialBalanceDto`:

```java
	@Test
	void reportsRender() throws Exception {
		when(accountingApiClient.trialBalance(any())).thenReturn(new TrialBalanceDto(LocalDate.of(2026, 3, 31),
				List.of(new TrialBalanceDto.Row(2L, "1110", "Kas Kecil", "ASSET", new BigDecimal("1000.00"), new BigDecimal("300.00"),
						new BigDecimal("700.00"))),
				new BigDecimal("1000.00"), new BigDecimal("300.00"), new BigDecimal("700.00")));

		assertThat(render("reports.zul")).contains("Kas Kecil", "1.000,00", "700,00", "Export CSV", "General ledger");
	}
```

Run: `./mvnw -q -pl frontend/shell -am test -Dtest=AccountingScreensRenderTests -Dsurefire.failIfNoSpecifiedTests=false`
Expected: FAIL on `reports.zul` with a non-200 status.

- [ ] **Step 2: Write the page and view model**

`reports.zul`:

```xml
<?xml version="1.0" encoding="UTF-8"?>
<?link rel="stylesheet" type="text/css" href="~./accounting/accounting.css"?>
<zk>
	<div viewModel="@id('vm') @init('com.example.ui.accounting.ReportsViewModel')" sclass="accounting">
		<tabbox>
			<tabs>
				<tab label="Trial balance"/>
				<tab label="General ledger"/>
			</tabs>
			<tabpanels>
				<tabpanel>
					<hlayout sclass="filters card" valign="middle">
						<label value="As of"/>
						<datebox valueInLocalDate="@bind(vm.asOf)" format="yyyy-MM-dd" width="130px"/>
						<button label="Run" onClick="@command('runTrialBalance')"/>
						<button label="Export CSV" iconSclass="z-icon-download" disabled="@load(empty vm.trialRows)" onClick="@command('exportTrialBalance')"/>
					</hlayout>
					<listbox model="@load(vm.trialRows)" emptyMessage="No posted entries up to this date" sclass="card">
						<listhead>
							<listheader label="Code" width="90px"/>
							<listheader label="Account"/>
							<listheader label="Debit" width="150px" align="right"/>
							<listheader label="Credit" width="150px" align="right"/>
							<listheader label="Balance" width="150px" align="right"/>
						</listhead>
						<template name="model" var="row">
							<listitem>
								<listcell label="@load(row.code)"/>
								<listcell label="@load(row.name)"/>
								<listcell label="@load(row.debitText)"/>
								<listcell label="@load(row.creditText)"/>
								<listcell label="@load(row.balanceText)"/>
							</listitem>
						</template>
					</listbox>
					<label value="@load(vm.trialTotals)"/>
				</tabpanel>
				<tabpanel>
					<hlayout sclass="filters card" valign="middle">
						<combobox value="@bind(vm.ledgerAccountText)" model="@load(vm.accountModel)" autodrop="true" placeholder="Search code or name" width="260px">
							<template name="model"><comboitem label="@load(each.label)"/></template>
						</combobox>
						<datebox valueInLocalDate="@bind(vm.ledgerFrom)" format="yyyy-MM-dd" width="130px"/>
						<datebox valueInLocalDate="@bind(vm.ledgerTo)" format="yyyy-MM-dd" width="130px"/>
						<button label="Run" onClick="@command('runLedger')"/>
						<button label="Export CSV" iconSclass="z-icon-download" disabled="@load(vm.ledger eq null)" onClick="@command('exportLedger')"/>
					</hlayout>
					<label value="@load(vm.ledgerOpening)"/>
					<listbox model="@load(vm.ledgerLines)" emptyMessage="No lines in this range" sclass="card">
						<listhead>
							<listheader label="Date" width="110px"/>
							<listheader label="Number" width="150px"/>
							<listheader label="Description"/>
							<listheader label="Memo"/>
							<listheader label="Debit" width="130px" align="right"/>
							<listheader label="Credit" width="130px" align="right"/>
							<listheader label="Balance" width="130px" align="right"/>
						</listhead>
						<template name="model" var="line">
							<listitem>
								<listcell label="@load(line.dateText)"/>
								<listcell label="@load(line.entryNo)"/>
								<listcell label="@load(line.description)"/>
								<listcell label="@load(line.memo)"/>
								<listcell label="@load(line.debitText)"/>
								<listcell label="@load(line.creditText)"/>
								<listcell label="@load(line.runningBalanceText)"/>
							</listitem>
						</template>
					</listbox>
					<label value="@load(vm.ledgerClosing)"/>
				</tabpanel>
			</tabpanels>
		</tabbox>
	</div>
</zk>
```

`ReportsViewModel.java`:

```java
package com.example.ui.accounting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.List;

import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;
import org.zkoss.zul.Filedownload;
import org.zkoss.zul.ListModel;
import org.zkoss.zul.ListModelList;
import org.zkoss.zul.ListModels;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind reports.zul: trial balance and general ledger, both exportable. */
@VariableResolver(SpringBeanVariableResolver.class)
public class ReportsViewModel {

	private static final String CSV = "text/csv";

	private static final int ACCOUNT_CHOICES = 50;

	@WireVariable
	private AccountingApiClient accountingApiClient;

	private final LocalDate today = LocalDate.now(ZoneId.of("Asia/Jakarta"));

	private LocalDate asOf = today;

	private TrialBalanceDto trial;

	private List<AccountDto> accounts = List.of();

	private ListModel<Object> accountModel = new ListModelList<>();

	private String ledgerAccountText = "";

	private LocalDate ledgerFrom = today.withDayOfYear(1);

	private LocalDate ledgerTo = today;

	private GeneralLedgerDto ledger;

	@Init
	public void init() {
		try {
			accounts = accountingApiClient.accounts();
			accountModel = ListModels.toListSubModel(new ListModelList<Object>(accounts), new AccountMatcher(), ACCOUNT_CHOICES);
		}
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
		}
		loadTrialBalance();
	}

	@Command
	@NotifyChange("*")
	public void runTrialBalance() {
		loadTrialBalance();
	}

	@Command
	public void exportTrialBalance() {
		Filedownload.save(Csv.trialBalance(trial), CSV, "trial-balance-" + trial.asOf() + ".csv");
	}

	@Command
	@NotifyChange("*")
	public void runLedger() {
		AccountDto account = accounts.stream().filter(option -> option.getLabel().equals(ledgerAccountText)).findFirst().orElse(null);
		if (account == null || ledgerFrom == null || ledgerTo == null) {
			Clients.showNotification("Choose an account and a date range", "warning", null, "middle_center", 3000);
			return;
		}
		try {
			ledger = accountingApiClient.generalLedger(account.id(), ledgerFrom, ledgerTo);
		}
		catch (RuntimeException ex) {
			ledger = null;
			ApiErrors.show(ex);
		}
	}

	@Command
	public void exportLedger() {
		Filedownload.save(Csv.generalLedger(ledger), CSV,
				"general-ledger-" + ledger.accountCode() + "-" + ledger.from() + "-" + ledger.to() + ".csv");
	}

	private void loadTrialBalance() {
		try {
			trial = accountingApiClient.trialBalance(asOf);
		}
		catch (RuntimeException ex) {
			trial = null;
			ApiErrors.show(ex);
		}
	}

	public LocalDate getAsOf() {
		return asOf;
	}

	public void setAsOf(LocalDate asOf) {
		this.asOf = asOf;
	}

	public List<TrialBalanceDto.Row> getTrialRows() {
		return trial == null ? List.of() : trial.rows();
	}

	public String getTrialTotals() {
		return trial == null ? ""
				: "Debit " + Idr.format(trial.totalDebit()) + " · Credit " + Idr.format(trial.totalCredit()) + " · Difference "
						+ Idr.format(trial.difference());
	}

	public ListModel<Object> getAccountModel() {
		return accountModel;
	}

	public String getLedgerAccountText() {
		return ledgerAccountText;
	}

	public void setLedgerAccountText(String ledgerAccountText) {
		this.ledgerAccountText = ledgerAccountText;
	}

	public LocalDate getLedgerFrom() {
		return ledgerFrom;
	}

	public void setLedgerFrom(LocalDate ledgerFrom) {
		this.ledgerFrom = ledgerFrom;
	}

	public LocalDate getLedgerTo() {
		return ledgerTo;
	}

	public void setLedgerTo(LocalDate ledgerTo) {
		this.ledgerTo = ledgerTo;
	}

	public GeneralLedgerDto getLedger() {
		return ledger;
	}

	public List<GeneralLedgerDto.Line> getLedgerLines() {
		return ledger == null ? List.of() : ledger.lines();
	}

	public String getLedgerOpening() {
		return ledger == null ? "" : "Opening balance " + Idr.format(ledger.openingBalance());
	}

	public String getLedgerClosing() {
		return ledger == null ? "" : "Closing balance " + Idr.format(ledger.closingBalance());
	}

}
```

- [ ] **Step 3: Run the tests to verify they pass**

Run: `./mvnw -q -pl frontend/shell -am test`
Expected: BUILD SUCCESS.

- [ ] **Step 4: Commit**

```bash
git add frontend/accounting frontend/shell/src/test/java/com/example/shell/AccountingScreensRenderTests.java
git commit -m "Add the reports screen: trial balance and general ledger with CSV export

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```

---

### Task 8: Documentation and full verification

**Files:**
- Modify: `README.md`, `CLAUDE.md`, `docs/superpowers/specs/2026-10-29-accounting-core-design.md`

- [ ] **Step 1: README**

In `## Accounting`, replace the note that the screens arrive later with one paragraph on the four screens, and how the entry form is opened.

Append to the "Manual end-to-end check" table, numbering on from the last row (these are the spec's manual checks; automated tests cannot click):

| Action | Expected |
|---|---|
| Log in as `admin`, open **Accounts**, click **Initialize from template** | Tree of 39 accounts; settings panel with "Require a different user to post entries" on |
| Log in as `bookkeeper`, open **Journal**, **New entry**: search an account by name, enter a debit, then a credit on the same line | Typing the credit clears the debit; totals update; banner shows the difference |
| Add a second line, click **Balance** on it, then **Save draft** | Banner shows "Balanced"; no number yet; no Post button (no permission) |
| Log in as `accountant`, open the draft, change nothing, click **Post** | Number `JE-<year>-000001`; form is read-only with Reverse buttons |
| As `accountant`, create and save your own balanced draft | Post is disabled with "You prepared this entry; another user must post it" |
| As `admin`, switch the setting off in **Accounts**; as `accountant`, reopen that draft | Post is enabled and works; switch the setting back on afterwards |
| Open a draft in two browser windows as `accountant`; save a change in one, click Save in the other | "Someone else changed this entry. Reload?"; Yes shows the other window's change |
| Change a saved draft without saving, click **Post** | Refused: "Save the draft first" |
| In **Periods**, click **Close** on a month holding a draft | The draft is listed; opening it from the list works; closing succeeds once it is posted or deleted |
| **Reopen** a closed month | Asks for a reason; refused when empty |
| **Reports**: run the trial balance, export CSV, open it in a spreadsheet | Difference 0,00 on screen; columns and non-ASCII names correct in the spreadsheet |
| Run the general ledger for an account with activity | Opening balance, running balance, closing balance |
| Log in as `acme-admin` | No `demo` accounting data on any of the four screens |

- [ ] **Step 2: CLAUDE.md**

Under "Testing", replace "Frontend tests are context-load only." with: "Frontend tests are context-load plus unit tests of plain classes. `AccountingScreensRenderTests` also renders ZUL pages through ZK's real servlets on a random port, with the REST client mocked: a wrong binding redirects instead of answering 200. Copy it for new screens; MockMvc does not run ZK's servlets."

Under "Gotchas", add: "In ZK forms, bind an amount with `@load` plus `onChange=\"@command('setDebit', line=line, amount=self.value)\"` rather than `@bind` plus a second command: the order of a prompt save and a command on the same event is not something to rely on."

Under "Status", replace the "Not built yet" mention of accounting screens if any, and add: "Verified `<date>` (`./mvnw verify`): accounting screens render, with their logic unit-tested. Not yet verified by hand: the accounting rows of the README checklist." Use the date of the verify run.

- [ ] **Step 3: Spec**

Change the status line to `Status: implemented on feat/accounting-frontend (backend merged)`. Under "Settled by the backend plan", add a heading-level sibling `### Settled by the frontend plan` listing the bullets from this plan's "Decisions this plan settles".

- [ ] **Step 4: Full verification**

Run: `./mvnw verify`
Expected: BUILD SUCCESS across all modules. Report per-module counts. The full build takes over 10 minutes against the remote Docker, so run it where a 10-minute tool limit cannot kill it.

- [ ] **Step 5: Commit**

```bash
git add README.md CLAUDE.md docs/superpowers/specs/2026-10-29-accounting-core-design.md
git commit -m "Document the accounting screens and their manual checks

Co-Authored-By: Claude Opus 5.5 <noreply@anthropic.com>"
```
