package dev.finflow.importing.storage;

import org.springframework.boot.context.properties.ConfigurationProperties;
import org.springframework.boot.context.properties.bind.DefaultValue;

@ConfigurationProperties("finflow.storage")
public record StorageProperties(
        @DefaultValue("http://localhost:9000") String endpoint,
        @DefaultValue("finflow") String accessKey,
        @DefaultValue("finflow-minio") String secretKey,
        @DefaultValue("finflow-statements") String bucket) {
}
