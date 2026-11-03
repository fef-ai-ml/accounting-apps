package com.example.accounting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostingConcurrencyTests extends AccountingTestSupport {

	@Autowired
	PostingService posting;

	@Autowired
	PeriodService periodService;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
	}

	private MockHttpServletResponse postEntry(long id) throws Exception {
		return postEntry(id, versionOf(id), "checker").andReturn().getResponse();
	}

	/** Runs the same request on two threads at once and returns both responses. */
	private List<MockHttpServletResponse> inParallel(Callable<MockHttpServletResponse> request) throws Exception {
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			CountDownLatch go = new CountDownLatch(1);
			Callable<MockHttpServletResponse> gated = () -> {
				go.await();
				return request.call();
			};
			List<Future<MockHttpServletResponse>> both = List.of(pool.submit(gated), pool.submit(gated));
			go.countDown();
			List<MockHttpServletResponse> responses = new ArrayList<>();
			for (Future<MockHttpServletResponse> future : both) {
				responses.add(future.get());
			}
			return responses;
		}
		finally {
			pool.shutdownNow();
		}
	}

	private long count(String sql) {
		return inTransaction(() -> ((Number) entityManager.createNativeQuery(sql).getSingleResult()).longValue());
	}

	@Test
	void aDoublePostIsOneSuccessOneConflictAndOneNumber() throws Exception {
		long id = balancedDraft("maker", DATE);
		long version = versionOf(id);
		List<MockHttpServletResponse> responses = inParallel(() -> postEntry(id, version, "checker").andReturn().getResponse());

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
		assertThat(count("select next_value from accounting.entry_sequence where year = " + YEAR)).isEqualTo(2);
		assertThat(postEntry(balancedDraft("maker", DATE)).getStatus()).isEqualTo(200);
	}

	@Test
	void aDoubleReverseIsOneSuccessOneConflictAndOneReversal() throws Exception {
		long id = balancedDraft("maker", DATE);
		assertThat(postEntry(id).getStatus()).isEqualTo(200);
		List<MockHttpServletResponse> responses = inParallel(() -> mvc
				.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", READ, POST))
						.contentType("application/json").content("{\"date\": \"" + DATE + "\"}"))
				.andReturn().getResponse());

		assertThat(responses).extracting(MockHttpServletResponse::getStatus).containsExactlyInAnyOrder(200, 409);
		assertThat(count("select count(*) from accounting.journal_entry where reversal_of = " + id)).isEqualTo(1);
	}

	@Test
	void twentyParallelPostsGetGaplessNumbers() throws Exception {
		List<Long> drafts = new ArrayList<>();
		for (int i = 0; i < 20; i++) {
			drafts.add(balancedDraft("maker", DATE));
		}
		ExecutorService pool = Executors.newFixedThreadPool(20);
		try {
			List<Callable<MockHttpServletResponse>> posts = new ArrayList<>();
			drafts.forEach(id -> posts.add(() -> postEntry(id)));
			List<String> numbers = new ArrayList<>();
			for (Future<MockHttpServletResponse> result : pool.invokeAll(posts)) {
				MockHttpServletResponse response = result.get();
				assertThat(response.getStatus()).isEqualTo(200);
				numbers.add(JsonPath.read(response.getContentAsString(), "$.entryNo"));
			}
			List<String> expected = IntStream.rangeClosed(1, 20)
					.mapToObj(n -> "JE-%d-%06d".formatted(YEAR, n))
					.toList();
			assertThat(numbers).containsExactlyInAnyOrderElementsOf(expected);
		}
		finally {
			pool.shutdownNow();
		}
	}

	@Test
	void aFailedPostConsumesNoNumber() throws Exception {
		long id = balancedDraft("maker", DATE);
		// A number is allocated, then the transaction rolls back after it, as a failure at commit would.
		assertThatThrownBy(() -> inTenantTransaction(tenant, "checker", () -> {
			posting.post(id, 0);
			throw new IllegalStateException("roll back after numbering");
		})).hasMessage("roll back after numbering");
		long unbalanced = draft("maker", DATE, line(account("1110"), "1", "0"));
		assertThat(postEntry(unbalanced).getStatus()).isEqualTo(422);

		postEntry(id, "checker")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"));
	}

	@Test
	void aReversalRacingACloseWaitsAndIsRefused() throws Exception {
		long id = balancedDraft("maker", DATE);
		assertThat(postEntry(id).getStatus()).isEqualTo(200);
		String body = mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(admin()))
				.andReturn().getResponse().getContentAsString();
		long june = ((Number) JsonPath.read(body, "$[5].id")).longValue();
		CountDownLatch closed = new CountDownLatch(1);
		ExecutorService pool = Executors.newSingleThreadExecutor();
		try {
			// The close stays uncommitted for 500 ms after it has run, so a reversal without the lock sees OPEN.
			Future<?> closing = pool.submit(() -> inTenantTransaction(tenant, "closer", () -> {
				periodService.close(june);
				closed.countDown();
				try {
					Thread.sleep(500);
				}
				catch (InterruptedException ex) {
					throw new IllegalStateException(ex);
				}
				return null;
			}));
			assertThat(closed.await(30, java.util.concurrent.TimeUnit.SECONDS)).isTrue();
			mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", READ, POST))
					.contentType("application/json").content("{\"date\": \"" + YEAR + "-06-15\"}"))
					.andExpect(problem(422, "period-closed"));
			closing.get();
		}
		finally {
			pool.shutdownNow();
		}
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ)))
				.andExpect(jsonPath("$.status").value("POSTED"));
	}

	private MockHttpServletResponse deleteDraft(long id, long version) throws Exception {
		return mvc.perform(delete("/api/accounting/journal-entries/" + id).param("version", String.valueOf(version))
				.with(as("deleter", WRITE))).andReturn().getResponse();
	}

	/** Holds the entry's row until {@code release}, then commits; {@code held} says the lock is taken. */
	private Future<Integer> holding(ExecutorService pool, long id, String statement, CountDownLatch held, CountDownLatch release) {
		return pool.submit(() -> inTransaction(() -> {
			int changed = sql(statement, id);
			held.countDown();
			try {
				release.await(10, TimeUnit.SECONDS);
			}
			catch (InterruptedException ex) {
				Thread.currentThread().interrupt();
			}
			return changed;
		}));
	}

	@Test
	void aPostWaitingOnADeleteGetsNotFound() throws Exception {
		long id = balancedDraft("maker", DATE);
		long version = versionOf(id);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Integer> deleter = holding(pool, id,
					"update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", held, release);
			held.await();
			Future<MockHttpServletResponse> poster = pool.submit(() -> postEntry(id, version, "checker").andReturn().getResponse());
			Thread.sleep(300);
			assertThat(poster.isDone()).as("the post waits on the row lock").isFalse();
			release.countDown();
			assertThat(deleter.get()).isEqualTo(1);
			assertThat(poster.get().getStatus()).isEqualTo(404);
		}
	}

	@Test
	void aDeleteWaitingOnAPostGetsNotDraft() throws Exception {
		long id = balancedDraft("maker", DATE);
		long version = versionOf(id);
		CountDownLatch held = new CountDownLatch(1);
		CountDownLatch release = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Integer> poster = holding(pool, id,
					"update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-RACE' where id = :id", held, release);
			held.await();
			Future<MockHttpServletResponse> deleter = pool.submit(() -> deleteDraft(id, version));
			Thread.sleep(300);
			assertThat(deleter.isDone()).as("the delete waits on the row lock").isFalse();
			release.countDown();
			assertThat(poster.get()).isEqualTo(1);
			assertThat(deleter.get().getStatus()).isEqualTo(409);
			assertThat(deleter.get().getContentAsString()).contains("not-draft");
		}
	}

	@Test
	void aDeleteRacingAPostNeverAnswers500() throws Exception {
		for (int i = 0; i < 10; i++) {
			long id = balancedDraft("maker", DATE);
			long version = versionOf(id);
			try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
				CountDownLatch go = new CountDownLatch(1);
				Future<MockHttpServletResponse> poster = pool.submit(() -> {
					go.await();
					return postEntry(id, version, "checker").andReturn().getResponse();
				});
				Future<MockHttpServletResponse> deleter = pool.submit(() -> {
					go.await();
					return deleteDraft(id, version);
				});
				go.countDown();
				assertThat(List.of(poster.get().getStatus(), deleter.get().getStatus()))
						.isIn(List.of(200, 409), List.of(404, 204));
			}
		}
	}

}
