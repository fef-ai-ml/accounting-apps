package com.example.accounting;

import org.springframework.http.HttpStatus;

/** A refused business rule; rendered as an RFC 9457 problem with a stable {@code code}. */
public class AccountingException extends RuntimeException {

	private final HttpStatus status;

	private final String code;

	public AccountingException(HttpStatus status, String code, String detail) {
		super(detail);
		this.status = status;
		this.code = code;
	}

	static AccountingException badRequest(String code, String detail) {
		return new AccountingException(HttpStatus.BAD_REQUEST, code, detail);
	}

	static AccountingException forbidden(String code, String detail) {
		return new AccountingException(HttpStatus.FORBIDDEN, code, detail);
	}

	static AccountingException conflict(String code, String detail) {
		return new AccountingException(HttpStatus.CONFLICT, code, detail);
	}

	static AccountingException unprocessable(String code, String detail) {
		return new AccountingException(HttpStatus.valueOf(422), code, detail);
	}

	HttpStatus status() {
		return status;
	}

	String code() {
		return code;
	}

}
