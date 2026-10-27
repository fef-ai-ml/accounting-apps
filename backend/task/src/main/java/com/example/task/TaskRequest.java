package com.example.task;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Size;

public record TaskRequest(
		@NotBlank @Size(max = 200) String title,
		@Size(max = 2000) String description,
		boolean done) {
}
