package com.shuttle.server.storage;

import java.io.IOException;
import java.util.Objects;

/** A single object updated with optimistic concurrency control. */
public interface ObjectStore {
    record Versioned(byte[] data, String etag) {
        public Versioned {
            data = Objects.requireNonNull(data, "data").clone();
            Objects.requireNonNull(etag, "etag");
        }

        @Override
        public byte[] data() {
            return data.clone();
        }
    }

    /** The expected object version no longer matches; the caller must reload before retrying. */
    final class ConflictException extends IOException {
        public ConflictException(String message) {
            super(message);
        }
    }

    /** Returns null only when the object does not exist. */
    Versioned get() throws IOException;

    /** Null expectedEtag creates a new object; otherwise updates that exact version only. */
    String put(byte[] data, String expectedEtag) throws IOException;
}
