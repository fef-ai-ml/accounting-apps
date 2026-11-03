package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class PostingTests extends AccountingTestSupport {

	private long cash;

	private long sales;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
	}

	private ResultActions reverse(long id, String json) throws Exception {
		return mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("checker", READ, POST))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long periodId(int month) throws Exception {
		String body = mvc.perform(get("/api/accounting/periods").param("year", String.valueOf(YEAR)).with(admin())).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$[" + (month - 1) + "].id")).longValue();
	}

	private void closePeriod(int month) throws Exception {
		mvc.perform(post("/api/accounting/periods/" + periodId(month) + "/close").with(as("u", PERIOD_MANAGE)))
				.andExpect(status().isOk());
	}

	private void setSeparatePoster(boolean required) throws Exception {
		mvc.perform(put("/api/accounting/settings").with(admin()).contentType(MediaType.APPLICATION_JSON)
				.content("{\"requireSeparatePoster\": " + required + "}")).andExpect(status().isOk());
	}

	@Test
	void postingNumbersTheEntryAndAuditsIt() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.status").value("POSTED"))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"))
				.andExpect(jsonPath("$.postedByName").value("checker-name"));
		long second = balancedDraft("maker", DATE);
		postEntry(second, "checker").andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000002"));

		Number audits = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'POST' and actor_sub = 'checker'")
				.getSingleResult());
		assertThat(audits.longValue()).isEqualTo(2);
	}

	@Test
	void eachTenantAndYearHasItsOwnNumbers() throws Exception {
		mvc.perform(post("/api/accounting/periods/year/" + (YEAR + 1)).with(as("u", PERIOD_MANAGE))).andExpect(status().isOk());
		postEntry(balancedDraft("maker", DATE), "checker").andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"));
		postEntry(balancedDraft("maker", (YEAR + 1) + "-01-05"), "checker")
				.andExpect(jsonPath("$.entryNo").value("JE-" + (YEAR + 1) + "-000001"));

		UUID other = UUID.randomUUID();
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
		String body = mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ))).andReturn().getResponse().getContentAsString();
		long otherCash = ((java.util.List<Number>) JsonPath.read(body, "$[?(@.code=='1110')].id")).get(0).longValue();
		long otherSales = ((java.util.List<Number>) JsonPath.read(body, "$[?(@.code=='4100')].id")).get(0).longValue();
		String created = mvc.perform(post("/api/accounting/journal-entries").with(asTenant(other, "o", WRITE))
						.contentType(MediaType.APPLICATION_JSON)
						.content(entry(DATE, line(otherCash, "5", "0"), line(otherSales, "0", "5"))))
				.andReturn().getResponse().getContentAsString();
		long otherEntry = ((Number) JsonPath.read(created, "$.id")).longValue();
		mvc.perform(post("/api/accounting/journal-entries/" + otherEntry + "/post").param("version", "0").with(asTenant(other, "p", POST)))
				.andExpect(jsonPath("$.entryNo").value("JE-" + YEAR + "-000001"));

		mvc.perform(post("/api/accounting/journal-entries/" + otherEntry + "/post").param("version", "0").with(as("checker", POST)))
				.andExpect(problem(404, "not-found"));
	}

	@Test
	void anUnbalancedOrShortEntryIsNotPosted() throws Exception {
		postEntry(draft("maker", DATE, line(cash, "1", "0")), "checker").andExpect(problem(422, "too-few-lines"));
		postEntry(draft("maker", DATE, line(cash, "2", "0"), line(sales, "0", "1")), "checker")
				.andExpect(problem(422, "unbalanced"));
		postEntry(draft("maker", DATE), "checker").andExpect(problem(422, "too-few-lines"));
	}

	@Test
	void postingNeedsThePostPermission() throws Exception {
		long id = balancedDraft("maker", DATE);
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").param("version", "0").with(as("u", READ, WRITE)))
				.andExpect(problem(403, "forbidden"));
	}

	@Test
	void makerCannotPostUntilTheSettingIsOff() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "maker").andExpect(problem(403, "separate-poster-required"));

		long edited = balancedDraft("maker", DATE);
		String body = mvc.perform(get("/api/accounting/journal-entries/" + edited).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		long version = ((Number) JsonPath.read(body, "$.version")).longValue();
		mvc.perform(put("/api/accounting/journal-entries/" + edited).with(as("editor", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, line(cash, "3", "0"), line(sales, "0", "3")).replaceFirst("\\{", "{\"version\": " + version + ", ")))
				.andExpect(status().isOk());
		postEntry(edited, "editor").andExpect(problem(403, "separate-poster-required"));
		postEntry(edited, "maker").andExpect(problem(403, "separate-poster-required"));

		setSeparatePoster(false);
		postEntry(id, "maker").andExpect(status().isOk());
	}

	@Test
	void aPostedEntryCannotBeChanged() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker").andExpect(status().isOk());

		postEntry(id, "checker").andExpect(problem(409, "not-draft"));
		mvc.perform(delete("/api/accounting/journal-entries/" + id).param("version", "0").with(as("maker", WRITE)))
				.andExpect(problem(409, "not-draft"));
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("maker", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE).replaceFirst("\\{", "{\"version\": 0, "))).andExpect(problem(409, "not-draft"));
	}

	@Test
	void nothingIsPostedIntoAClosedPeriod() throws Exception {
		closePeriod(2);
		long id = balancedDraft("maker", YEAR + "-02-10");
		postEntry(id, "checker").andExpect(problem(422, "period-closed"));
	}

	@Test
	void movingADraftIntoAClosedPeriodBlocksPosting() throws Exception {
		closePeriod(2);
		long id = balancedDraft("maker", DATE);
		String body = mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		long version = ((Number) JsonPath.read(body, "$.version")).longValue();
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("maker", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(YEAR + "-02-10", line(cash, "1", "0"), line(sales, "0", "1")).replaceFirst("\\{", "{\"version\": " + version + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(problem(422, "period-closed"));

		String moved = mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		version = ((Number) JsonPath.read(moved, "$.version")).longValue();
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("maker", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, line(cash, "1", "0"), line(sales, "0", "1")).replaceFirst("\\{", "{\"version\": " + version + ", ")))
				.andExpect(status().isOk());
		postEntry(id, "checker").andExpect(status().isOk());
	}

	@Test
	void aDeactivatedAccountBlocksPosting() throws Exception {
		long id = balancedDraft("maker", DATE);
		deactivate(sales, "4100", "Penjualan", "REVENUE", account("4000"));
		postEntry(id, "checker").andExpect(problem(422, "account-not-postable"))
				.andExpect(jsonPath("$.detail").value("Account 4100 cannot receive journal lines"));
	}

	private void deactivate(long accountId, String code, String name, String type, long parent) throws Exception {
		mvc.perform(put("/api/accounting/accounts/" + accountId).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
				{"code": "%s", "name": "%s", "type": "%s", "parentId": %d, "postable": true, "active": false}
				""".formatted(code, name, type, parent))).andExpect(status().isOk());
	}

	@Test
	void reversalSwapsSidesAndMarksTheOriginal() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");

		String body = reverse(id, "{\"date\": \"" + YEAR + "-04-01\"}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reversal.status").value("POSTED"))
				.andExpect(jsonPath("$.reversal.entryNo").value("JE-" + YEAR + "-000002"))
				.andExpect(jsonPath("$.reversal.date").value(YEAR + "-04-01"))
				.andExpect(jsonPath("$.reversal.description").value("Reversal of JE-" + YEAR + "-000001"))
				.andExpect(jsonPath("$.reversal.reversalOf").value((int) id))
				.andExpect(jsonPath("$.reversal.lines[0].debit").value("0.00"))
				.andExpect(jsonPath("$.reversal.lines[0].credit").value("100.00"))
				.andExpect(jsonPath("$.reversal.lines[1].debit").value("100.00"))
				.andExpect(jsonPath("$.reversal.lines[1].credit").value("0.00"))
				.andExpect(jsonPath("$.draftId").isEmpty())
				.andReturn().getResponse().getContentAsString();
		long reversalId = ((Number) JsonPath.read(body, "$.reversal.id")).longValue();

		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ)))
				.andExpect(jsonPath("$.status").value("REVERSED"))
				.andExpect(jsonPath("$.reversedBy").value((int) reversalId));

		Number audits = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'REVERSE'").getSingleResult());
		assertThat(audits.longValue()).isEqualTo(1);
	}

	@Test
	void theMakerMayReverseAndTheDateDefaultsToToday() throws Exception {
		// Dated at the start of the year so that "today" is never before it.
		long id = balancedDraft("checker", YEAR + "-01-01");
		postEntry(id, "other");
		reverse(id, "{}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.reversal.date").value(java.time.LocalDate.now(java.time.ZoneId.of("Asia/Jakarta")).toString()));
	}

	@Test
	void reverseAndCopyCreatesACorrectableDraft() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");

		String body = reverse(id, "{\"date\": \"" + DATE + "\", \"copyToDraft\": true}")
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.draftId").isNumber())
				.andReturn().getResponse().getContentAsString();
		long draftId = ((Number) JsonPath.read(body, "$.draftId")).longValue();
		mvc.perform(get("/api/accounting/journal-entries/" + draftId).with(as("u", READ)))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.lines[0].debit").value("100.00"))
				.andExpect(jsonPath("$.createdByName").value("checker-name"));
		postEntry(draftId, "checker").andExpect(problem(403, "separate-poster-required"));
	}

	@Test
	void reversalRulesAreEnforced() throws Exception {
		long draftId = balancedDraft("maker", DATE);
		reverse(draftId, "{}").andExpect(problem(409, "not-draft"));

		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");
		reverse(id, "{\"date\": \"" + (YEAR + 5) + "-01-01\"}").andExpect(problem(422, "no-period"));
		closePeriod(5);
		reverse(id, "{\"date\": \"" + YEAR + "-05-01\"}").andExpect(problem(422, "period-closed"));
		reverse(id, "{\"date\": \"" + DATE + "\"}").andExpect(status().isOk());
		reverse(id, "{\"date\": \"" + DATE + "\"}").andExpect(problem(409, "already-reversed"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/reverse").with(as("u", READ))
				.contentType(MediaType.APPLICATION_JSON).content("{}")).andExpect(problem(403, "forbidden"));
	}

	@Test
	void aReversalCannotPrecedeTheEntryItReverses() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");
		reverse(id, "{\"date\": \"" + YEAR + "-02-01\"}").andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ)))
				.andExpect(jsonPath("$.status").value("POSTED"));
		reverse(id, "{\"date\": \"" + DATE + "\"}").andExpect(status().isOk());
	}

	@Test
	void postingNeedsTheVersionTheCheckerReviewed() throws Exception {
		long id = balancedDraft("maker", DATE);
		long reviewed = versionOf(id);
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("maker", WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, line(cash, "9999999", "0"), line(sales, "0", "9999999"))
						.replaceFirst("\\{", "{\"version\": " + reviewed + ", ")))
				.andExpect(status().isOk());

		postEntry(id, reviewed, "checker").andExpect(problem(409, "version-conflict"));
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ)))
				.andExpect(jsonPath("$.status").value("DRAFT"));
		mvc.perform(post("/api/accounting/journal-entries/" + id + "/post").with(as("checker", READ, POST)))
				.andExpect(problem(400, "validation"));

		postEntry(id, versionOf(id), "checker").andExpect(status().isOk());
		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'POST' and entity_id = :id")
				.setParameter("id", id).getSingleResult());
		assertThat(details).contains("9999999").contains("\"version\"");
	}

	@Test
	void reversingWithADeactivatedAccountIsAClearRefusal() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker");
		deactivate(sales, "4100", "Penjualan", "REVENUE", account("4000"));
		reverse(id, "{\"date\": \"" + DATE + "\"}").andExpect(problem(422, "account-not-postable"));
	}

	@Test
	void anEntryTellsTheUserWhetherMakerCheckerBlocksThem() throws Exception {
		long id = balancedDraft("maker", DATE);
		String path = "/api/accounting/journal-entries/" + id;

		mvc.perform(get(path).with(as("maker", READ))).andExpect(jsonPath("$.posterBlocked").value(true));
		mvc.perform(get(path).with(as("checker", READ))).andExpect(jsonPath("$.posterBlocked").value(false));

		setSeparatePoster(false);
		mvc.perform(get(path).with(as("maker", READ))).andExpect(jsonPath("$.posterBlocked").value(false));

		setSeparatePoster(true);
		mvc.perform(post(path + "/post").param("version", String.valueOf(versionOf(id))).with(as("checker", READ, POST)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.posterBlocked").value(false));
		mvc.perform(get(path).with(as("maker", READ))).andExpect(jsonPath("$.posterBlocked").value(false));
	}

	private void editAs(String sub, long id) throws Exception {
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as(sub, READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(entry(DATE, line(cash, "3", "0"), line(sales, "0", "3")).replaceFirst("\\{", "{\"version\": " + versionOf(id) + ", ")))
				.andExpect(status().isOk());
	}

	@Test
	void anyoneWhoEverEditedADraftCannotPostIt() throws Exception {
		long id = balancedDraft("maker", DATE);
		editAs("early-editor", id);
		editAs("late-editor", id);

		postEntry(id, "maker").andExpect(problem(403, "separate-poster-required"))
				.andExpect(jsonPath("$.detail").value("You prepared or edited this entry; another user must post it"));
		postEntry(id, "early-editor").andExpect(problem(403, "separate-poster-required"));
		postEntry(id, "late-editor").andExpect(problem(403, "separate-poster-required"));
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("early-editor", READ)))
				.andExpect(jsonPath("$.posterBlocked").value(true));
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("checker", READ)))
				.andExpect(jsonPath("$.posterBlocked").value(false));

		postEntry(id, "checker").andExpect(status().isOk());
	}

	@Test
	void anEarlierEditorMayPostWhenTheSettingIsOff() throws Exception {
		long id = balancedDraft("maker", DATE);
		editAs("early-editor", id);
		editAs("late-editor", id);
		setSeparatePoster(false);

		postEntry(id, "early-editor").andExpect(status().isOk());
	}

	@Test
	void editingTwiceRecordsAnEditorOnce() throws Exception {
		long id = balancedDraft("maker", DATE);
		editAs("editor", id);
		editAs("editor", id);

		String subs = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(prepared_by_subs as text) from accounting.journal_entry where id = :id")
				.setParameter("id", id).getSingleResult());
		assertThat(subs).isEqualTo("{maker,editor}");
	}

	@Test
	void aReversalCanItselfBeReversed() throws Exception {
		long id = balancedDraft("maker", DATE);
		postEntry(id, "checker").andExpect(status().isOk());
		String reversed = reverse(id, "{\"date\": \"" + DATE + "\"}")
				.andExpect(status().isOk()).andReturn().getResponse().getContentAsString();
		long reversal = ((Number) JsonPath.read(reversed, "$.reversal.id")).longValue();

		reverse(reversal, "{\"date\": \"" + DATE + "\"}").andExpect(status().isOk());
	}
}
