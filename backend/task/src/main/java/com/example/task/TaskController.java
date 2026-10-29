package com.example.task;

import java.net.URI;
import java.util.List;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

@RestController
@RequestMapping("/api/tasks")
public class TaskController {

	private final TaskService service;

	public TaskController(TaskService service) {
		this.service = service;
	}

	@GetMapping
	@PreAuthorize("hasAuthority('task:read')")
	public List<TaskResponse> list() {
		return service.findAll();
	}

	@GetMapping("/{id}")
	@PreAuthorize("hasAuthority('task:read')")
	public TaskResponse get(@PathVariable Long id) {
		return service.findById(id);
	}

	@PostMapping
	@PreAuthorize("hasAuthority('task:write')")
	public ResponseEntity<TaskResponse> create(@Valid @RequestBody TaskRequest request) {
		TaskResponse created = service.create(request);
		return ResponseEntity.created(URI.create("/api/tasks/" + created.id())).body(created);
	}

	@PutMapping("/{id}")
	@PreAuthorize("hasAuthority('task:write')")
	public TaskResponse update(@PathVariable Long id, @Valid @RequestBody TaskRequest request) {
		return service.update(id, request);
	}

	@DeleteMapping("/{id}")
	@PreAuthorize("hasAuthority('task:delete')")
	public ResponseEntity<Void> delete(@PathVariable Long id) {
		service.delete(id);
		return ResponseEntity.noContent().build();
	}

}
