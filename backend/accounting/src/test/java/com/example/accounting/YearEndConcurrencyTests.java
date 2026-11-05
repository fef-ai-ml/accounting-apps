package com.example.accounting;

import java.util.List;
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
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

/** Real concurrent transactions: one holds its work uncommitted until the other has had to wait or has been refused. */
class YearEndConcurrencyTests extends YearEndTestSupport {

	@Autowired
	YearEndService years;

	@Autowired
	PostingService posting;

	/** Runs the work in a transaction and holds it uncommitted until {@code release}. */
	private Future<?> holding(ExecutorService pool, String sub, Runnable work, CountDownLatch held, CountDownLatch release) {
		return pool.submit(() -> inTenantTransaction(tenant, sub, () -> {
			work.run();
			held.countDown();
			try {
				release.await(30, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return null;
		}));
	}

	private static void assertHeld(CountDownLatch held) throws Exception {
		assertThat(held.await(30, TimeUnit.SECONDS)).as("the holder got its locks").isTrue();
	}

	@Test
	void aCloseWaitsForAPostInProgressAndCountsIt() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		long id = draft("maker", YEAR + "-12-20", line(cash, "40.00", "0"), line(sales, "0", "40.00"));
		long version = versionOf(id);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> poster = holding(pool, "checker", () -> posting.post(id, version), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> closing = pool.submit(() -> closeYear(YEAR).andReturn().getResponse());
			Thread.sleep(3000);
			assertThat(closing.isDone()).as("the close waits for the post's hold on the December period").isFalse();
			release.countDown();
			poster.get();
			MockHttpServletResponse response = closing.get();
			assertThat(response.getStatus()).isEqualTo(200);
			assertThat((String) JsonPath.read(response.getContentAsString(), "$.netProfit")).isEqualTo("140.00");
		}
	}

	@Test
	void aReversalOrPostRacingACloseWaitsAndIsRefused() throws Exception {
		long earlier = sale(YEAR + "-03-10", "100.00");
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> closing = holding(pool, CLOSER, () -> years.close(YEAR, retained), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> reversing = pool.submit(() -> mvc
					.perform(post("/api/accounting/journal-entries/" + earlier + "/reverse").with(as("checker", READ, POST))
							.contentType(MediaType.APPLICATION_JSON).content("{\"date\": \"" + YEAR + "-06-15\"}"))
					.andReturn().getResponse());
			Thread.sleep(3000);
			assertThat(reversing.isDone()).as("the reversal waits for the close's locks (June period, entry sequence)").isFalse();
			release.countDown();
			closing.get();
			assertThat(reversing.get().getStatus()).isEqualTo(422);
			assertThat(reversing.get().getContentAsString()).contains("period-closed");
		}
		// The known Phase 1 race: a draft saved in the closed year cannot be posted, so it moves no money.
		long late = balancedDraft("maker", YEAR + "-05-01");
		postEntry(late, "checker").andExpect(problem(422, "period-closed"));
	}

	@Test
	void aDoubleCloseIsOneSuccessAndOneEntry() throws Exception {
		sale(YEAR + "-03-10", "100.00");
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			CountDownLatch go = new CountDownLatch(1);
			List<Future<MockHttpServletResponse>> both = List.of(pool.submit(() -> {
				go.await();
				return closeYear(YEAR).andReturn().getResponse();
			}), pool.submit(() -> {
				go.await();
				return closeYear(YEAR).andReturn().getResponse();
			}));
			go.countDown();
			assertThat(List.of(both.get(0).get().getStatus(), both.get(1).get().getStatus())).containsExactlyInAnyOrder(200, 409);
		}
		Number closes = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.journal_entry where closing_year = " + YEAR).getSingleResult());
		assertThat(closes.longValue()).isEqualTo(1);
	}

	@Test
	void aCloseWaitingOnAReopenOfTheYearBeforeIsRefused() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(earlier + "-05-01", "40.00");
		sale(YEAR + "-05-01", "60.00");
		closeYear(earlier).andExpect(status().isOk());
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> reopening = holding(pool, CLOSER, () -> years.reopen(earlier, "late"), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> closing = pool.submit(() -> closeYear(YEAR).andReturn().getResponse());
			Thread.sleep(3000);
			assertThat(closing.isDone()).as("the close waits for the reopen's locks (settings row, earlier periods)").isFalse();
			release.countDown();
			reopening.get();
			assertThat(closing.get().getStatus()).isEqualTo(409);
			assertThat(closing.get().getContentAsString()).contains("earlier-year-open");
		}
	}

	@Test
	void aReopenWaitingOnACloseOfTheYearAfterIsRefused() throws Exception {
		int earlier = YEAR - 1;
		openPeriods(earlier);
		sale(earlier + "-05-01", "40.00");
		sale(YEAR + "-05-01", "60.00");
		closeYear(earlier).andExpect(status().isOk());
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<?> closing = holding(pool, CLOSER, () -> years.close(YEAR, retained), held, release);
			assertHeld(held);
			Future<MockHttpServletResponse> reopening = pool.submit(() -> reopenYear(earlier, "late").andReturn().getResponse());
			Thread.sleep(3000);
			assertThat(reopening.isDone()).as("the reopen waits for the close's locks (settings row, earlier periods)").isFalse();
			release.countDown();
			closing.get();
			assertThat(reopening.get().getStatus()).isEqualTo(409);
			assertThat(reopening.get().getContentAsString()).contains("later-year-closed");
		}
	}

}
