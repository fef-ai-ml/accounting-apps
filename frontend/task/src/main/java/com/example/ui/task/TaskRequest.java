package com.example.ui.task;

/** Payload sent to the REST API when creating or updating a task. */
public record TaskRequest(String title, String description, boolean done) {
}
