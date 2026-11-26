package com.fooddelivery.grpc;

import io.grpc.Metadata;

/**
 * Metadata keys shared by the gRPC servers and clients of the services, part of the contract like the {@code .proto}
 * files: a server writes them, a client reads them, so both must use the same names.
 */
public final class GrpcMetadataKeys {

    // Trailer of a failed call with the business error code, as in the HTTP API, e.g. "USER_NOT_FOUND". Absent on
    // errors that are not business errors, e.g. UNAVAILABLE
    public static final Metadata.Key<String> ERROR_CODE =
            Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER);

    // Header of every call with the spring.application.name of the calling service, e.g. "service-order", so the
    // server can log who called it. Informative only: anyone inside the network can set it
    public static final Metadata.Key<String> CALLER_SERVICE =
            Metadata.Key.of("x-caller-service", Metadata.ASCII_STRING_MARSHALLER);

    // Header of every call with the ID of the request being handled, e.g. "3f2a9c1e-7b4d-4e0a-9f1c-2d6e8b5a7c30", so
    // the log lines of every service it went through share it. Same name as the HTTP header of the gateway
    public static final Metadata.Key<String> REQUEST_ID =
            Metadata.Key.of("x-request-id", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcMetadataKeys() {}
}
