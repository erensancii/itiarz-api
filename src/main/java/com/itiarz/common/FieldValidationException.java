package com.itiarz.common;

import org.springframework.http.HttpStatus;

/**
 * A problem tied to one request field detected by business code; rendered with
 * {@code errors[{field, message}]}. Defaults to 422 VALIDATION; a field-level conflict (e.g. a name
 * already taken) uses 409 CONFLICT.
 */
public class FieldValidationException extends DomainException {
    private final String field;

    public FieldValidationException(String field, String message) {
        this(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION, field, message);
    }

    public FieldValidationException(HttpStatus status, ErrorCode code, String field, String message) {
        super(status, code, message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
