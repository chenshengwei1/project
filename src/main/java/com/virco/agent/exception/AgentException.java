package com.virco.agent.exception;

public class AgentException extends RuntimeException {
    private final String code, userMessage;
    public AgentException(String code, String userMessage, Throwable cause) {
        super(userMessage, cause); this.code = code; this.userMessage = userMessage;
    }
    public String code() { return code; }
    public String userMessage() { return userMessage; }
}
