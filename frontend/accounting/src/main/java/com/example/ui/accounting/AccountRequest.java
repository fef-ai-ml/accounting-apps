package com.example.ui.accounting;

public record AccountRequest(String code, String name, String type, Long parentId, boolean postable, Boolean active) {
}
