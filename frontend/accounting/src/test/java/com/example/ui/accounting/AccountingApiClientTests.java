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

	@Test
	void auditSendsOnlyTheFiltersInUseAndReadsTheRow() {
		server.expect(requestTo(BASE + "/audit?from=2026-03-01&action=POST&page=1&size=50")).andRespond(withSuccess("""
				{"items": [{"at": "2026-03-14T17:30:00Z", "actorName": "Akuntan", "action": "POST", "entity": "journal_entry",
				 "entityId": 5, "details": {"name": {"from": "Kas", "to": "Kas Kecil"}, "totalDebit": "10000000.00"}}],
				 "total": 51, "page": 1, "size": 50}
				""", MediaType.APPLICATION_JSON));

		AuditPageDto page = client.audit(LocalDate.of(2026, 3, 1), null, "POST", 1, 50);

		assertThat(page.total()).isEqualTo(51);
		AuditRowDto row = page.items().get(0);
		assertThat(row.getAtText()).isEqualTo("2026-03-15 00:30:00");
		assertThat(row.getRecord()).isEqualTo("journal_entry 5");
		assertThat(row.getDetailsText()).isEqualTo("name: Kas → Kas Kecil, totalDebit: 10000000.00");
		server.verify();
	}

	@Test
	void auditWithNoFiltersSendsOnlyThePage() {
		server.expect(requestTo(BASE + "/audit?page=0&size=50")).andRespond(withSuccess("""
				{"items": [], "total": 0, "page": 0, "size": 50}
				""", MediaType.APPLICATION_JSON));

		assertThat(client.audit(null, null, "", 0, 50).items()).isEmpty();
		server.verify();
	}

	@Test
	void statementsArriveWithTheirRowsAndAmounts() {
		server.expect(requestTo(BASE + "/reports/income-statement?from=2026-01-01&to=2026-12-31")).andRespond(withSuccess("""
				{"from": "2026-01-01", "to": "2026-12-31", "revenue": [{"accountId": 1, "code": "4000", "name": "P", "depth": 0, "amount": "10.00"}],
				 "expenses": [], "totalRevenue": "10.00", "totalExpenses": "0.00", "netProfit": "10.00"}
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/reports/balance-sheet?asOf=2026-06-30")).andRespond(withSuccess("""
				{"asOf": "2026-06-30", "assets": [], "liabilities": [], "equity": [], "earningsNotYetClosed": "0.00", "totalAssets": "0.00",
				 "totalLiabilities": "0.00", "totalEquity": "0.00", "totalLiabilitiesAndEquity": "0.00", "difference": "0.00"}
				""", MediaType.APPLICATION_JSON));

		IncomeStatementDto income = client.incomeStatement(LocalDate.of(2026, 1, 1), LocalDate.of(2026, 12, 31));
		BalanceSheetDto balance = client.balanceSheet(LocalDate.of(2026, 6, 30));

		assertThat(income.revenue().get(0).amount()).isEqualByComparingTo("10.00");
		assertThat(balance.difference()).isEqualByComparingTo("0");
	}

	@Test
	void closingAndReopeningAYearSendTheirBodies() {
		server.expect(requestTo(BASE + "/years/2026")).andRespond(withSuccess("""
				{"year": 2026, "closed": true, "entryId": 9, "entryNo": "JE-2026-000009"}
				""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/years/2026/close")).andExpect(method(HttpMethod.POST))
				.andExpect(content().json("{\"retainedEarningsAccountId\": 5}"))
				.andRespond(withSuccess("""
						{"year": 2026, "entryId": 9, "entryNo": "JE-2026-000009", "netProfit": "-20.00"}
						""", MediaType.APPLICATION_JSON));
		server.expect(requestTo(BASE + "/years/2026/reopen")).andExpect(method(HttpMethod.POST))
				.andExpect(content().json("{\"reason\": \"late\"}"))
				.andRespond(withSuccess("""
						{"year": 2026, "closed": false}
						""", MediaType.APPLICATION_JSON));

		assertThat(client.yearStatus(2026).entryNo()).isEqualTo("JE-2026-000009");
		assertThat(client.closeYear(2026, 5).netProfit()).isEqualByComparingTo("-20.00");
		assertThat(client.reopenYear(2026, "late").closed()).isFalse();
	}
}
