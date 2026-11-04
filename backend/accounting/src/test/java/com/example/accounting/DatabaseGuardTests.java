package com.example.accounting;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

/** Bypasses the services with native SQL as app_runtime: these invariants must hold even if a service is wrong. */
class DatabaseGuardTests extends AccountingTestSupport {

	/** Creates a balanced posted entry and returns {entryId, firstLineId}. */
	private long[] postedEntry() {
		return inTransaction(() -> {
			long cash = insertAccount("A", true, true);
			long sales = insertAccount("B", true, true);
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, cash, "100.00", "0");
			insertLine(entry, 2, sales, "0", "100.00");
			sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id", entry);
			long line = ((Number) entityManager
					.createNativeQuery("select min(id) from accounting.journal_line where entry_id = :id")
					.setParameter("id", entry).getSingleResult()).longValue();
			return new long[] { entry, line };
		});
	}

	/** A balanced draft in the given month, on accounts of its own. */
	private long balancedDraft(int month) {
		long entry = insertEntry(insertPeriod(YEAR, month), "DRAFT");
		insertLine(entry, 1, insertAccount("D" + month, true, true), "100.00", "0");
		insertLine(entry, 2, insertAccount("C" + month, true, true), "0", "100.00");
		return entry;
	}

	/** Reverses the entry in the order PostingService does: the reversal is posted, then the original is marked. */
	private long reverse(long original) {
		return inTransaction(() -> {
			long reversal = balancedDraft(2);
			sql("update accounting.journal_entry set reversal_of = " + original + " where id = :id", reversal);
			sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-2' where id = :id", reversal);
			sql("update accounting.journal_entry set status = 'REVERSED', reversed_by = " + reversal
					+ ", version = version + 1 where id = :id", original);
			return reversal;
		});
	}

	@Test
	void postedUnbalancedEntryIsRejectedAtCommit() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long cash = insertAccount("A", true, true);
			long sales = insertAccount("B", true, true);
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, cash, "100.00", "0");
			insertLine(entry, 2, sales, "0", "50.00");
			return sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id", entry);
		})).hasStackTraceContaining("is not balanced");
	}

	@Test
	void postedEntryWithFewerThanTwoLinesIsRejectedAtCommit() {
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id",
				insertEntry(insertPeriod(YEAR, 1), "DRAFT")))).hasStackTraceContaining("is not balanced");
	}

	@Test
	void balancedPostedEntryIsAccepted() {
		assertThatCode(this::postedEntry).doesNotThrowAnyException();
	}

	@Test
	void postedLinesCannotBeChangedOrDeleted() {
		long[] posted = postedEntry();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_line set memo = 'x' where id = :id", posted[1])))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(
				() -> sql("delete from accounting.journal_line where id = :id", posted[1])))
				.hasStackTraceContaining("permission denied");
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(posted[0], 3, insertAccount("C", true, true), "1.00", "0");
			return 0;
		})).hasStackTraceContaining("cannot be changed");
	}

	@Test
	void postedEntryCannotBeChangedOrDeleted() {
		long[] posted = postedEntry();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_entry set description = 'x' where id = :id", posted[0])))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(
				() -> sql("delete from accounting.journal_entry where id = :id", posted[0])))
				.hasStackTraceContaining("permission denied");
	}

	@Test
	void onlyPostedToReversedWithAReversingEntryIsAllowed() {
		long[] posted = postedEntry();
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_entry set status = 'REVERSED' where id = :id", posted[0])))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'REVERSED', reversed_by = id, description = 'x' where id = :id",
				posted[0]))).hasStackTraceContaining("cannot be changed");
		reverse(posted[0]);
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_entry set status = 'POSTED' where id = :id", posted[0])))
				.hasStackTraceContaining("cannot be changed");
	}

	@Test
	void anEntryIsOnlyMarkedReversedByThePostedEntryThatReversesIt() {
		String refused = "the posted entry that reverses it";
		long[] posted = postedEntry();
		long unrelated = inTransaction(() -> {
			long entry = balancedDraft(3);
			sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-3' where id = :id", entry);
			return entry;
		});

		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'REVERSED', reversed_by = id, version = version + 1 where id = :id",
				posted[0]))).as("itself").hasStackTraceContaining(refused);
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'REVERSED', reversed_by = " + unrelated
						+ ", version = version + 1 where id = :id", posted[0])))
				.as("an unrelated entry").hasStackTraceContaining(refused);
		assertThatThrownBy(() -> inTransaction(() -> {
			long draft = balancedDraft(4);
			sql("update accounting.journal_entry set reversal_of = " + posted[0] + " where id = :id", draft);
			return sql("update accounting.journal_entry set status = 'REVERSED', reversed_by = " + draft
					+ ", version = version + 1 where id = :id", posted[0]);
		})).as("a reversal still in draft").hasStackTraceContaining(refused);
		assertThatThrownBy(() -> inTransaction(() -> {
			long entry = balancedDraft(4);
			sql("update accounting.journal_entry set reversal_of = id where id = :id", entry);
			sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-4' where id = :id", entry);
			return sql("update accounting.journal_entry set status = 'REVERSED', reversed_by = id, version = version + 1 where id = :id",
					entry);
		})).as("an entry that says it reverses itself").hasStackTraceContaining(refused);
	}

	@Test
	void aPostedReversalMustBeNamedByTheEntryItReverses() {
		long[] posted = postedEntry();

		assertThatThrownBy(() -> inTransaction(() -> {
			long reversal = balancedDraft(2);
			sql("update accounting.journal_entry set reversal_of = " + posted[0] + " where id = :id", reversal);
			return sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-2' where id = :id", reversal);
		})).hasStackTraceContaining("does not name it");

		reverse(posted[0]);
	}

	@Test
	void draftsStayEditable() {
		assertThatCode(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, insertAccount("A", true, true), "1.00", "0");
			sql("update accounting.journal_line set memo = 'x' where entry_id = :id", entry);
			sql("update accounting.journal_line set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where entry_id = :id", entry);
			return sql("update accounting.journal_entry set deleted_at = now(), deleted_by_sub = 's', deleted_by_name = 'n' where id = :id", entry);
		})).doesNotThrowAnyException();
	}

	@Test
	void linesNeedAPostableActiveAccount() {
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(insertEntry(insertPeriod(YEAR, 1), "DRAFT"), 1, insertAccount("H", false, true), "1.00", "0");
			return 0;
		})).hasStackTraceContaining("is not postable and active");
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(insertEntry(insertPeriod(YEAR, 2), "DRAFT"), 1, insertAccount("I", true, false), "1.00", "0");
			return 0;
		})).hasStackTraceContaining("is not postable and active");
	}

	@Test
	void aLineCannotUseAnotherTenantsAccount() {
		UUID other = UUID.randomUUID();
		long foreignAccount = inTenantTransaction(other, "other", () -> insertAccount("X", true, true));
		assertThatThrownBy(() -> inTransaction(() -> {
			insertLine(insertEntry(insertPeriod(YEAR, 1), "DRAFT"), 1, foreignAccount, "1.00", "0");
			return 0;
		})).hasStackTraceContaining("foreign key");
	}

	@Test
	void anotherTenantCannotSeeOrTouchAPostedEntry() {
		long[] posted = postedEntry();
		UUID other = UUID.randomUUID();
		int changed = inTenantTransaction(other, "other",
				() -> sql("update accounting.journal_entry set description = 'x' where id = :id", posted[0]));
		assertThat(changed).isZero();
	}

	private static final List<String> TENANT_TABLES = List.of("settings", "account", "fiscal_period", "entry_sequence",
			"journal_entry", "journal_line", "audit_log");

	private long count(String table) {
		return ((Number) entityManager.createNativeQuery("select count(*) from accounting." + table).getSingleResult())
				.longValue();
	}

	@Test
	void rowLevelSecurityIsolatesEveryTenantTable() {
		inTransaction(() -> {
			String t = tenant.toString();
			entityManager.createNativeQuery("insert into accounting.settings (tenant_id) values (cast(:t as uuid))")
					.setParameter("t", t).executeUpdate();
			entityManager.createNativeQuery(
					"insert into accounting.entry_sequence (tenant_id, year, next_value) values (cast(:t as uuid), :y, 1)")
					.setParameter("t", t).setParameter("y", YEAR).executeUpdate();
			entityManager.createNativeQuery("""
					insert into accounting.audit_log (tenant_id, action, entity, actor_sub, actor_name, at)
					values (cast(:t as uuid), 'SETUP', 'x', 's', 'n', now())
					""").setParameter("t", t).executeUpdate();
			long cash = insertAccount("A", true, true);
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, cash, "1.00", "0");
			return 0;
		});
		inTransaction(() -> {
			// Positive counts keep the zero counts below from being vacuous.
			TENANT_TABLES.forEach(table -> assertThat(count(table)).as(table).isPositive());
			return 0;
		});
		UUID other = UUID.randomUUID();
		inTenantTransaction(other, "other", () -> {
			TENANT_TABLES.forEach(table -> assertThat(count(table)).as(table).isZero());
			return 0;
		});
		// No tenant at all (app.tenant_id empty) sees nothing and may insert nothing.
		inTenantTransaction(null, "nobody", () -> {
			TENANT_TABLES.forEach(table -> assertThat(count(table)).as(table + " without a tenant").isZero());
			return 0;
		});
		assertThatThrownBy(() -> inTenantTransaction(null, "nobody", () -> entityManager
				.createNativeQuery("insert into accounting.settings (tenant_id) values (cast(:t as uuid))")
				.setParameter("t", tenant.toString()).executeUpdate())).hasStackTraceContaining("row-level security");
		// WITH CHECK on every table, naming this test's tenant while running as another one; parent ids are dummies.
		List<String> inserts = List.of(
				"insert into accounting.settings (tenant_id) values (cast(:t as uuid))",
				"insert into accounting.account (tenant_id, code, name, type, postable, active) values (cast(:t as uuid), 'Z', 'Z', 'ASSET', true, true)",
				"insert into accounting.fiscal_period (tenant_id, year, month, start_date, end_date, status) values (cast(:t as uuid), 2000, 1, date '2000-01-01', date '2000-01-31', 'OPEN')",
				"insert into accounting.entry_sequence (tenant_id, year, next_value) values (cast(:t as uuid), 2000, 1)",
				"insert into accounting.journal_entry (tenant_id, entry_date, period_id, description, status, created_by_sub, created_by_name, created_at, last_modified_by_sub, last_modified_by_name, last_modified_at, version) values (cast(:t as uuid), date '2000-01-01', 1, 'x', 'DRAFT', 's', 'n', now(), 's', 'n', now(), 0)",
				"insert into accounting.journal_line (tenant_id, entry_id, line_no, account_id, debit, credit) values (cast(:t as uuid), 1, 1, 1, 1, 0)",
				"insert into accounting.audit_log (tenant_id, action, entity, actor_sub, actor_name, at) values (cast(:t as uuid), 'SETUP', 'x', 's', 'n', now())");
		for (String insert : inserts) {
			assertThatThrownBy(() -> inTenantTransaction(other, "other",
					() -> entityManager.createNativeQuery(insert).setParameter("t", tenant.toString()).executeUpdate()))
					.as(insert).hasStackTraceContaining("row-level security");
		}
	}

	@Test
	void aDraftCannotSkipPostingByBecomingReversed() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			return sql("update accounting.journal_entry set status = 'REVERSED', reversed_by = id where id = :id", entry);
		})).hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, insertAccount("A", true, true), "1.00", "0");
			insertLine(entry, 2, insertAccount("B", true, true), "0", "1.00");
			return sql("update accounting.journal_entry set status = 'REVERSED', reversed_by = id where id = :id", entry);
		})).hasStackTraceContaining("cannot be changed");
	}

	@Test
	void reversedEntryAndItsLinesCannotBeChangedOrDeleted() {
		long[] posted = postedEntry();
		reverse(posted[0]);
		assertThatThrownBy(() -> inTransaction(
				() -> sql("delete from accounting.journal_entry where id = :id", posted[0])))
				.hasStackTraceContaining("permission denied");
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_line set memo = 'x' where id = :id", posted[1])))
				.hasStackTraceContaining("cannot be changed");
		assertThatThrownBy(() -> inTransaction(
				() -> sql("delete from accounting.journal_line where id = :id", posted[1])))
				.hasStackTraceContaining("permission denied");
	}

	@Test
	void changingALineOfADeactivatedAccountIsRejected() {
		long[] ids = inTransaction(() -> {
			long account = insertAccount("A", true, true);
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, account, "1.00", "0");
			return new long[] { account, entry };
		});
		inTransaction(() -> sql("update accounting.account set active = false where id = :id", ids[0]));
		assertThatThrownBy(() -> inTransaction(
				() -> sql("update accounting.journal_line set memo = 'x' where entry_id = :id", ids[1])))
				.hasStackTraceContaining("is not postable and active");
	}

	@Test
	void aLineRacingAPostWaitsAndIsRejected() throws Exception {
		long[] draft = inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			insertLine(entry, 1, insertAccount("A", true, true), "1.00", "0");
			insertLine(entry, 2, insertAccount("B", true, true), "0", "1.00");
			return new long[] { entry, insertAccount("C", true, true) };
		});
		CountDownLatch posting = new CountDownLatch(1);
		try (ExecutorService pool = Executors.newFixedThreadPool(2)) {
			Future<Integer> poster = pool.submit(() -> inTransaction(() -> {
				int changed = sql("update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id", draft[0]);
				posting.countDown();
				try {
					Thread.sleep(500);
				}
				catch (InterruptedException ex) {
					Thread.currentThread().interrupt();
				}
				return changed;
			}));
			Future<Integer> writer = pool.submit(() -> {
				posting.await();
				return inTransaction(() -> {
					insertLine(draft[0], 3, draft[1], "1.00", "0");
					return 0;
				});
			});
			assertThat(poster.get()).isEqualTo(1);
			assertThatThrownBy(writer::get).hasStackTraceContaining("cannot be changed");
		}
	}

	@Test
	void onlyADraftHasNoNumber() {
		assertThatThrownBy(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 1), "DRAFT");
			return sql("update accounting.journal_entry set entry_no = 'JE-9' where id = :id", entry);
		})).hasStackTraceContaining("check");
		assertThatThrownBy(() -> inTransaction(() -> {
			long entry = insertEntry(insertPeriod(YEAR, 2), "DRAFT");
			insertLine(entry, 1, insertAccount("D", true, true), "1.00", "0");
			insertLine(entry, 2, insertAccount("E", true, true), "0", "1.00");
			return sql("update accounting.journal_entry set status = 'POSTED' where id = :id", entry);
		})).hasStackTraceContaining("check");
	}

	@Test
	void anEntryIsReversedAtMostOnce() {
		long[] posted = postedEntry();
		long period = inTransaction(() -> ((Number) entityManager.createNativeQuery(
				"select period_id from accounting.journal_entry where id = :id").setParameter("id", posted[0]).getSingleResult()).longValue());
		assertThatThrownBy(() -> inTransaction(() -> {
			long first = insertEntry(period, "DRAFT");
			long second = insertEntry(period, "DRAFT");
			sql("update accounting.journal_entry set reversal_of = " + posted[0] + " where id = :id", first);
			return sql("update accounting.journal_entry set reversal_of = " + posted[0] + " where id = :id", second);
		})).hasStackTraceContaining("duplicate key");
	}

	@Test
	void reversalColumnsMustPointAtAnEntryOfThisTenant() {
		long[] foreign = new long[] { inTenantTransaction(UUID.randomUUID(), "other",
				() -> insertEntry(insertPeriod(YEAR, 1), "DRAFT")) };
		long[] posted = postedEntry();
		long period = inTransaction(() -> ((Number) entityManager.createNativeQuery(
				"select period_id from accounting.journal_entry where id = :id").setParameter("id", posted[0]).getSingleResult()).longValue());
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set reversal_of = " + foreign[0] + " where id = :id", insertEntry(period, "DRAFT"))))
				.hasStackTraceContaining("foreign key");
		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'REVERSED', reversed_by = " + foreign[0] + ", version = version + 1 where id = :id", posted[0])))
				.hasStackTraceContaining("the posted entry that reverses it");
	}

	@Test
	void postingIntoAClosedPeriodIsRefused() {
		long[] draft = inTransaction(() -> {
			long period = insertPeriod(YEAR, 1);
			long entry = insertEntry(period, "DRAFT");
			insertLine(entry, 1, insertAccount("A", true, true), "1.00", "0");
			insertLine(entry, 2, insertAccount("B", true, true), "0", "1.00");
			sql("update accounting.fiscal_period set status = 'CLOSED' where id = :id", period);
			return new long[] { entry, period };
		});

		assertThatThrownBy(() -> inTransaction(() -> sql(
				"update accounting.journal_entry set status = 'POSTED', entry_no = 'JE-1' where id = :id", draft[0])))
				.hasStackTraceContaining("is not open");
		assertThatThrownBy(() -> inTransaction(() -> insertEntry(draft[1], "POSTED")))
				.hasStackTraceContaining("is not open");
	}

	@Test
	void aPostedEntryInAClosedPeriodCanStillBeMarkedReversed() {
		long[] posted = postedEntry();
		inTransaction(() -> sql(
				"update accounting.fiscal_period set status = 'CLOSED' where id = (select period_id from accounting.journal_entry where id = :id)", posted[0]));

		assertThatCode(() -> reverse(posted[0])).doesNotThrowAnyException();
	}
}
