package com.virco.agent.service.impl;


import org.springframework.http.HttpStatus;

import java.util.HashMap;
import java.util.Map;

public class ApiException extends RuntimeException {

    private final HttpStatus status;
    private final String code;
    private final Map<String, Object> details;

    public ApiException(HttpStatus status, String code, String message, Map<String, Object> details) {
        super(message);
        this.status = status;
        this.code = code;
        this.details = details == null ? Map.of() : details;
    }

    public HttpStatus status() { return status; }
    public String code() { return code; }
    public Map<String, Object> details() { return details; }

    public static ApiException unauthorized() {
        return new ApiException(HttpStatus.UNAUTHORIZED, "UNAUTHORIZED", "unauthorized", Map.of());
    }
    public static ApiException notFound(String msg) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", msg, Map.of());
    }
    public static ApiException invalidParam(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", msg, Map.of());
    }
    public static ApiException fileTooLarge(String fileName) {
        Map<String, Object> d = new HashMap<>();
        d.put("fileName", fileName);
        return new ApiException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "file too large", d);
    }
    public static ApiException unsupportedFileType(String fileName, String mime) {
        Map<String, Object> d = new HashMap<>();
        d.put("fileName", fileName);
        d.put("mimeType", mime);
        return new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", "unsupported file type", d);
    }
}