package com.example.security;

import org.springframework.http.HttpStatus;
import org.springframework.http.ProblemDetail;
import org.springframework.security.access.AccessDeniedException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

/** A missing permission ({@code @PreAuthorize} denial) is answered like every other error: an RFC 9457 problem. */
@RestControllerAdvice
class AccessDeniedProblemHandler {

	@ExceptionHandler(AccessDeniedException.class)
	ProblemDetail accessDenied(AccessDeniedException ex) {
		ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.FORBIDDEN,
				"You do not have permission to perform this action");
		problem.setProperty("code", "forbidden");
		return problem;
	}

}
