package com.example.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

abstract class YearEndTestSupport extends AccountingTestSupport {

	static final String CLOSER = "closer";

	long cash;

	long sales;

	long rent;

	long retained;

	@BeforeEach
	void accounts() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
		rent = account("6120");
		retained = account("3200");
	}

	long posted(String date, String... lines) throws Exception {
		long id = draft("maker", date, lines);
		postEntry(id, "checker").andExpect(status().isOk());
		return id;
	}

	long sale(String date, String amount) throws Exception {
		return posted(date, line(cash, amount, "0"), line(sales, "0", amount));
	}

	long rentPaid(String date, String amount) throws Exception {
		return posted(date, line(rent, amount, "0"), line(cash, "0", amount));
	}

	void openPeriods(int year) throws Exception {
		mvc.perform(post("/api/accounting/periods/year/" + year).with(admin())).andExpect(status().isOk());
	}

	ResultActions closeYear(int year) throws Exception {
		return closeYear(year, retained);
	}

	ResultActions closeYear(int year, long account) throws Exception {
		return mvc.perform(post("/api/accounting/years/" + year + "/close").with(as(CLOSER, READ, PERIOD_MANAGE))
				.contentType(MediaType.APPLICATION_JSON).content("{\"retainedEarningsAccountId\": " + account + "}"));
	}

	ResultActions reopenYear(int year, String reason) throws Exception {
		return mvc.perform(post("/api/accounting/years/" + year + "/reopen").with(as(CLOSER, READ, PERIOD_MANAGE))
				.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"" + reason + "\"}"));
	}

	ResultActions entryOf(long id) throws Exception {
		return mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ)));
	}

	String body(ResultActions result) throws Exception {
		return result.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
	}

	long idIn(ResultActions result, String path) throws Exception {
		return ((Number) JsonPath.read(body(result), path)).longValue();
	}

	ResultActions income(String from, String to) throws Exception {
		return mvc.perform(get("/api/accounting/reports/income-statement").param("from", from).param("to", to).with(as("u", READ)));
	}

	ResultActions balanceSheet(String asOf) throws Exception {
		return mvc.perform(get("/api/accounting/reports/balance-sheet").param("asOf", asOf).with(as("u", READ)));
	}

	ResultActions periodsOf(int year) throws Exception {
		return mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(year)).with(as("u", READ)));
	}

	long periodId(int year, int month) throws Exception {
		return idIn(periodsOf(year), "$[" + (month - 1) + "].id");
	}

	void deactivate(long account) {
		inTransaction(() -> sql("update accounting.account set active = false where id = :id", account));
	}

}
