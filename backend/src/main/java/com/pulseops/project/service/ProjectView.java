package com.pulseops.project.service;

import java.time.Instant;
import java.util.UUID;

import com.pulseops.project.domain.Environment;
import com.pulseops.project.domain.ProjectStatus;

/** A project with service and connection counts, for lists and detail pages. */
public record ProjectView(UUID id, String name, String description, String techStack, Environment environment,
                          String region, String applicationType, ProjectStatus status, long serviceCount,
                          long connectedServiceCount, Instant createdAt, Instant updatedAt) {
}
