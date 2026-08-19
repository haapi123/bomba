package com.betterloka.api;

/** Raised for any failure talking to a remote service, including "no such player". */
public class ApiException extends Exception {
    private final boolean notFound;

    public ApiException(String message, boolean notFound) {
        super(message);
        this.notFound = notFound;
    }

    public ApiException(String message, Throwable cause) {
        super(message, cause);
        this.notFound = false;
    }

    /** Whether the request succeeded but the resource does not exist. */
    public boolean notFound() {
        return notFound;
    }
}
