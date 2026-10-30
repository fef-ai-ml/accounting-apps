package com.example.iam;

import java.util.List;

/** {@code tenant} is null when the token names no known organization, or more than one. */
public record MeResponse(String username, String name, List<String> roles, List<String> permissions,
		TenantResponse tenant) {
}
