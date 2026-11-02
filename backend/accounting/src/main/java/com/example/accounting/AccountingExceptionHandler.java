package com.example.accounting;

import java.math.BigDecimal;
import java.util.stream.Collectors;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataAccessException;
import org.springframework.http.HttpStatus;
import org.springframework.http.HttpStatusCode;
import org.springframework.http.ProblemDetail;
import org.springframework.http.converter.HttpMessageNotReadableException;
import org.springframework.orm.ObjectOptimisticLockingFailureException;
import org.springframework.transaction.TransactionException;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.MissingServletRequestParameterException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;
import org.springframework.web.method.annotation.MethodArgumentTypeMismatchException;
import tools.jackson.databind.exc.InvalidFormatException;

/** Every failure of this module's API is an RFC 9457 problem with a stable {@code code}. */
@RestControllerAdvice(basePackageClasses = AccountingModule.class)
class AccountingExceptionHandler {

	private static final Logger log = LoggerFactory.getLogger(AccountingExceptionHandler.class);

	@ExceptionHandler(AccountingException.class)
	ProblemDetail rule(AccountingException ex) {
		return problem(ex.status(), ex.code(), ex.getMessage());
	}

	@ExceptionHandler(MethodArgumentNotValidException.class)
	ProblemDetail invalid(MethodArgumentNotValidException ex) {
		String detail = ex.getBindingResult().getFieldErrors().stream()
				.map(error -> error.getField() + " " + error.getDefaultMessage())
				.collect(Collectors.joining(", "));
		return problem(HttpStatus.BAD_REQUEST, "validation", detail);
	}

	@ExceptionHandler({ MissingServletRequestParameterException.class, MethodArgumentTypeMismatchException.class })
	ProblemDetail badParameter(Exception ex) {
		return problem(HttpStatus.BAD_REQUEST, "validation", ex.getMessage());
	}

	@ExceptionHandler(HttpMessageNotReadableException.class)
	ProblemDetail unreadable(HttpMessageNotReadableException ex) {
		if (ex.getMostSpecificCause() instanceof InvalidFormatException format
				&& format.getTargetType() == BigDecimal.class) {
			return problem(HttpStatus.BAD_REQUEST, "invalid-amount", "Amounts must be decimal numbers");
		}
		return problem(HttpStatus.BAD_REQUEST, "validation", "The request body could not be read");
	}

	@ExceptionHandler(ObjectOptimisticLockingFailureException.class)
	ProblemDetail staleVersion(ObjectOptimisticLockingFailureException ex) {
		return problem(HttpStatus.CONFLICT, "version-conflict", "Someone else changed this entry");
	}

	/** The services check every rule first, so reaching a database guard is a bug, not a user error. */
	@ExceptionHandler({ DataAccessException.class, TransactionException.class })
	ProblemDetail invariantBug(Exception ex) {
		log.error("A database guard rejected what the service allowed", ex);
		return problem(HttpStatus.INTERNAL_SERVER_ERROR, "invariant-violated", "The request could not be completed");
	}

	private static ProblemDetail problem(HttpStatusCode status, String code, String detail) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(status, detail);
		problem.setProperty("code", code);
		return problem;
	}

}
