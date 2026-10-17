package com.itiarz.common;

import org.springframework.http.HttpStatus;

/** Business failure rendered as a problem response; {@code detail} is user-facing (Turkish). */
public class DomainException extends RuntimeException {
    private final HttpStatus status;
    private final ErrorCode code;

    public DomainException(HttpStatus status, ErrorCode code, String detail) {
        super(detail);
        this.status = status;
        this.code = code;
    }

    public HttpStatus status() {
        return status;
    }

    public ErrorCode code() {
        return code;
    }
}
