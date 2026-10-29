package com.example.ui.task;

import java.util.List;

import org.zkoss.bind.annotation.BindingParam;
import org.zkoss.bind.annotation.Command;
import org.zkoss.bind.annotation.Init;
import org.zkoss.bind.annotation.NotifyChange;
import org.zkoss.zk.ui.select.annotation.VariableResolver;
import org.zkoss.zk.ui.select.annotation.WireVariable;
import org.zkoss.zk.ui.util.Clients;

import com.example.ui.common.ApiErrors;
import com.example.ui.common.CurrentUser;
import com.example.ui.common.SpringBeanVariableResolver;

/** MVVM view model behind main.zul. Controls the user may not use are hidden; the backend enforces regardless. */
@VariableResolver(SpringBeanVariableResolver.class)
public class TaskViewModel {

	static final String WRITE = "task:write";
	static final String DELETE = "task:delete";

	@WireVariable
	private TaskApiClient taskApiClient;

	@WireVariable
	private CurrentUser currentUser;

	private List<TaskDto> tasks = List.of();
	private Long editingId;
	private String title = "";
	private String description = "";
	private boolean done;
	private boolean canWrite;
	private boolean canDelete;

	@Init
	public void init() {
		try {
			canWrite = currentUser.can(WRITE);
			canDelete = currentUser.can(DELETE);
		}
		catch (RuntimeException ex) {
			// The shell has already reported this failure; stay read-only and let reload() show the API state.
		}
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
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
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
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
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
		catch (RuntimeException ex) {
			ApiErrors.show(ex);
			return;
		}
		reload();
	}

	private void reload() {
		try {
			tasks = taskApiClient.list();
		}
		catch (RuntimeException ex) {
			tasks = List.of();
			ApiErrors.show(ex);
		}
	}

	private void clearForm() {
		editingId = null;
		title = "";
		description = "";
		done = false;
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

	public boolean isCanWrite() {
		return canWrite;
	}

	public boolean isCanDelete() {
		return canDelete;
	}

}
