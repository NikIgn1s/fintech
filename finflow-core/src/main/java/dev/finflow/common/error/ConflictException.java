package dev.finflow.common.error;

import org.springframework.http.HttpStatus;

public class ConflictException extends ApiException {

    public ConflictException(String code, String title, String detail) {
        super(HttpStatus.CONFLICT, code, title, detail);
    }
}
