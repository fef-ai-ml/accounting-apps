package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class SetupAndAccountsTests extends AccountingTestSupport {

	private void setup() throws Exception {
		mvc.perform(post("/api/accounting/setup").with(admin())).andExpect(status().isOk());
	}

	private long accountId(String code) throws Exception {
		String body = mvc.perform(get("/api/accounting/accounts").with(admin())).andReturn().getResponse().getContentAsString();
		return ((Number) JsonPath.<java.util.List<Object>>read(body, "$[?(@.code=='" + code + "')].id").get(0)).longValue();
	}

	private static String account(String code, String type, Long parentId, boolean postable) {
		return """
				{"code": "%s", "name": "Name %s", "type": "%s", "parentId": %s, "postable": %s}
				""".formatted(code, code, type, parentId, postable);
	}

	private org.springframework.test.web.servlet.ResultActions createAccount(String json) throws Exception {
		return mvc.perform(post("/api/accounting/accounts").with(admin())
				.contentType(MediaType.APPLICATION_JSON).content(json));
	}

	@Test
	void beforeSetupSettingsSayNotInitializedAndAccountsAreEmpty() throws Exception {
		mvc.perform(get("/api/accounting/settings").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.initialized").value(false));
		mvc.perform(get("/api/accounting/accounts").with(as("u", READ)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$", hasSize(0)));
	}

	@Test
	void setupCopiesTheTemplateOpensTheYearAndIsAudited() throws Exception {
		mvc.perform(post("/api/accounting/setup").with(admin()))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.initialized").value(true))
				.andExpect(jsonPath("$.requireSeparatePoster").value(true));

		mvc.perform(get("/api/accounting/accounts").with(as("u", READ)))
				.andExpect(jsonPath("$", hasSize(39)))
				.andExpect(jsonPath("$[0].code").value("1000"))
				.andExpect(jsonPath("$[0].parentId").isEmpty())
				.andExpect(jsonPath("$[?(@.code=='1110')].postable").value(true));
		long parent = accountId("1100");
		mvc.perform(get("/api/accounting/accounts").with(as("u", READ)))
				.andExpect(jsonPath("$[?(@.code=='1110')].parentId").value((int) parent));

		Number periods = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.fiscal_period where year = :y and status = 'OPEN'")
				.setParameter("y", YEAR).getSingleResult());
		assertThat(periods.longValue()).isEqualTo(12);
		Number audited = inTransaction(() -> (Number) entityManager.createNativeQuery(
				"select count(*) from accounting.audit_log where action = 'SETUP' and actor_sub = 'admin-sub'")
				.getSingleResult());
		assertThat(audited.longValue()).isEqualTo(1);
	}

	@Test
	void setupTwiceIsAConflict() throws Exception {
		setup();
		mvc.perform(post("/api/accounting/setup").with(admin())).andExpect(problem(409, "already-initialized"));
	}

	@Test
	void writesBeforeSetupAreRefused() throws Exception {
		createAccount(account("9000", "ASSET", null, true)).andExpect(problem(422, "not-initialized"));
		mvc.perform(put("/api/accounting/settings").with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"requireSeparatePoster\": false}"))
				.andExpect(problem(422, "not-initialized"));
	}

	@Test
	void changingTheSettingIsAudited() throws Exception {
		setup();
		mvc.perform(put("/api/accounting/settings").with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content("{\"requireSeparatePoster\": false}"))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.requireSeparatePoster").value(false))
				.andExpect(jsonPath("$.updatedByName").value("admin-sub-name"));
		String details = inTransaction(() -> (String) entityManager.createNativeQuery(
				"select cast(details as text) from accounting.audit_log where action = 'SETTING_CHANGE'")
				.getSingleResult());
		assertThat(details).contains("\"old\": true").contains("\"new\": false");
	}

	@Test
	void permissionsAreEnforcedWithAStableCode() throws Exception {
		setup();
		mvc.perform(post("/api/accounting/setup").with(as("u", READ))).andExpect(problem(403, "forbidden"));
		mvc.perform(post("/api/accounting/accounts").with(as("u", READ, WRITE)).contentType(MediaType.APPLICATION_JSON)
				.content(account("9000", "ASSET", null, true))).andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/accounts").with(as("u"))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void accountsCanBeAddedEditedAndDeleted() throws Exception {
		setup();
		long parent = accountId("1100");
		String created = createAccount(account("1170", "ASSET", parent, true))
				.andExpect(status().isCreated())
				.andExpect(jsonPath("$.active").value(true))
				.andReturn().getResponse().getContentAsString();
		long id = ((Number) JsonPath.read(created, "$.id")).longValue();

		mvc.perform(put("/api/accounting/accounts/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content("""
						{"code": "1171", "name": "Renamed", "type": "ASSET", "parentId": %d, "postable": true, "active": false}
						""".formatted(parent)))
				.andExpect(status().isOk())
				.andExpect(jsonPath("$.code").value("1171"))
				.andExpect(jsonPath("$.active").value(false));

		mvc.perform(delete("/api/accounting/accounts/" + id).with(admin())).andExpect(status().isNoContent());
		mvc.perform(delete("/api/accounting/accounts/" + id).with(admin())).andExpect(problem(404, "not-found"));
	}

	@Test
	void accountRulesAreEnforced() throws Exception {
		setup();
		long header = accountId("1100");
		long leaf = accountId("1110");

		createAccount(account("1110", "ASSET", header, true)).andExpect(problem(409, "account-code-exists"));
		createAccount(account("1171", "ASSET", leaf, true)).andExpect(problem(400, "validation"));
		createAccount(account("1171", "ASSET", 999999999L, true)).andExpect(problem(400, "validation"));
		createAccount("{\"code\": \"\", \"name\": \"x\", \"type\": \"ASSET\", \"postable\": true}")
				.andExpect(problem(400, "validation"));
		createAccount("{\"code\": \"1\", \"name\": \"x\", \"type\": \"FOO\", \"postable\": true}")
				.andExpect(problem(400, "validation"));

		// A header with children stays a header, and an account cannot become its own ancestor.
		mvc.perform(put("/api/accounting/accounts/" + header).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1100", "ASSET", accountId("1000"), true)))
				.andExpect(problem(400, "validation"));
		long top = accountId("1000");
		mvc.perform(put("/api/accounting/accounts/" + top).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1000", "ASSET", header, false)))
				.andExpect(problem(400, "validation"));

		mvc.perform(delete("/api/accounting/accounts/" + header).with(admin())).andExpect(problem(409, "account-in-use"));
	}

	@Test
	void accountsWithLinesKeepTheirTypeAndCannotBeDeleted() throws Exception {
		setup();
		long cash = accountId("1110");
		inTransaction(() -> {
			long period = ((Number) entityManager.createNativeQuery(
					"select min(id) from accounting.fiscal_period").getSingleResult()).longValue();
			insertLine(insertEntry(period, "DRAFT"), 1, cash, "1.00", "0");
			return 0;
		});

		mvc.perform(put("/api/accounting/accounts/" + cash).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1110", "EXPENSE", accountId("1100"), true)))
				.andExpect(problem(409, "account-in-use"));
		mvc.perform(put("/api/accounting/accounts/" + cash).with(admin()).contentType(MediaType.APPLICATION_JSON)
						.content(account("1110", "ASSET", accountId("1100"), false)))
				.andExpect(problem(409, "account-in-use"));
		mvc.perform(delete("/api/accounting/accounts/" + cash).with(admin())).andExpect(problem(409, "account-in-use"));
	}

	@Test
	void tenantsDoNotSeeEachOthersAccounts() throws Exception {
		setup();
		long cash = accountId("1110");
		UUID other = UUID.randomUUID();
		mvc.perform(get("/api/accounting/accounts").with(asTenant(other, "o", READ)))
				.andExpect(jsonPath("$", hasSize(0)));
		mvc.perform(delete("/api/accounting/accounts/" + cash).with(asTenant(other, "o", ACCOUNT_MANAGE)))
				.andExpect(problem(404, "not-found"));
		mvc.perform(post("/api/accounting/setup").with(asTenant(other, "o", ACCOUNT_MANAGE))).andExpect(status().isOk());
	}

}
