package com.example.iam;

import java.util.List;

public record RoleResponse(String role, List<String> permissions) {
}
