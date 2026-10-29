package com.example.iam;

import java.util.List;

public record MeResponse(String username, String name, List<String> roles, List<String> permissions) {
}
