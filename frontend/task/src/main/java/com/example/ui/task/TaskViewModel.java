package com.example.ui.task;

import java.util.List;

import org.springframework.web.client.RestClientException;
import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;

import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind index.zul. */
@VariableResolver(SpringBeanVariableResolver.class)
public class TaskViewModel {

	@WireVariable
	private TaskApiClient taskApiClient;

	private List<TaskDto> tasks = List.of();
	private Long editingId;
	private String title = "";
	private String description = "";
	private boolean done;

	@Init
	public void init() {
		reload();
	}

	@Command
	@NotifyChange({ "tasks", "editingId", "title", "description", "done" })
	public void save() {
		if (title == null || title.isBlank()) {
			Clients.showNotification("Title is required", "warning", null, "end_center", 2000);
			return;
		}
		TaskRequest request = new TaskRequest(title.trim(), description, done);
		try {
			if (editingId == null) {
				taskApiClient.create(request);
			}
			else {
				taskApiClient.update(editingId, request);
			}
		}
		catch (RestClientException ex) {
			apiError(ex);
			return;
		}
		clearForm();
		reload();
	}

	@Command
	@NotifyChange({ "editingId", "title", "description", "done" })
	public void edit(@BindingParam("task") TaskDto task) {
		editingId = task.id();
		title = task.title();
		description = task.description();
		done = task.done();
	}

	@Command
	@NotifyChange({ "editingId", "title", "description", "done" })
	public void cancel() {
		clearForm();
	}

	@Command
	@NotifyChange({ "tasks", "editingId", "title", "description", "done" })
	public void delete(@BindingParam("task") TaskDto task) {
		try {
			taskApiClient.delete(task.id());
		}
		catch (RestClientException ex) {
			apiError(ex);
			return;
		}
		if (task.id().equals(editingId)) {
			clearForm();
		}
		reload();
	}

	@Command
	@NotifyChange("tasks")
	public void toggle(@BindingParam("task") TaskDto task) {
		try {
			taskApiClient.update(task.id(), new TaskRequest(task.title(), task.description(), !task.done()));
		}
		catch (RestClientException ex) {
			apiError(ex);
			return;
		}
		reload();
	}

	private void reload() {
		try {
			tasks = taskApiClient.list();
		}
		catch (RestClientException ex) {
			tasks = List.of();
			apiError(ex);
		}
	}

	private void clearForm() {
		editingId = null;
		title = "";
		description = "";
		done = false;
	}

	private void apiError(RestClientException ex) {
		Clients.showNotification("API error: " + ex.getMessage(), "error", null, "middle_center", 4000);
	}

	public List<TaskDto> getTasks() {
		return tasks;
	}

	public Long getEditingId() {
		return editingId;
	}

	public String getTitle() {
		return title;
	}

	public void setTitle(String title) {
		this.title = title;
	}

	public String getDescription() {
		return description;
	}

	public void setDescription(String description) {
		this.description = description;
	}

	public boolean isDone() {
		return done;
	}

	public void setDone(boolean done) {
		this.done = done;
	}

}
