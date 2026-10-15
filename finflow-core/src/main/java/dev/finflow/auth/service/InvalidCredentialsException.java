package dev.finflow.auth.service;

import dev.finflow.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidCredentialsException extends ApiException {

    public InvalidCredentialsException() {
        super(HttpStatus.UNAUTHORIZED, "invalid-credentials", "Invalid credentials",
                "Email or password is incorrect");
    }
}
