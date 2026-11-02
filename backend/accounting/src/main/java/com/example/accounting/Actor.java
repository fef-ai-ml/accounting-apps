package com.example.accounting;

import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;

/** The acting user: {@code sub} is the identity for checks, {@code name} is only for display. */
record Actor(String sub, String name) {

	static Actor current() {
		if (SecurityContextHolder.getContext().getAuthentication() instanceof JwtAuthenticationToken token) {
			return new Actor(token.getToken().getSubject(), token.getName());
		}
		throw new IllegalStateException("No authenticated user");
	}

}
