package com.example.accounting;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.stream.IntStream;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockHttpServletResponse;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostingConcurrencyTests extends AccountingTestSupport {

	@Autowired
	PostingService posting;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
	}

	private MockHttpServletResponse postEntry(long id) throws Exception {
		return mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as("checker", READ, POST)))
				.andReturn().getResponse();
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
			posting.post(id);
			throw new IllegalStateException("roll back after numbering");
		})).hasMessage("roll back after numbering");
		long unbalanced = draft("maker", DATE, line(account("1110"), "1", "0"));
		assertThat(postEntry(unbalanced).getStatus()).isEqualTo(422);

		mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as("checker", READ, POST)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"));
	}

	@Test
	void aPostRacingACloseNeverLandsInAClosedPeriodAfterTheClose() throws Exception {
		String body = mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(admin()))
				.andReturn().getResponse().getContentAsString();
		long period = ((Number) JsonPath.read(body, "$[2].id")).longValue();
		ExecutorService pool = Executors.newFixedThreadPool(2);
		try {
			for (int round = 0; round < 10; round++) {
				long id = balancedDraft("maker", DATE);
				Future<MockHttpServletResponse> posted = pool.submit(() -> postEntry(id));
				Future<MockHttpServletResponse> closed = pool.submit(() -> mvc
						.perform(post("/api/accounting/periods/" + period + "/close").with(as("closer", PERIOD_MANAGE)))
						.andReturn().getResponse());
				assertThat(posted.get().getStatus()).isIn(200, 422);
				assertThat(closed.get().getStatus()).isIn(200, 409);

				Object[] state = inTransaction(() -> (Object[]) entityManager.createNativeQuery("""
						select p.status, e.status <> 'POSTED' or p.status = 'OPEN' or e.posted_at <= p.closed_at
						from accounting.journal_entry e join accounting.fiscal_period p on p.id = e.period_id
						where e.id = :id
						""").setParameter("id", id).getSingleResult());
				assertThat(state[1]).as("a post never lands after the close").isEqualTo(true);
				if ("CLOSED".equals(state[0])) {
					mvc.perform(post("/api/accounting/periods/" + period + "/reopen").with(as("closer", PERIOD_MANAGE))
							.contentType("application/json").content("{\"reason\": \"next round\"}"))
							.andExpect(status().isOk());
				}
			}
		}
		finally {
			pool.shutdownNow();
		}
	}

}
