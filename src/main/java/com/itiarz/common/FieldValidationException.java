package com.itiarz.common;

import org.springframework.http.HttpStatus;

/** 422 VALIDATION for one request field detected by business code; rendered with {@code errors[{field, message}]}. */
public class FieldValidationException extends DomainException {
    private final String field;

    public FieldValidationException(String field, String message) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, ErrorCode.VALIDATION, message);
        this.field = field;
    }

    public String field() {
        return field;
    }
}
