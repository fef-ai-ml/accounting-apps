package com.example.app;

import org.springframework.boot.SpringApplication;
import org.springframework.boot.autoconfigure.SpringBootApplication;
import org.springframework.context.annotation.Import;

import com.example.task.TaskModule;

/**
 * Assembles the bounded contexts into one deployable. To split a context out into its own microservice,
 * create another app like this one that imports only that module.
 */
@SpringBootApplication
@Import({ TaskModule.class })
public class ApiApplication {

	public static void main(String[] args) {
		SpringApplication.run(ApiApplication.class, args);
	}

}
