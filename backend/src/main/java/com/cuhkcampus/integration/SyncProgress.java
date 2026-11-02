package com.cuhkcampus.integration;

public record SyncProgress(String stage, String message, int completed, int total) {}
