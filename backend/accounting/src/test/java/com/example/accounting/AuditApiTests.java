package com.example.accounting;

import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

class AuditApiTests extends AccountingTestSupport {

	@BeforeEach
	void setup() throws Exception {
		setupAccounting();
	}

	private ResultActions changes() throws Exception {
		return mvc.perform(get("/api/accounting/audit").param("action", "ACCOUNT_CHANGE").with(as("auditor", AUDIT_READ)));
	}

	private static String body(String code, String name, Long parentId, Boolean active) {
		String activeField = active == null ? "" : ", \"active\": " + active;
		return """
				{"code": "%s", "name": "%s", "type": "ASSET", "parentId": %s, "postable": true%s}
				""".formatted(code, name, parentId, activeField);
	}

	private ResultActions putAccount(long id, String json) throws Exception {
		return mvc.perform(put("/api/accounting/accounts/" + id).with(admin()).contentType(MediaType.APPLICATION_JSON).content(json));
	}

	/** Inserts a row with this test tenant's id; call with the JSON text for details. */
	private long insertAudit(String at, String details) {
		return inTransaction(() -> ((Number) entityManager.createNativeQuery("""
				insert into accounting.audit_log (tenant_id, action, entity, entity_id, actor_sub, actor_name, at, details)
				values (cast(:tenant as uuid), 'POST', 'journal_entry', 1, 's', 'Akuntan', cast(:at as timestamptz), cast(:details as jsonb))
				returning id
				""").setParameter("tenant", tenant.toString()).setParameter("at", at).setParameter("details", details)
				.getSingleResult()).longValue());
	}

	@Test
	void aRenameRecordsOnlyWhatChanged() throws Exception {
		long cash = account("1110");
		long parent = account("1100");
		putAccount(cash, body("1110", "Kas Kecil", parent, null)).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].action").value("ACCOUNT_CHANGE"))
				.andExpect(jsonPath("$.items[0].entity").value("account"))
				.andExpect(jsonPath("$.items[0].entityId").value((int) cash))
				.andExpect(jsonPath("$.items[0].actorName").value("admin-sub-name"))
				.andExpect(jsonPath("$.items[0].details.name.from").value("Kas"))
				.andExpect(jsonPath("$.items[0].details.name.to").value("Kas Kecil"))
				.andExpect(jsonPath("$.items[0].details.code").doesNotExist())
				.andExpect(jsonPath("$.items[0].details.parent").doesNotExist())
				.andExpect(jsonPath("$.items[0].details.active").doesNotExist());
	}

	@Test
	void aPutThatChangesNothingRecordsNothing() throws Exception {
		long cash = account("1110");
		putAccount(cash, body("1110", "Kas", account("1100"), null)).andExpect(status().isOk());
		putAccount(cash, body("1110", "Kas", account("1100"), true)).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void movingToTheTopAndBackRecordsTheParentCodes() throws Exception {
		long cash = account("1110");
		long parent = account("1100");
		putAccount(cash, body("1110", "Kas", null, null)).andExpect(status().isOk());
		changes().andExpect(jsonPath("$.items[0].details.parent.from").value("1100"))
				.andExpect(jsonPath("$.items[0].details.parent.to").value(nullValue()));

		putAccount(cash, body("1110", "Kas", parent, null)).andExpect(status().isOk());
		changes().andExpect(jsonPath("$.items[0].details.parent.from").value(nullValue()))
				.andExpect(jsonPath("$.items[0].details.parent.to").value("1100"));
	}

	@Test
	void deactivatingAndChangingTheCodeAreRecorded() throws Exception {
		long cash = account("1110");
		putAccount(cash, body("1119", "Kas", account("1100"), false)).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.items[0].details.code.from").value("1110"))
				.andExpect(jsonPath("$.items[0].details.code.to").value("1119"))
				.andExpect(jsonPath("$.items[0].details.active.from").value(true))
				.andExpect(jsonPath("$.items[0].details.active.to").value(false));
	}

	@Test
	void aTypeChangeRecordsOnlyTheType() throws Exception {
		long cash = account("1110");
		putAccount(cash, """
				{"code": "1110", "name": "Kas", "type": "LIABILITY", "parentId": %d, "postable": true}
				""".formatted(account("1100"))).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].details.type.from").value("ASSET"))
				.andExpect(jsonPath("$.items[0].details.type.to").value("LIABILITY"))
				.andExpect(jsonPath("$.items[0].details.postable").doesNotExist())
				.andExpect(jsonPath("$.items[0].details.name").doesNotExist());
	}

	@Test
	void aPostableChangeRecordsOnlyPostable() throws Exception {
		long cash = account("1110");
		putAccount(cash, """
				{"code": "1110", "name": "Kas", "type": "ASSET", "parentId": %d, "postable": false}
				""".formatted(account("1100"))).andExpect(status().isOk());

		changes().andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].details.postable.from").value(true))
				.andExpect(jsonPath("$.items[0].details.postable.to").value(false))
				.andExpect(jsonPath("$.items[0].details.type").doesNotExist())
				.andExpect(jsonPath("$.items[0].details.name").doesNotExist());
	}

	@Test
	void aRefusedChangeRecordsNothing() throws Exception {
		putAccount(account("1110"), body("1120", "Kas", account("1100"), null)).andExpect(status().is4xxClientError());

		changes().andExpect(jsonPath("$.total").value(0));
	}

	@Test
	void largeAmountsStayPlainDecimals() throws Exception {
		insertAudit("2026-03-15T01:00:00Z", "{\"totalDebit\": 10000000.00, \"entryNo\": \"JE-1\", \"version\": 3}");

		mvc.perform(get("/api/accounting/audit").param("action", "POST").with(as("auditor", AUDIT_READ)))
				.andExpect(jsonPath("$.items[0].details.totalDebit").value("10000000.00"))
				.andExpect(jsonPath("$.items[0].details.entryNo").value("JE-1"))
				.andExpect(jsonPath("$.items[0].details.version").value(3));
	}

	@Test
	void datesAreJakartaDaysAndNewestComesFirst() throws Exception {
		insertAudit("2026-03-14T16:59:59Z", "{\"n\": \"last of the 14th\"}");
		insertAudit("2026-03-14T17:00:00Z", "{\"n\": \"first of the 15th\"}");
		insertAudit("2026-03-15T16:59:59Z", "{\"n\": \"last of the 15th\"}");
		insertAudit("2026-03-15T17:00:00Z", "{\"n\": \"first of the 16th\"}");

		mvc.perform(get("/api/accounting/audit").param("from", "2026-03-15").param("to", "2026-03-15").with(as("auditor", AUDIT_READ)))
				.andExpect(jsonPath("$.total").value(2))
				.andExpect(jsonPath("$.items[0].details.n").value("last of the 15th"))
				.andExpect(jsonPath("$.items[1].details.n").value("first of the 15th"));
	}

	@Test
	void pages() throws Exception {
		for (int i = 0; i < 3; i++) {
			insertAudit("2026-03-1" + i + "T01:00:00Z", "{\"n\": " + i + "}");
		}
		mvc.perform(get("/api/accounting/audit").param("action", "POST").param("page", "1").param("size", "2").with(as("auditor", AUDIT_READ)))
				.andExpect(jsonPath("$.total").value(3))
				.andExpect(jsonPath("$.page").value(1))
				.andExpect(jsonPath("$.size").value(2))
				.andExpect(jsonPath("$.items", hasSize(1)))
				.andExpect(jsonPath("$.items[0].details.n").value(0));
	}

	@Test
	void badParametersAreValidationProblems() throws Exception {
		String[][] bad = { { "size", "0" }, { "size", "201" }, { "page", "-1" }, { "action", "NOPE" }, { "from", "not-a-date" } };
		for (String[] parameter : bad) {
			mvc.perform(get("/api/accounting/audit").param(parameter[0], parameter[1]).with(as("auditor", AUDIT_READ)))
					.andExpect(problem(400, "validation"));
		}
		mvc.perform(get("/api/accounting/audit").param("from", "2026-03-16").param("to", "2026-03-15").with(as("auditor", AUDIT_READ)))
				.andExpect(problem(400, "validation"));
	}

	@Test
	void readingNeedsTheAuditPermission() throws Exception {
		mvc.perform(get("/api/accounting/audit").with(as("bookkeeper", READ, WRITE))).andExpect(problem(403, "forbidden"));
		mvc.perform(get("/api/accounting/audit").with(as("viewer", READ))).andExpect(problem(403, "forbidden"));
	}

	@Test
	void anotherTenantSeesNothingEvenWithoutHibernatesFilter() throws Exception {
		long row = insertAudit("2026-03-15T01:00:00Z", "{}");
		UUID other = UUID.randomUUID();

		mvc.perform(get("/api/accounting/audit").with(asTenant(other, "auditor", AUDIT_READ))).andExpect(jsonPath("$.total").value(0));
		long seen = inTenantTransaction(other, "auditor", () -> ((Number) entityManager
				.createNativeQuery("select count(*) from accounting.audit_log where id = :id").setParameter("id", row)
				.getSingleResult()).longValue());
		assertThat(seen).isZero();
	}

}
