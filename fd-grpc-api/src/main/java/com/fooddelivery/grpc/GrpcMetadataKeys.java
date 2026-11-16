package com.fooddelivery.grpc;

import io.grpc.Metadata;

/**
 * Metadata keys shared by the gRPC servers and clients of the services, part of the contract like the {@code .proto}
 * files: a server writes them, a client reads them, so both must use the same names.
 */
public final class GrpcMetadataKeys {

    // Trailer of a failed call with the business error code, as in the HTTP API, e.g. "USER_NOT_FOUND". Absent on
    // errors that are not business errors, e.g. UNAVAILABLE
    public static final Metadata.Key<String> ERROR_CODE = Metadata.Key.of("error-code", Metadata.ASCII_STRING_MARSHALLER);

    // Header of every call with the spring.application.name of the calling service, e.g. "service-order", so the
    // server can log who called it. Informative only: anyone inside the network can set it
    public static final Metadata.Key<String> CALLER_SERVICE =
            Metadata.Key.of("x-caller-service", Metadata.ASCII_STRING_MARSHALLER);

    private GrpcMetadataKeys() {}
}
