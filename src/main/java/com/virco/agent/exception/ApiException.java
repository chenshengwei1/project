package com.virco.agent.exception;

import com.virco.agent.entity.ChatDTO;
import com.virco.agent.entity.ChatDTO.*;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.validation.FieldError;
import org.springframework.web.bind.MethodArgumentNotValidException;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.RestControllerAdvice;

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
    public static ApiException forbidden(String msg) {
        return new ApiException(HttpStatus.FORBIDDEN, "FORBIDDEN", msg, Map.of());
    }
    public static ApiException notFound(String msg) {
        return new ApiException(HttpStatus.NOT_FOUND, "NOT_FOUND", msg, Map.of());
    }
    public static ApiException invalidParam(String msg) {
        return new ApiException(HttpStatus.BAD_REQUEST, "INVALID_PARAM", msg, Map.of());
    }
    public static ApiException fileTooLarge(String fileName) {
        return new ApiException(HttpStatus.BAD_REQUEST, "FILE_TOO_LARGE", "file too large",
                Map.of("fileName", fileName));
    }
    public static ApiException unsupportedFileType(String fileName, String mime) {
        return new ApiException(HttpStatus.BAD_REQUEST, "UNSUPPORTED_FILE_TYPE", "unsupported file type",
                Map.of("fileName", fileName, "mimeType", mime == null ? "" : mime));
    }

    @RestControllerAdvice
    public static class Handler {
        private static final Logger log = LoggerFactory.getLogger(Handler.class);

        @ExceptionHandler(ApiException.class)
        public ResponseEntity<ChatDTO.ErrorResponse> handle(ApiException e) {
            return ResponseEntity.status(e.status())
                    .body(new ChatDTO.ErrorResponse(new ChatDTO.ErrorBody(e.code(), e.getMessage(), e.details())));
        }

        @ExceptionHandler(MethodArgumentNotValidException.class)
        public ResponseEntity<ChatDTO.ErrorResponse> handleValidation(MethodArgumentNotValidException e) {
            Map<String, Object> details = new HashMap<>();
            for (FieldError fe : e.getBindingResult().getFieldErrors()) {
                details.put(fe.getField(), fe.getDefaultMessage());
            }
            return ResponseEntity.badRequest().body(new ChatDTO.ErrorResponse(
                    new ChatDTO.ErrorBody("INVALID_PARAM", "validation failed", details)));
        }

        @ExceptionHandler(Exception.class)
        public ResponseEntity<ChatDTO.ErrorResponse> handleOthers(Exception e) {
            log.error("unexpected error", e);
            return ResponseEntity.status(500).body(new ChatDTO.ErrorResponse(
                    new ChatDTO.ErrorBody("INTERNAL_ERROR", "internal error", Map.of())));
        }
    }
}