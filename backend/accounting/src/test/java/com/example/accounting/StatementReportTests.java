package com.example.accounting;

import java.time.LocalDate;
import java.time.ZoneId;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class StatementReportTests extends AccountingTestSupport {

	@Autowired
	ReportService reports;

	private long cash;

	private long sales;

	private long rent;

	private long retained;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
		rent = account("6120");
		retained = account("3200");
	}

	private long posted(String date, String... lines) throws Exception {
		long id = draft("maker", date, lines);
		postEntry(id, "checker").andExpect(status().isOk());
		return id;
	}

	private ResultActions income(String from, String to) throws Exception {
		return mvc.perform(get("/api/accounting/reports/income-statement").param("from", from).param("to", to).with(as("u", READ)));
	}

	private ResultActions balanceSheet(String asOf) throws Exception {
		return mvc.perform(get("/api/accounting/reports/balance-sheet").param("asOf", asOf).with(as("u", READ)));
	}

	@Test
	void theIncomeStatementShowsEachSectionByHierarchyAndTheNet() throws Exception {
		posted(YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		posted(YEAR + "-01-20", line(rent, "300.00", "0"), line(cash, "0", "300.00"));
		posted(YEAR + "-02-20", line(account("7100"), "50.00", "0"), line(cash, "0", "50.00"));

		income(YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.from").value(YEAR + "-01-01"))
				.andExpect(jsonPath("$.revenue", hasSize(2)))
				.andExpect(jsonPath("$.revenue[0].code").value("4000"))
				.andExpect(jsonPath("$.revenue[0].depth").value(0))
				.andExpect(jsonPath("$.revenue[0].amount").value("1000.00"))
				.andExpect(jsonPath("$.revenue[1].code").value("4100"))
				.andExpect(jsonPath("$.revenue[1].depth").value(1))
				.andExpect(jsonPath("$.expenses", hasSize(4)))
				.andExpect(jsonPath("$.expenses[0].code").value("6000"))
				.andExpect(jsonPath("$.expenses[0].amount").value("300.00"))
				.andExpect(jsonPath("$.expenses[2].code").value("7000"))
				.andExpect(jsonPath("$.totalRevenue").value("1000.00"))
				.andExpect(jsonPath("$.totalExpenses").value("350.00"))
				.andExpect(jsonPath("$.netProfit").value("650.00"));
	}

	@Test
	void theIncomeStatementCountsOnlyItsRange() throws Exception {
		posted(YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		posted(YEAR + "-02-20", line(rent, "50.00", "0"), line(cash, "0", "50.00"));

		income(YEAR + "-02-01", YEAR + "-02-28")
				.andExpect(jsonPath("$.revenue", hasSize(0)))
				.andExpect(jsonPath("$.totalRevenue").value("0.00"))
				.andExpect(jsonPath("$.netProfit").value("-50.00"));
	}

	@Test
	void draftsAreIgnoredAndAReversedEntryNetsToNothing() throws Exception {
		draft("maker", YEAR + "-01-10", line(cash, "9.00", "0"), line(sales, "0", "9.00"));
		long id = posted(YEAR + "-01-10", line(cash, "50.00", "0"), line(sales, "0", "50.00"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", POST))
				.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + YEAR + "-01-11\"}")).andExpect(status().isOk());

		income(YEAR + "-01-01", YEAR + "-12-31")
				.andExpect(jsonPath("$.revenue", hasSize(0)))
				.andExpect(jsonPath("$.netProfit").value("0.00"));
	}

	@Test
	void linesRemovedFromADraftAreNotCounted() throws Exception {
		long id = draft("maker", YEAR + "-01-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("editor", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-01-10", line(cash, "30.00", "0"), line(sales, "0", "30.00"))
						.replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(status().isOk());

		income(YEAR + "-01-01", YEAR + "-12-31").andExpect(jsonPath("$.totalRevenue").value("30.00"));
		balanceSheet(YEAR + "-12-31").andExpect(jsonPath("$.totalAssets").value("30.00"));
	}

	@Test
	void parametersAreChecked() throws Exception {
		income(YEAR + "-02-01", YEAR + "-01-01").andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/reports/income-statement").param("from", YEAR + "-01-01").with(as("u", READ)))
				.andExpect(problem(400, "validation"));
		balanceSheet("nope").andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/reports/income-statement").param("from", YEAR + "-01-01").param("to", YEAR + "-02-01").with(as("u")))
				.andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/reports/balance-sheet").with(as("u"))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void theBalanceSheetBalancesWithAContraAssetAndUnclosedEarnings() throws Exception {
		posted(YEAR + "-01-05", line(cash, "1000.00", "0"), line(account("3100"), "0", "1000.00"));
		posted(YEAR + "-01-10", line(account("6140"), "5.00", "0"), line(account("1290"), "0", "5.00"));

		balanceSheet(YEAR + "-06-30")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.asOf").value(YEAR + "-06-30"))
				.andExpect(jsonPath("$.assets[0].code").value("1000"))
				.andExpect(jsonPath("$.assets[0].amount").value("995.00"))
				.andExpect(jsonPath("$.assets[1].code").value("1100"))
				.andExpect(jsonPath("$.assets[2].code").value("1110"))
				.andExpect(jsonPath("$.assets[2].depth").value(2))
				.andExpect(jsonPath("$.assets[3].code").value("1200"))
				.andExpect(jsonPath("$.assets[3].amount").value("-5.00"))
				.andExpect(jsonPath("$.assets[4].code").value("1290"))
				.andExpect(jsonPath("$.liabilities", hasSize(0)))
				.andExpect(jsonPath("$.equity[0].code").value("3000"))
				.andExpect(jsonPath("$.equity[1].code").value("3100"))
				.andExpect(jsonPath("$.earningsNotYetClosed").value("-5.00"))
				.andExpect(jsonPath("$.totalAssets").value("995.00"))
				.andExpect(jsonPath("$.totalLiabilities").value("0.00"))
				.andExpect(jsonPath("$.totalEquity").value("995.00"))
				.andExpect(jsonPath("$.totalLiabilitiesAndEquity").value("995.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void theBalanceSheetDefaultsToToday() throws Exception {
		mvc.perform(get("/api/accounting/reports/balance-sheet").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.asOf").value(LocalDate.now(ZoneId.of("Asia/Jakarta")).toString()));
	}

	@Test
	void aClosingEntryIsLeftOutOfTheIncomeStatementAndCountedOnTheBalanceSheet() throws Exception {
		posted(YEAR + "-01-10", line(cash, "100.00", "0"), line(sales, "0", "100.00"));
		inTransaction(() -> closeBySql(YEAR, sales, retained));

		income(YEAR + "-01-01", YEAR + "-12-31").andExpect(jsonPath("$.totalRevenue").value("100.00"));
		balanceSheet(YEAR + "-12-31")
				.andExpect(jsonPath("$.equity[1].code").value("3200"))
				.andExpect(jsonPath("$.equity[1].amount").value("100.00"))
				.andExpect(jsonPath("$.earningsNotYetClosed").value("0.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
		balanceSheet(YEAR + "-06-30")
				.andExpect(jsonPath("$.equity", hasSize(0)))
				.andExpect(jsonPath("$.earningsNotYetClosed").value("100.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
	}

	@Test
	void tenantsSeeOnlyTheirOwnFigures() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		UUID other = UUID.randomUUID();

		mvc.perform(get("/api/accounting/reports/income-statement").param("from", YEAR + "-01-01").param("to", YEAR + "-12-31")
						.with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$.revenue", hasSize(0)));
		assertThat(inTenantTransaction(other, "o", () -> reports.debitMinusCredit(LocalDate.of(YEAR, 12, 31)))).isEmpty();
		assertThat(inTransaction(() -> reports.debitMinusCredit(LocalDate.of(YEAR, 12, 31)))).isNotEmpty();
	}

	@Test
	void rowLevelSecurityAloneHidesAnotherTenantsLinesFromTheReportTables() throws Exception {
		posted(YEAR + "-01-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		String query = """
				select count(*) from accounting.journal_line l
				join accounting.journal_entry e on e.tenant_id = l.tenant_id and e.id = l.entry_id
				""";

		assertThat(inTenantTransaction(UUID.randomUUID(), "o", () -> (Number) entityManager.createNativeQuery(query).getSingleResult()).longValue()).isZero();
		assertThat(inTransaction(() -> (Number) entityManager.createNativeQuery(query).getSingleResult()).longValue()).isEqualTo(2);
	}

}
