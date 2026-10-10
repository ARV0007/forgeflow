package com.forgeflow.execution.dto;

/** @param checkpointId a version to publish (a rollback); omitted = the project as it is now */
public record PublishRequest(Long checkpointId) {
}
