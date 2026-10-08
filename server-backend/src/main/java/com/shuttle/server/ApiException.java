package com.shuttle.server;

/**
 * Custom runtime exception to handle API errors gracefully[cite: 21].
 * Maps system errors into clean HTTP status codes and messages[cite: 8].
 */
public class ApiException extends RuntimeException {

    private final int statusCode;

    public ApiException(int statusCode, String message) {
        super(message);
        this.statusCode = statusCode;
    }

    public int getStatusCode() {
        return statusCode;
    }
}