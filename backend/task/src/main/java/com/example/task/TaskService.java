package com.example.task;

import java.util.List;

import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
@Transactional
public class TaskService {

	private final TaskRepository repository;

	public TaskService(TaskRepository repository) {
		this.repository = repository;
	}

	@Transactional(readOnly = true)
	public List<TaskResponse> findAll() {
		return repository.findAll(Sort.by("id")).stream().map(TaskResponse::from).toList();
	}

	@Transactional(readOnly = true)
	public TaskResponse findById(Long id) {
		return TaskResponse.from(get(id));
	}

	public TaskResponse create(TaskRequest request) {
		Task task = repository.saveAndFlush(new Task(request.title(), request.description(), request.done()));
		return TaskResponse.from(task);
	}

	public TaskResponse update(Long id, TaskRequest request) {
		Task task = get(id);
		task.setTitle(request.title());
		task.setDescription(request.description());
		task.setDone(request.done());
		return TaskResponse.from(task);
	}

	public void delete(Long id) {
		repository.delete(get(id));
	}

	private Task get(Long id) {
		return repository.findById(id).orElseThrow(() -> new TaskNotFoundException(id));
	}

}
