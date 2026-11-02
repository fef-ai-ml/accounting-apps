package com.example.accounting;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

/** {@code active} defaults to true when left out. */
public record AccountRequest(
		@NotBlank @Size(max = 20) String code,
		@NotBlank @Size(max = 200) String name,
		@NotNull AccountType type,
		Long parentId,
		boolean postable,
		Boolean active) {

	boolean activeOrDefault() {
		return active == null || active;
	}

}
