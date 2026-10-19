package dev.finflow.importing.storage;

import dev.finflow.common.error.ApiException;
import org.springframework.http.HttpStatus;

public class StorageUnavailableException extends ApiException {

    public StorageUnavailableException(Throwable cause) {
        super(HttpStatus.SERVICE_UNAVAILABLE, "storage-unavailable", "Storage unavailable",
                "File storage is temporarily unavailable");
        initCause(cause);
    }
}
