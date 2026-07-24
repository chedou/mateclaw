package vip.mate.troubleshooting.model;

/** Durable lifecycle of one at-least-once knowledge publication. */
public enum KnowledgePublicationStatus {
    PENDING,
    PROCESSING,
    PUBLISHED,
    FAILED
}
