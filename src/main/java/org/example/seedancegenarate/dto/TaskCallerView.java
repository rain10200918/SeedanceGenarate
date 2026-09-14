package org.example.seedancegenarate.dto;

/** Detail-only display projection. Never contains credentials or contact information. */
public record TaskCallerView(String callerName, String apiKeyName) {}
