package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class ReportTests extends AccountingTestSupport {

	private long cash;

	private long sales;

	private long rent;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
		rent = account("6120");
	}

	private long posted(String date, String... lines) throws Exception {
		long id = draft("maker", date, lines);
		postEntry(id, "checker").andExpect(status().isOk());
		return id;
	}

	private org.springframework.test.web.servlet.ResultActions trialBalance(String asOf) throws Exception {
		return mvc.perform(get("/api/accounting/reports/trial-balance").param("asOf", asOf).with(as("u", READ)));
	}

	private org.springframework.test.web.servlet.ResultActions ledger(long account, String from, String to) throws Exception {
		return mvc.perform(get("/api/accounting/reports/general-ledger").param("accountId", String.valueOf(account))
				.param("from", from).param("to", to).with(as("u", READ)));
	}

	@Test
	void trialBalanceShowsBalancesOnTheNormalSideAndZeroDifference() throws Exception {
		posted(YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		posted(YEAR + "-01-20", line(rent, "300.00", "0"), line(cash, "0", "300.00"));

		trialBalance(YEAR + "-12-31")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.rows", hasSize(3)))
				.andExpect(jsonPath("$.rows[0].code").value("1110"))
				.andExpect(jsonPath("$.rows[0].debit").value("1000.00"))
				.andExpect(jsonPath("$.rows[0].credit").value("300.00"))
				.andExpect(jsonPath("$.rows[0].balance").value("700.00"))
				.andExpect(jsonPath("$.rows[1].code").value("4100"))
				.andExpect(jsonPath("$.rows[1].balance").value("1000.00"))
				.andExpect(jsonPath("$.rows[2].code").value("6120"))
				.andExpect(jsonPath("$.rows[2].balance").value("300.00"))
				.andExpect(jsonPath("$.totalDebit").value("1300.00"))
				.andExpect(jsonPath("$.totalCredit").value("1300.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void reportsRespectTheDateAndIgnoreDrafts() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		posted(YEAR + "-02-10", line(cash, "20.00", "0"), line(sales, "0", "20.00"));
		draft("maker", YEAR + "-01-15", line(cash, "99.00", "0"), line(sales, "0", "99.00"));

		trialBalance(YEAR + "-01-31")
				.andExpect(jsonPath("$.totalDebit").value("10.00"))
				.andExpect(jsonPath("$.rows", hasSize(2)));
	}

	@Test
	void anEntryAndItsReversalNetToZero() throws Exception {
		long id = posted(YEAR + "-01-10", line(cash, "50.00", "0"), line(sales, "0", "50.00"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", POST))
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + YEAR + "-01-11\"}")).andExpect(status().isOk());

		trialBalance(YEAR + "-12-31")
				.andExpect(jsonPath("$.rows[0].balance").value("0.00"))
				.andExpect(jsonPath("$.rows[1].balance").value("0.00"))
				.andExpect(jsonPath("$.totalDebit").value("100.00"))
				.andExpect(jsonPath("$.totalCredit").value("100.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void aContraAssetShowsAsANegativeBalance() throws Exception {
		long depreciation = account("1290");
		posted(YEAR + "-01-10", line(account("6140"), "5.00", "0"), line(depreciation, "0", "5.00"));
		trialBalance(YEAR + "-12-31").andExpect(jsonPath("$.rows[0].balance").value("-5.00"));
	}

	@Test
	void generalLedgerRunsTheBalanceFromTheOpeningBalance() throws Exception {
		posted(YEAR + "-01-10", line(cash, "100.00", "0"), line(sales, "0", "100.00"));
		posted(YEAR + "-02-05", line(cash, "40.00", "0"), line(sales, "0", "40.00"));
		posted(YEAR + "-02-20", line(rent, "15.00", "0"), line(cash, "0", "15.00"));
		posted(YEAR + "-03-01", line(cash, "7.00", "0"), line(sales, "0", "7.00"));

		ledger(cash, YEAR + "-02-01", YEAR + "-02-28")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.accountCode").value("1110"))
				.andExpect(jsonPath("$.openingBalance").value("100.00"))
				.andExpect(jsonPath("$.lines", hasSize(2)))
				.andExpect(jsonPath("$.lines[0].date").value(YEAR + "-02-05"))
				.andExpect(jsonPath("$.lines[0].entryNo").value("JE-" + YEAR + "-000002"))
				.andExpect(jsonPath("$.lines[0].debit").value("40.00"))
				.andExpect(jsonPath("$.lines[0].runningBalance").value("140.00"))
				.andExpect(jsonPath("$.lines[1].credit").value("15.00"))
				.andExpect(jsonPath("$.lines[1].runningBalance").value("125.00"))
				.andExpect(jsonPath("$.closingBalance").value("125.00"));
		ledger(sales, YEAR + "-01-01", YEAR + "-01-31").andExpect(jsonPath("$.closingBalance").value("100.00"));
	}

	@Test
	void anAccountWithNoActivityHasAnEmptyLedger() throws Exception {
		ledger(rent, YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.openingBalance").value("0.00"))
				.andExpect(jsonPath("$.lines", hasSize(0)))
				.andExpect(jsonPath("$.closingBalance").value("0.00"));
	}

	@Test
	void parametersAreChecked() throws Exception {
		ledger(cash, YEAR + "-02-01", YEAR + "-01-01").andExpect(problem(400, "validation"));
		ledger(999999999L, YEAR + "-01-01", YEAR + "-02-01").andExpect(problem(404, "not-found"));
		mvc.perform(get("/api/accounting/reports/general-ledger").param("accountId", String.valueOf(cash)).with(as("u", READ)))
				.andExpect(problem(400, "validation"));
		trialBalance("nope").andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/reports/trial-balance").with(as("u"))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void trialBalanceDefaultsToToday() throws Exception {
		mvc.perform(get("/api/accounting/reports/trial-balance").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.asOf").value(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).toString()));
	}

	@Test
	void tenantsSeeOnlyTheirOwnFigures() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		UUID other = UUID.randomUUID();
		mvc.perform(get("/api/accounting/reports/trial-balance").param("asOf", YEAR + "-12-31").with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$.rows", hasSize(0)))
				.andExpect(jsonPath("$.totalDebit").value("0.00"));
		mvc.perform(get("/api/accounting/reports/general-ledger").param("accountId", String.valueOf(cash))
						.param("from", YEAR + "-01-01").param("to", YEAR + "-12-31").with(asTenant(other, "o", READ)))
				.andExpect(problem(404, "not-found"));
	}

	private void edit(long id, String... lines) throws Exception {
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/accounting/journal-entries/" + id)
				.with(as("editor", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-01-10", lines).replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
	}

	@Test
	void anEntryEditedBeforePostingCountsOnlyItsLiveLines() throws Exception {
		long id = draft("maker", YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		edit(id, line(cash, "30.00", "0"), line(sales, "0", "30.00"));
		postEntry(id, "checker").andExpect(status().isOk());

		trialBalance(YEAR + "-12-31")
				.andExpect(jsonPath("$.rows[0].debit").value("30.00"))
				.andExpect(jsonPath("$.totalDebit").value("30.00"))
				.andExpect(jsonPath("$.totalCredit").value("30.00"));
		ledger(cash, YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(jsonPath("$.openingBalance").value("0.00"))
				.andExpect(jsonPath("$.lines", hasSize(1)))
				.andExpect(jsonPath("$.lines[0].debit").value("30.00"));
		ledger(cash, YEAR + "-02-01", YEAR + "-12-31")
				.andExpect(jsonPath("$.openingBalance").value("30.00"));
	}
}
