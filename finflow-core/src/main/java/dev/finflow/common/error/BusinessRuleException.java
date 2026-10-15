package dev.finflow.common.error;

import org.springframework.http.HttpStatus;

public class BusinessRuleException extends ApiException {

    public BusinessRuleException(String code, String title, String detail) {
        super(HttpStatus.UNPROCESSABLE_ENTITY, code, title, detail);
    }
}
