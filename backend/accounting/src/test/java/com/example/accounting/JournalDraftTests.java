package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.jayway.jsonpath.JsonPath;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.startsWith;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class JournalDraftTests extends AccountingTestSupport {

	private long cash;

	private long sales;

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
		cash = account("1110");
		sales = account("4100");
	}

	private ResultActions create(String json) throws Exception {
		return mvc.perform(post("/api/accounting/journal-entries").with(as("maker", READ, WRITE))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private ResultActions update(long id, long version, String date, String... lines) throws Exception {
		String json = entry(date, lines).replaceFirst("\\{", "{\"version\": " + version + ", ");
		return mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("editor", READ, WRITE))
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	private long versionOf(long id) throws Exception {
		String body = mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.read(body, "$.version")).longValue();
	}

	@Test
	void aDraftIsCreatedWithStringAmountsAndNoNumber() throws Exception {
		create(entry(DATE, line(cash, "1665000", "0"), line(sales, "0", "1665000.00")))
				.andExpect(status().isCreated())
				.andExpect(header().string("Location", startsWith("/api/accounting/journal-entries/")))
				.andExpect(jsonPath("$.status").value("DRAFT"))
				.andExpect(jsonPath("$.entryNo").isEmpty())
				.andExpect(jsonPath("$.date").value(DATE))
				.andExpect(jsonPath("$.createdByName").value("maker-name"))
				.andExpect(jsonPath("$.lines", hasSize(2)))
				.andExpect(jsonPath("$.lines[0].lineNo").value(1))
				.andExpect(jsonPath("$.lines[0].debit").value("1665000.00"))
				.andExpect(jsonPath("$.lines[0].credit").value("0.00"))
				.andExpect(jsonPath("$.totalDebit").value("1665000.00"));
	}

	@Test
	void aDraftMayBeUnbalancedOrEmpty() throws Exception {
		create(entry(DATE, line(cash, "1.00", "0"))).andExpect(status().isCreated());
		create(entry(DATE)).andExpect(status().isCreated());
	}

	@Test
	void linesAreChecked() throws Exception {
		create(entry(DATE, line(cash, "0", "0"))).andExpect(problem(400, "invalid-line"));
		create(entry(DATE, line(cash, "1", "1"))).andExpect(problem(400, "invalid-line"));
		create(entry(DATE, line(cash, "-1", "0"))).andExpect(problem(400, "invalid-line"));
		String[] tooMany = new String[501];
		java.util.Arrays.fill(tooMany, line(cash, "1", "0"));
		create(entry(DATE, tooMany)).andExpect(problem(400, "too-many-lines"));
		create("{\"date\": \"" + DATE + "\", \"description\": \"x\", \"lines\": [null]}")
				.andExpect(problem(400, "validation"));
	}

	@Test
	void oddAmountsAreRefusedNotRounded() throws Exception {
		create(entry(DATE, line(cash, "1.555", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "abc", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "1E+1000000000", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "123456789012345678", "0"))).andExpect(problem(400, "invalid-amount"));
		create(entry(DATE, line(cash, "1.500", "0")))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.lines[0].debit").value("1.50"));
	}

	@Test
	void linesNeedAUsableAccountOfThisTenant() throws Exception {
		long header = account("1100");
		create(entry(DATE, line(header, "1", "0"))).andExpect(problem(422, "account-not-postable"));

		mvc.perform(put("/api/accounting/accounts/" + sales).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
				{"code": "4100", "name": "Penjualan", "type": "REVENUE", "parentId": %d, "postable": true, "active": false}
				""".formatted(account("4000")))).andExpect(status().isOk());
		create(entry(DATE, line(sales, "1", "0"))).andExpect(problem(422, "account-not-postable"));

		create(entry(DATE, line(999999999L, "1", "0"))).andExpect(problem(422, "account-not-postable"));
		UUID other = UUID.randomUUID();
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
		String body = mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ))).andReturn().getResponse().getContentAsString();
		java.util.List<Number> otherCash = JsonPath.read(body, "$[?(@.code=='1110')].id");
		create(entry(DATE, line(otherCash.get(0).longValue(), "1", "0"))).andExpect(problem(422, "account-not-postable"));
	}

	@Test
	void theDateMustFallIntoAPeriod() throws Exception {
		create(entry("1999-01-01", line(cash, "1", "0"))).andExpect(problem(422, "no-period"));
		create("{\"description\": \"no date\", \"lines\": []}").andExpect(problem(400, "validation"));
		create("{\"date\": \"" + DATE + "\", \"description\": \" \", \"lines\": []}").andExpect(problem(400, "validation"));
	}

	@Test
	void writesBeforeSetupAreRefused() throws Exception {
		UUID fresh = UUID.randomUUID();
		mvc.perform(post("/api/accounting/journal-entries").with(asTenant(fresh, "u", WRITE))
						.contentType(MediaType.APPLICATION_JSON).content(entry(DATE)))
				.andExpect(problem(422, "not-initialized"));
	}

	@Test
	void anEditReplacesTheLinesAndRecordsTheEditor() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"), line(sales, "0", "1"));
		update(id, versionOf(id), YEAR + "-04-02", line(sales, "0", "7"), line(cash, "7", "0"), line(cash, "2", "0"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.date").value(YEAR + "-04-02"))
				.andExpect(jsonPath("$.createdByName").value("maker-name"))
				.andExpect(jsonPath("$.lastModifiedByName").value("editor-name"))
				.andExpect(jsonPath("$.lines", hasSize(3)))
				.andExpect(jsonPath("$.lines[0].accountId").value((int) sales))
				.andExpect(jsonPath("$.lines[2].lineNo").value(3));
	}

	@Test
	void aStaleVersionIsAConflict() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"));
		long version = versionOf(id);
		update(id, version, DATE, line(cash, "2", "0")).andExpect(status().isOk());
		update(id, version, DATE, line(cash, "3", "0")).andExpect(problem(409, "version-conflict"));
		mvc.perform(put("/api/accounting/journal-entries/" + id).with(as("editor", WRITE))
				.contentType(MediaType.APPLICATION_JSON).content(entry(DATE))).andExpect(problem(400, "validation"));
	}

	@Test
	void aDraftCanBeDeleted() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"));
		mvc.perform(delete("/api/accounting/journal-entries/" + id).with(as("u", WRITE))).andExpect(status().isNoContent());
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(as("u", READ))).andExpect(problem(404, "not-found"));
	}

	@Test
	void writesNeedTheWritePermission() throws Exception {
		mvc.perform(post("/api/accounting/journal-entries").with(as("u", READ))
				.contentType(MediaType.APPLICATION_JSON).content(entry(DATE))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void anotherTenantsEntriesLookMissing() throws Exception {
		long id = draft("maker", DATE, line(cash, "1", "0"));
		UUID other = UUID.randomUUID();
		mvc.perform(get("/api/accounting/journal-entries/" + id).with(asTenant(other, "o", READ)))
				.andExpect(problem(404, "not-found"));
		mvc.perform(delete("/api/accounting/journal-entries/" + id).with(asTenant(other, "o", WRITE)))
				.andExpect(problem(404, "not-found"));
		mvc.perform(get("/api/accounting/journal-entries").with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void theListFiltersAndPages() throws Exception {
		draft("maker", YEAR + "-01-10", line(cash, "1", "0"));
		long second = draft("maker", YEAR + "-02-10", line(sales, "0", "1"));
		long third = draft("maker", YEAR + "-03-10", line(cash, "1", "0"), line(sales, "0", "1"));

		mvc.perform(get("/api/accounting/journal-entries").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.size").value(50))
				.andExpect(jsonPath("$.items[0].id").value((int) third))
				.andExpect(jsonPath("$.items[0].status").value("DRAFT"));
		mvc.perform(get("/api/accounting/journal-entries").param("from", YEAR + "-02-01").param("to", YEAR + "-02-28").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(1))
				.andExpect(jsonPath("$.items[0].id").value((int) second));
		mvc.perform(get("/api/accounting/journal-entries").param("accountId", String.valueOf(cash)).with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(2));
		mvc.perform(get("/api/accounting/journal-entries").param("status", "POSTED").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/accounting/journal-entries").param("size", "1").param("page", "1").with(as("u", READ)))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.total").value(3));
		mvc.perform(get("/api/accounting/journal-entries").param("q", "test ENTRY").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(3));
		mvc.perform(get("/api/accounting/journal-entries").param("q", "%").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
		mvc.perform(get("/api/accounting/journal-entries").param("q", "t_st").with(as("u", READ)))
				.andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void pagingValuesOutsideTheLimitsAreRefused() throws Exception {
		mvc.perform(get("/api/accounting/journal-entries").param("size", "0").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("size", "201").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("page", "-1").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("status", "NOPE").with(as("u", READ))).andExpect(problem(400, "validation"));
		mvc.perform(get("/api/accounting/journal-entries").param("from", "nope").with(as("u", READ))).andExpect(problem(400, "validation"));
	}

}
