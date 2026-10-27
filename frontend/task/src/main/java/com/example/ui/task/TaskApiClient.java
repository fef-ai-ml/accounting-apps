package com.example.ui.task;

import java.util.List;

import org.springframework.core.ParameterizedTypeReference;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.example.ui.common.ModuleRestClients;

@Component
public class TaskApiClient {

	private static final String PATH = "/api/tasks";

	private final RestClient client;

	public TaskApiClient(ModuleRestClients clients) {
		this.client = clients.forModule(TaskUiModule.ID);
	}

	public List<TaskDto> list() {
		return client.get().uri(PATH).retrieve().body(new ParameterizedTypeReference<>() {
		});
	}

	public TaskDto create(TaskRequest request) {
		return client.post().uri(PATH).body(request).retrieve().body(TaskDto.class);
	}

	public TaskDto update(Long id, TaskRequest request) {
		return client.put().uri(PATH + "/{id}", id).body(request).retrieve().body(TaskDto.class);
	}

	public void delete(Long id) {
		client.delete().uri(PATH + "/{id}", id).retrieve().toBodilessEntity();
	}

}
