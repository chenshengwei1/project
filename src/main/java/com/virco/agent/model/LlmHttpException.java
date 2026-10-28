package com.virco.agent.model;

import java.io.IOException;

public class LlmHttpException extends IOException {
    private final int status; private final boolean retryable;
    public LlmHttpException(int status, String body, boolean retryable) {
        super("HTTP " + status + ": " + abbreviate(body, 300));
        this.status = status; this.retryable = retryable;
    }
    public int status() { return status; }
    public boolean retryable() { return retryable; }
    private static String abbreviate(String s, int max) {
        return s == null ? null : (s.length() <= max ? s : s.substring(0, max) + "...");
    }
}