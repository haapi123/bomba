package com.betterloka.api;

/** Raised for any failure talking to the Loka API, including "player not found". */
public class LokaApiException extends Exception {
    private final boolean notFound;

    public LokaApiException(String message, boolean notFound) {
        super(message);
        this.notFound = notFound;
    }

    public LokaApiException(String message, Throwable cause) {
        super(message, cause);
        this.notFound = false;
    }

    /** Whether the request succeeded but the resource does not exist. */
    public boolean notFound() {
        return notFound;
    }
}
