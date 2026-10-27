package com.example.ui.task;

import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.ComponentScan;
import org.springframework.context.annotation.Configuration;
import org.springframework.context.annotation.Import;

import com.example.ui.common.UiModule;
import com.example.ui.common.ZkSupport;

/** Entry point of the task UI module. A deployable UI app includes it with {@code @Import(TaskUiModule.class)}. */
@Configuration(proxyBeanMethods = false)
@Import(ZkSupport.class)
@ComponentScan(basePackageClasses = TaskUiModule.class)
public class TaskUiModule {

	static final String ID = "task";

	@Bean
	UiModule taskUi() {
		return UiModule.embedded(ID, "Tasks", 10, "~./task/main.zul");
	}

}
