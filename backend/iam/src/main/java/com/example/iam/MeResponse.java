package com.example.iam;

import java.util.List;

/**
 * {@code tenant} is null when the token names no organization known to {@code iam} (by organization id), or several;
 * a SUSPENDED tenant is shown so the UI can explain.
 */
public record MeResponse(String username, String name, List<String> roles, List<String> permissions,
		TenantResponse tenant) {
}
