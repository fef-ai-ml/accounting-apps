package com.example.shell;

import java.math.BigDecimal;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Instant;
import java.util.Map;
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
import com.example.ui.accounting.AuditPageDto;
import com.example.ui.accounting.AuditRowDto;
import com.example.ui.accounting.EntryPageDto;
import com.example.ui.accounting.EntrySummaryDto;
import com.example.ui.accounting.PeriodDto;
import com.example.ui.accounting.SettingsDto;
import com.example.ui.accounting.TrialBalanceDto;
import com.example.ui.accounting.YearStatusDto;
import com.example.ui.common.CurrentUser;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.ArgumentMatchers.any;
import static org.mockito.ArgumentMatchers.anyInt;
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
		when(accountingApiClient.settings()).thenReturn(new SettingsDto(true, true));
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

		assertThat(render("journal.zul")).contains("Penjualan tunai", "000001", "1.665.000,00", "New entry");
	}

	@Test
	void aNewEntryFormRenders() throws Exception {
		assertThat(render("entry.zul")).contains("Save draft", "Debit", "Credit");
	}

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

	@Test
	void periodsRender() throws Exception {
		when(accountingApiClient.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, 2026, 3, LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31), "CLOSED", "Ani Accountant")));

		assertThat(render("periods.zul")).contains("Mar 2026", "CLOSED", "Ani Accountant", "Reopen");
	}

	@Test
	void reportsRender() throws Exception {
		when(accountingApiClient.trialBalance(any())).thenReturn(new TrialBalanceDto(LocalDate.of(2026, 3, 31),
				List.of(new TrialBalanceDto.Row(2L, "1110", "Kas Kecil", "ASSET", new BigDecimal("1000.00"), new BigDecimal("300.00"),
						new BigDecimal("700.00"))),
				new BigDecimal("1000.00"), new BigDecimal("300.00"), new BigDecimal("700.00")));

		assertThat(render("reports.zul")).contains("Kas Kecil", "1.000,00", "700,00", "Export CSV", "General ledger", "Income statement",
				"Balance sheet", "the closing entry has moved them");
	}

	@Test
	void auditRendersRowsInJakartaTime() throws Exception {
		when(accountingApiClient.audit(any(), any(), any(), anyInt(), anyInt())).thenReturn(new AuditPageDto(List.of(new AuditRowDto(
				Instant.parse("2026-03-14T17:30:00Z"), "Akuntan", "ACCOUNT_CHANGE", "account", 5L,
				Map.of("name", Map.of("from", "Kas", "to", "Kas Kecil")))), 1, 0, 50));

		assertThat(render("audit.zul")).contains("Akuntan", "2026\\-03\\-15 00:30:00", "ACCOUNT_CHANGE", "account 5", "Kas Kecil");
	}

	@Test
	void aClosedYearRendersItsCloseAndTheReopenButton() throws Exception {
		when(accountingApiClient.periods(anyInt())).thenReturn(List.of(new PeriodDto(3L, 2026, 3, LocalDate.of(2026, 3, 1),
				LocalDate.of(2026, 3, 31), "CLOSED", "Ani Accountant")));
		when(accountingApiClient.yearStatus(anyInt())).thenReturn(new YearStatusDto(2026, true, 9L, "JE-2026-000009"));

		assertThat(render("periods.zul")).contains("Reopen year", "Closed by JE\\-2026\\-000009");
	}
}
