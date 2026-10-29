package com.example.ui.common;

import org.junit.jupiter.api.Test;

import static org.assertj.core.api.Assertions.assertThat;

class UiModuleTests {

	@Test
	void modulesNeedNoPermissionByDefault() {
		assertThat(UiModule.embedded("task", "Tasks", 10, "~./task/main.zul").requiredPermission()).isNull();
		assertThat(UiModule.remote("task", "Tasks", 10, "http://localhost:8083").requiredPermission()).isNull();
	}

	@Test
	void withPermissionKeepsEverythingElse() {
		UiModule module = UiModule.embedded("task", "Tasks", 10, "~./task/main.zul").withPermission("task:read");

		assertThat(module).isEqualTo(new UiModule("task", "Tasks", 10, "~./task/main.zul", null, "task:read"));
	}

}
