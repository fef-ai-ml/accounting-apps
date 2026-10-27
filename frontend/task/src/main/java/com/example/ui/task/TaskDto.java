package com.example.ui.task;

import java.time.Instant;

/** A task as returned by the REST API. */
public record TaskDto(Long id, String title, String description, boolean done, Instant createdAt) {

	// JavaBean getters: ZK's EL cannot read record accessors like title().

	public Long getId() {
		return id;
	}

	public String getTitle() {
		return title;
	}

	public String getDescription() {
		return description;
	}

	public boolean isDone() {
		return done;
	}

}
