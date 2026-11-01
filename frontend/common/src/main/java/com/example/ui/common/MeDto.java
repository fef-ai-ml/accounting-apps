package com.example.ui.common;

import java.util.List;

/**
 * The signed-in user as returned by {@code GET /api/iam/me}. A UI-side copy: UI code never imports backend classes.
 * {@code tenant} is null when the user belongs to no known organization.
 */
public record MeDto(String username, String name, List<String> roles, List<String> permissions, TenantDto tenant) {
}
