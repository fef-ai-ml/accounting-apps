package com.example.ui.common;

import java.util.List;

/** The signed-in user as returned by {@code GET /api/iam/me}. A UI-side copy: UI code never imports backend classes. */
public record MeDto(String username, String name, List<String> roles, List<String> permissions) {
}
