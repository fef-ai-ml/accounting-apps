package com.example.accounting;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PeriodTests extends AccountingTestSupport {

	@BeforeEach
	void setup() throws Exception {
		mvc.perform(post("/api/accounting/setup").with(admin())).andExpect(status().isOk());
	}

	private long periodId(int month) throws Exception {
		String body = mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(admin()))
				.andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$[" + (month - 1) + "].id")).longValue();
	}

	@Test
	void periodsAreListedByYear() throws Exception {
		mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(12)))
				.andExpect(jsonPath("$[1].month").value(2))
				.andExpect(jsonPath("$[1].startDate").value(YEAR + "-02-01"))
				.andExpect(jsonPath("$[1].status").value("OPEN"));
		mvc.perform(get("/api/accounting/periods").param("year", "1999").with(as("u", READ)))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(get("/api/accounting/periods").with(as("u", READ))).andExpect(problem(400, "validation"));
	}

	@Test
	void aNewYearCanBeOpenedOnce() throws Exception {
		int next = YEAR + 1;
		mvc.perform(post("/api/accounting/periods/year/" + next).with(as("u", PERIOD_MANAGE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(12)));
		mvc.perform(post("/api/accounting/periods/year/" + next).with(as("u", PERIOD_MANAGE)))
				.andExpect(problem(409, "periods-exist"));
		mvc.perform(post("/api/accounting/periods/year/1800").with(as("u", PERIOD_MANAGE)))
				.andExpect(problem(400, "validation"));
		mvc.perform(post("/api/accounting/periods/year/" + (next + 1)).with(as("u", READ)))
				.andExpect(problem(403, "forbidden"));
	}

	@Test
	void closeAndReopenAreAudited() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("closer", PERIOD_MANAGE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("CLOSED"))
				.andExpect(jsonPath("$.closedByName").value("closer-name"));
		mvc.perform(post("/api/accounting/periods/" + id + "/reopen").with(as("closer", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"late invoice\"}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("OPEN"))
				.andExpect(jsonPath("$.closedByName").isEmpty());

		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'PERIOD_REOPEN'").getSingleResult());
		assertThat(details).contains("late invoice");
		Number closes = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'PERIOD_CLOSE'").getSingleResult());
		assertThat(closes.longValue()).isEqualTo(1);
	}

	@Test
	void reopenNeedsAReason() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE)));
		mvc.perform(post("/api/accounting/periods/" + id + "/reopen").with(as("u", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"  \"}"))
				.andExpect(problem(400, "validation"));
	}

	@Test
	void repeatingACloseOrReopenChangesNothing() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/reopen").with(as("u", PERIOD_MANAGE))
						.contentType(MediaType.APPLICATION_JSON).content("{\"reason\": \"x\"}"))
				.andExpect(status().isOk());
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE)));
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("v", PERIOD_MANAGE)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.closedByName").value("u-name"));
		Number audits = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action in ('PERIOD_CLOSE', 'PERIOD_REOPEN')")
				.getSingleResult());
		assertThat(audits.longValue()).isEqualTo(1);
	}

	@Test
	void closeIsRefusedWhileDraftsExist() throws Exception {
		long id = periodId(2);
		inTransaction(() -> insertEntry(id, "DRAFT"));
		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE)))
				.andExpect(problem(409, "period-has-drafts"));
	}

	@Test
	void anotherTenantsPeriodLooksMissing() throws Exception {
		long id = periodId(1);
		mvc.perform(post("/api/accounting/periods/" + id + "/close")
						.with(asTenant(java.util.UUID.randomUUID(), "o", PERIOD_MANAGE)))
				.andExpect(problem(404, "not-found"));
	}

	@Test
	void aDeletedDraftDoesNotBlockClosing() throws Exception {
		long id = periodId(2);
		long entry = inTransaction(() -> insertEntry(id, "DRAFT"));
		inTransaction(() -> sql(
				"update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", entry));

		mvc.perform(post("/api/accounting/periods/" + id + "/close").with(as("u", PERIOD_MANAGE))).andExpect(status().isOk());
	}
}
