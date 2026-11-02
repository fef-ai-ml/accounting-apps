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
