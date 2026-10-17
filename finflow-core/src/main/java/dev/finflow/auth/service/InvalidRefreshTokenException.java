package dev.finflow.auth.service;

import dev.finflow.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class InvalidRefreshTokenException extends ApiException {

    public InvalidRefreshTokenException() {
        super(HttpStatus.UNAUTHORIZED, "invalid-refresh-token", "Invalid refresh token",
                "Refresh token is invalid, expired or already used");
    }
}
