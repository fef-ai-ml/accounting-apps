package com.example.accounting;

import java.sql.SQLException;

import org.hibernate.exception.ConstraintViolationException;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.extension.ExtendWith;
import org.springframework.boot.test.system.CapturedOutput;
import org.springframework.boot.test.system.OutputCaptureExtension;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.dao.DataAccessResourceFailureException;
import org.springframework.http.ProblemDetail;

import static org.assertj.core.api.Assertions.assertThat;

@ExtendWith(OutputCaptureExtension.class)
class AccountingExceptionHandlerTests {

	private final AccountingExceptionHandler handler = new AccountingExceptionHandler();

	private ProblemDetail problemFor(String sqlState) {
		return handler.database(new DataIntegrityViolationException("x", new SQLException("reason", sqlState)));
	}

	private static String code(ProblemDetail problem) {
		return (String) problem.getProperties().get("code");
	}

	@Test
	void triggersAndChecksAreInvariantBugs(CapturedOutput output) {
		assertThat(code(problemFor("P0001"))).isEqualTo("invariant-violated");
		assertThat(problemFor("P0001").getStatus()).isEqualTo(500);
		assertThat(code(problemFor("23514"))).isEqualTo("invariant-violated");
		assertThat(output).contains("ERROR").contains("A database guard rejected");
	}

	@Test
	void aDuplicateIsAConflictLoggedWithItsConstraint(CapturedOutput output) {
		ProblemDetail problem = handler.database(new DataIntegrityViolationException("x", new ConstraintViolationException(
				"duplicate", new SQLException("reason", "23505"), "journal_entry_tenant_id_entry_no_key")));

		assertThat(problem.getStatus()).isEqualTo(409);
		assertThat(code(problem)).isEqualTo("conflict");
		assertThat(output).contains("WARN").contains("23505").contains("journal_entry_tenant_id_entry_no_key");
	}

	@Test
	void aValueTheDatabaseRefusesIsValidationLoggedWithItsState(CapturedOutput output) {
		ProblemDetail problem = problemFor("22P02");

		assertThat(problem.getStatus()).isEqualTo(400);
		assertThat(code(problem)).isEqualTo("validation");
		assertThat(output).contains("WARN").contains("22P02");
	}

	@Test
	void anythingElseIsADatabaseError(CapturedOutput output) {
		assertThat(code(problemFor("42501"))).isEqualTo("database-error");
		assertThat(code(handler.database(new DataAccessResourceFailureException("no SQLException")))).isEqualTo("database-error");
		assertThat(output).contains("ERROR").contains("Database operation failed");
	}

}
