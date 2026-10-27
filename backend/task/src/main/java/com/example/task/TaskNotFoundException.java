package com.example.task;

import com.example.common.ResourceNotFoundException;

public class TaskNotFoundException extends ResourceNotFoundException {

	public TaskNotFoundException(Long id) {
		super("Task %d not found".formatted(id));
	}

}
