package com.example.shell;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.example.ui.accounting.AccountingUiModule;
import com.example.ui.common.ZkSupport;
import com.example.ui.task.TaskUiModule;

/**
 * Hosts the UI modules. To run a module as its own app (micro-frontend), create another app that imports only that
 * module, and register it here with {@code UiModule.remote(...)} instead of importing it.
 */
@SpringBootApplication
@Import({ ZkSupport.class, TaskUiModule.class, AccountingUiModule.class })
public class ShellApplication {

	public static void main(String[] args) {
		SpringApplication.run(ShellApplication.class, args);
	}

}
