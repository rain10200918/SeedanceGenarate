package org.example.seedancegenarate.entity;

import java.time.LocalDateTime;
import java.util.List;

/** Deliberately excludes storage keys, owner and request fingerprints. */
public record ShowcaseWork(String id, String title, String description, String mediaType,
                           boolean hasCover, String status, int sortOrder, long version,
                           LocalDateTime createdAt, LocalDateTime publishedAt) {
    public record Edit(String title, String description, Integer sortOrder, Long expectedVersion, String status) {}
    public record Page<T>(List<T> records, long total, long current, long size) {}
}
