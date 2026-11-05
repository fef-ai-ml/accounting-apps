package com.example.accounting;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.everyItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class YearCloseTests extends YearEndTestSupport {

	@Autowired
	PostingService posting;

	@Test
	void aProfitMovesToRetainedEarningsAndEveryMonthCloses() throws Exception {
		sale(YEAR + "-03-10", "1000.00");
		rentPaid(YEAR + "-07-10", "300.00");

		closeYear(YEAR)
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.year").value(YEAR))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000003"))
				.andExpect(jsonPath("$.netProfit").value("700.00"));

		long entry = idIn(mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u", READ))), "$.entryId");
		entryOf(entry)
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.closingYear").value(YEAR))
				.andExpect(jsonPath("$.date").value(YEAR + "-12-31"))
				.andExpect(jsonPath("$.description").value("Closing entry for " + YEAR))
				.andExpect(jsonPath("$.postedByName").value("closer-name"))
				.andExpect(jsonPath("$.lines", hasSize(3)))
				.andExpect(jsonPath("$.lines[0].accountId").value(sales))
				.andExpect(jsonPath("$.lines[0].debit").value("1000.00"))
				.andExpect(jsonPath("$.lines[1].accountId").value(rent))
				.andExpect(jsonPath("$.lines[1].credit").value("300.00"))
				.andExpect(jsonPath("$.lines[2].accountId").value(retained))
				.andExpect(jsonPath("$.lines[2].credit").value("700.00"));
		periodsOf(YEAR).andExpect(jsonPath("$[*].status", everyItem(is("CLOSED"))));
		mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u", READ)))
				.andExpect(jsonPath("$.closed").value(true))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000003"));

		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'YEAR_CLOSE'").getSingleResult());
		assertThat(details).contains("\"year\": " + YEAR, "JE-" + YEAR + "-000003", "700.00", "3200");
	}

	@Test
	void aLossIsADebitOnRetainedEarnings() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		rentPaid(YEAR + "-07-10", "300.00");

		long entry = idIn(closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("-200.00")), "$.entryId");

		entryOf(entry)
				.andExpect(jsonPath("$.lines[2].accountId").value(retained))
				.andExpect(jsonPath("$.lines[2].debit").value("200.00"));
	}

	@Test
	void anAccountRunningAgainstItsNormalSideGetsTheOtherSide() throws Exception {
		long otherIncome = account("4900");
		sale(YEAR + "-03-10", "100.00");
		posted(YEAR + "-04-10", line(otherIncome, "30.00", "0"), line(cash, "0", "30.00"));

		long entry = idIn(closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("70.00")), "$.entryId");

		entryOf(entry)
				.andExpect(jsonPath("$.lines[0].accountId").value(sales))
				.andExpect(jsonPath("$.lines[0].debit").value("100.00"))
				.andExpect(jsonPath("$.lines[1].accountId").value(otherIncome))
				.andExpect(jsonPath("$.lines[1].credit").value("30.00"))
				.andExpect(jsonPath("$.lines[2].credit").value("70.00"));
	}

	@Test
	void aZeroNetHasNoRetainedEarningsLine() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		rentPaid(YEAR + "-07-10", "100.00");

		long entry = idIn(closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("0.00")), "$.entryId");

		entryOf(entry).andExpect(jsonPath("$.lines", hasSize(2)));
	}

	@Test
	void theMakerOfAnEntryMayCloseTheYearWithMakerCheckerOn() throws Exception {
		long id = draft(CLOSER, YEAR + "-03-10", line(cash, "10.00", "0"), line(sales, "0", "10.00"));
		postEntry(id, "checker").andExpect(status().isOk());

		closeYear(YEAR).andExpect(status().isOk());
	}

	@Test
	void linesRemovedFromADraftAreNotClosed() throws Exception {
		long id = draft("maker", YEAR + "-03-10", line(cash, "1000.00", "0"), line(sales, "0", "1000.00"));
		mvc.perform(org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put("/api/accounting/journal-entries/" + id)
				.with(as("editor", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-03-10", line(cash, "30.00", "0"), line(sales, "0", "30.00"))
						.replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(status().isOk());

		closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("30.00"));
	}

	@Test
	void aClosedDecemberIsReopenedForTheCloseAndClosedMonthsKeepTheirCloser() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		mvc.perform(post("/api/accounting/periods/" + periodId(YEAR, 3) + "/close").with(as("early", PERIOD_MANAGE))).andExpect(status().isOk());
		mvc.perform(post("/api/accounting/periods/" + periodId(YEAR, 12) + "/close").with(as("early", PERIOD_MANAGE))).andExpect(status().isOk());

		closeYear(YEAR).andExpect(status().isOk());

		periodsOf(YEAR)
				.andExpect(jsonPath("$[2].closedByName").value("early-name"))
				.andExpect(jsonPath("$[0].closedByName").value("closer-name"))
				.andExpect(jsonPath("$[11].closedByName").value("closer-name"));
		Number monthRows = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'PERIOD_CLOSE'").getSingleResult());
		assertThat(monthRows.longValue()).as("the close writes no PERIOD_CLOSE rows of its own").isEqualTo(2);
	}

	@Test
	void theBalanceSheetIsUnchangedByACloseAndNextYearStartsFromZero() throws Exception {
		sale(YEAR + "-03-10", "1000.00");
		rentPaid(YEAR + "-07-10", "300.00");
		String june = body(balanceSheet(YEAR + "-06-30"));
		balanceSheet(YEAR + "-12-31").andExpect(jsonPath("$.earningsNotYetClosed").value("700.00"));

		closeYear(YEAR).andExpect(status().isOk());

		assertThat(body(balanceSheet(YEAR + "-06-30"))).isEqualTo(june);
		balanceSheet(YEAR + "-12-31")
				.andExpect(jsonPath("$.earningsNotYetClosed").value("0.00"))
				.andExpect(jsonPath("$.totalEquity").value("700.00"))
				.andExpect(jsonPath("$.difference").value("0.00"));
		income(YEAR + "-01-01", YEAR + "-12-31").andExpect(jsonPath("$.netProfit").value("700.00"));

		int next = YEAR + 1;
		openPeriods(next);
		sale(next + "-02-01", "50.00");
		closeYear(next).andExpect(jsonPath("$.netProfit").value("50.00"));
		balanceSheet(next + "-12-31").andExpect(jsonPath("$.totalEquity").value("750.00"));
	}

	@Test
	void aYearWithNoPeriodsOrAnIncompleteSetIsRefused() throws Exception {
		closeYear(1999).andExpect(problem(422, "no-period"));
		inTransaction(() -> insertPeriod(1998, 1));
		closeYear(1998).andExpect(problem(422, "no-period"));
	}

	@Test
	void draftsInTheYearBlockTheCloseAndAreCounted() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		balancedDraft("maker", YEAR + "-05-01");
		balancedDraft("maker", YEAR + "-06-01");

		closeYear(YEAR).andExpect(problem(409, "year-has-drafts")).andExpect(jsonPath("$.detail", containsString("the 2 draft")));
		periodsOf(YEAR).andExpect(jsonPath("$[0].status").value("OPEN"));
	}

	@Test
	void anEarlierYearWithABalanceMustCloseFirst() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(earlier + "-05-01", "40.00");
		sale(YEAR + "-05-01", "60.00");

		closeYear(YEAR).andExpect(problem(409, "earlier-year-open")).andExpect(jsonPath("$.detail", containsString("4100")));

		closeYear(earlier).andExpect(jsonPath("$.netProfit").value("40.00"));
		closeYear(YEAR).andExpect(jsonPath("$.netProfit").value("60.00"));
	}

	@Test
	void aYearWithNothingToCloseDoesNotBlockTheNext() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		posted(earlier + "-05-01", line(cash, "40.00", "0"), line(account("3100"), "0", "40.00"));
		sale(YEAR + "-05-01", "60.00");

		closeYear(earlier).andExpect(problem(409, "nothing-to-close"));
		periodsOf(earlier).andExpect(jsonPath("$[0].status").value("OPEN"));
		closeYear(YEAR).andExpect(status().isOk());
	}

	@Test
	void aSecondCloseSaysTheYearIsClosed() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		closeYear(YEAR).andExpect(status().isOk());

		closeYear(YEAR).andExpect(problem(409, "year-closed"));
	}

	@Test
	void aDeactivatedAccountWithABalanceIsNamed() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		deactivate(sales);

		closeYear(YEAR).andExpect(problem(422, "account-not-postable")).andExpect(jsonPath("$.detail", containsString("4100")));
	}

	@Test
	void theRetainedEarningsAccountMustBeUsableEquity() throws Exception {
		sale(YEAR + "-03-10", "100.00");

		closeYear(YEAR, cash).andExpect(problem(422, "bad-retained-earnings-account"));
		closeYear(YEAR, account("3000")).andExpect(problem(422, "bad-retained-earnings-account"));
		closeYear(YEAR, 999999999L).andExpect(problem(422, "bad-retained-earnings-account"));
		deactivate(retained);
		closeYear(YEAR).andExpect(problem(422, "bad-retained-earnings-account"));
	}

	@Test
	void anotherTenantsAccountIsNotARetainedEarningsAccount() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		UUID other = UUID.randomUUID();
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
		String accounts = mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ))).andReturn().getResponse().getContentAsString();
		List<Number> theirs = JsonPath.read(accounts, "$[?(@.code=='3200')].id");

		closeYear(YEAR, theirs.get(0).longValue()).andExpect(problem(422, "bad-retained-earnings-account"));
	}

	@Test
	void permissionsAndBodyAreChecked() throws Exception {
		mvc.perform(post("/api/accounting/years/" + YEAR + "/close").with(as("b", READ, WRITE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"retainedEarningsAccountId\": " + retained + "}"))
				.andExpect(problem(403, "forbidden"));
		mvc.perform(post("/api/accounting/years/" + YEAR + "/close").with(as(CLOSER, PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{}"))
				.andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u"))).andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/years/" + YEAR).with(as("u", READ)))
				.andExpect(jsonPath("$.closed").value(false))
				.andExpect(jsonPath("$.entryId").isEmpty());
	}

	@Test
	void aCompanyNotSetUpIsToldSo() throws Exception {
		mvc.perform(post("/api/accounting/years/" + YEAR + "/close").with(asTenant(UUID.randomUUID(), "o", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"retainedEarningsAccountId\": 1}"))
				.andExpect(problem(422, "not-initialized"));
	}

	@Test
	void aCloseWaitsForAPostIntoAnEarlierYearAndIsRefused() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(YEAR + "-03-10", "100.00");
		long id = draft("maker", earlier + "-06-01", line(cash, "40.00", "0"), line(sales, "0", "40.00"));
		long version = versionOf(id);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> poster = pool.submit(() -> inTenantTransaction(tenant, "checker", () -> {
				posting.post(id, version);
				held.countDown();
				try {
					release.await(30, TimeUnit.SECONDS);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return null;
			}));
			assertThat(held.await(30, TimeUnit.SECONDS)).isTrue();
			Future<MockHttpServletResponse> closing = pool.submit(() -> closeYear(YEAR).andReturn().getResponse());
			Thread.sleep(3000);
			assertThat(closing.isDone()).as("the close waits for the post's lock on the earlier month").isFalse();
			release.countDown();
			poster.get();
			assertThat(closing.get().getStatus()).isEqualTo(409);
			assertThat(closing.get().getContentAsString()).contains("earlier-year-open");
		}
	}

	@Test
	void aYearOfEntriesWrittenWithoutTheServicesCloses() throws Exception {
		long march = periodId(YEAR, 3);
		inTransaction(() -> {
			long entry = insertEntry(march, "DRAFT");
			insertLine(entry, 1, cash, "100.00", "0");
			insertLine(entry, 2, sales, "0", "100.00");
			postBySql(entry);
			return null;
		});
		closeYear(YEAR).andExpect(status().isOk()).andExpect(jsonPath("$.netProfit").value("100.00"));
	}

}
