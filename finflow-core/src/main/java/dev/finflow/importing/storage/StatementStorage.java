package dev.finflow.importing.storage;

import dev.finflow.common.error.NotFoundException;
import io.minio.BucketExistsArgs;
import io.minio.GetObjectArgs;
import io.minio.MakeBucketArgs;
import io.minio.MinioClient;
import io.minio.PutObjectArgs;
import io.minio.RemoveObjectArgs;
import io.minio.errors.ErrorResponseException;
import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.io.InputStream;
import java.util.UUID;
import java.util.regex.Pattern;
import lombok.extern.slf4j.Slf4j;
import org.springframework.stereotype.Service;

@Slf4j
@Service
public class StatementStorage {

    private static final int ATTEMPTS = 3;
    private static final int MAX_FILE_NAME_LENGTH = 100;
    private static final Pattern UNSAFE = Pattern.compile("[^A-Za-z0-9._-]");
    private static final String NO_SUCH_KEY = "NoSuchKey";

    @FunctionalInterface
    private interface StorageCall<T> {
        T run() throws Exception;
    }

    private final MinioClient client;
    private final String bucket;
    private volatile boolean bucketReady;

    public StatementStorage(MinioClient client, StorageProperties properties) {
        this.client = client;
        this.bucket = properties.bucket();
    }

    public String store(UUID userId, UUID importId, String originalName, byte[] content, String contentType) {
        String key = "statements/" + userId + "/" + importId + "/" + safeName(originalName);
        String type = contentType == null ? "application/octet-stream" : contentType;
        try {
            withRetry(() -> {
                ensureBucket();
                return client.putObject(PutObjectArgs.builder().bucket(bucket).object(key).contentType(type)
                        .stream(new ByteArrayInputStream(content), content.length, -1).build());
            });
            return key;
        } catch (Exception e) {
            throw new StorageUnavailableException(e);
        }
    }

    public InputStream open(String key) {
        try {
            return withRetry(() -> client.getObject(GetObjectArgs.builder().bucket(bucket).object(key).build()));
        } catch (ErrorResponseException e) {
            if (NO_SUCH_KEY.equals(e.errorResponse().code())) {
                throw new NotFoundException("File " + key + " not found");
            }
            throw new StorageUnavailableException(e);
        } catch (Exception e) {
            throw new StorageUnavailableException(e);
        }
    }

    public void delete(String key) {
        try {
            withRetry(() -> {
                client.removeObject(RemoveObjectArgs.builder().bucket(bucket).object(key).build());
                return null;
            });
        } catch (Exception e) {
            throw new StorageUnavailableException(e);
        }
    }

    static String safeName(String originalName) {
        String name = originalName == null ? "" : originalName.replace('\\', '/');
        name = name.substring(name.lastIndexOf('/') + 1).trim();
        name = UNSAFE.matcher(name).replaceAll("_").replaceAll("^\\.+", "_");
        if (name.isBlank()) {
            name = "statement.csv";
        }
        return name.length() <= MAX_FILE_NAME_LENGTH ? name : name.substring(name.length() - MAX_FILE_NAME_LENGTH);
    }

    private <T> T withRetry(StorageCall<T> call) throws Exception {
        IOException last = null;
        for (int attempt = 1; attempt <= ATTEMPTS; attempt++) {
            try {
                return call.run();
            } catch (IOException e) {
                last = e;
                log.warn("Storage call failed on attempt {} of {}: {}", attempt, ATTEMPTS, e.toString());
            }
        }
        throw last;
    }

    private void ensureBucket() throws Exception {
        if (bucketReady) {
            return;
        }
        synchronized (this) {
            if (!bucketReady) {
                if (!client.bucketExists(BucketExistsArgs.builder().bucket(bucket).build())) {
                    client.makeBucket(MakeBucketArgs.builder().bucket(bucket).build());
                    log.info("Created storage bucket {}", bucket);
                }
                bucketReady = true;
            }
        }
    }
}
