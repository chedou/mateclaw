package vip.mate.troubleshooting.service;

import vip.mate.troubleshooting.model.SopEntry;

/** Indexed registry metadata paired with its authoritative stored contract. */
public record SopRegistryRecord(
        SopSummary summary,
        SopEntry entry) {

    public SopRegistryRecord {
        if (summary == null || entry == null
                || !summary.sopId().equals(entry.sopId())
                || !summary.routeKey().equals(entry.routingKey())) {
            throw new IllegalArgumentException(
                    "SOP registry summary and contract must share one identity");
        }
    }
}
