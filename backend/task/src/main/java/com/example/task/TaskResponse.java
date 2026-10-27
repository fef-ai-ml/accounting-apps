package com.example.task;

import java.time.Instant;

public record TaskResponse(Long id, String title, String description, boolean done, Instant createdAt) {

	static TaskResponse from(Task task) {
		return new TaskResponse(task.getId(), task.getTitle(), task.getDescription(), task.isDone(),
				task.getCreatedAt());
	}

}
