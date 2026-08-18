package com.pkmprojects.mongodbserver.model;

/**
 * Published after an {@link AuditEvent} is persisted, so webhook delivery can
 * observe admin actions without coupling the provisioning service to the
 * notifier.
 */
public record AuditEventRecorded(AuditEvent event) {
}