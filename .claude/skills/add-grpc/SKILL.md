---
name: add-grpc
description: Add or change a gRPC API between two services of this backend (one service calls another, e.g. "order needs restaurant info", "create a gRPC for X", "service A calls service B"). Covers the .proto contract in fd-grpc-api, the XxxGrpcService server, the XxxGrpcClient client, config, tests and docs, following docs/grpc-conventions.md so every gRPC has the same structure.
---

# Add a gRPC API

Every gRPC API of this repository follows **one** structure, written in `docs/grpc-conventions.md`. Your job is to
apply it exactly, not to design a new one. Answer the user in Vietnamese, as the rest of the project does.

## Steps

1. **Read `docs/grpc-conventions.md` in full** before writing anything. Its templates (section 2-4) are the code to
   copy; its example is auth (server) -> order (client) with `UserService/GetUser`.
2. **Fix the names** from the request and write them down in your first message to the user, using the table at the
   top of the conventions: called service (server), calling service (client), `XxxService`, rpc names, class names
   (`XxxGrpcService`, `XxxGrpcValidator`, `XxxGrpcClient`, result record), error-code prefix (`<CALLED>_`), gRPC port
   (HTTP port + 1000), channel name. If the request leaves something open that changes the contract (which fields
   the caller needs, which errors), ask before writing code; do not add fields "just in case".
3. **Check the prerequisite** (step 1 of the conventions): both services use `common-lib`. If one does not, stop and
   tell the user what moving it to `common-lib` involves (database, `api-routes.xml`) before going on.
4. **Write the code** in the order of section 1 of the conventions: contract, server, client. Copy the templates and
   rename; keep their Javadoc and comment style (`CLAUDE.md`, "Quy ước comment"). Reuse what exists: one
   `GrpcClientConfig` per calling service (add an `@ImportGrpcClients` line), dependencies only once per pom.
5. **Update the documents** listed in section 5 of the conventions, in the same change.
6. **Verify** as in section 6 of the conventions:
   - `./mvnw clean install` from the repository root must pass. If `GrpcConventionsTest` fails, fix the code it names;
     never edit `GrpcConventions`, `GrpcConventionsTest` or `ArchitectureDocTest` to make the build pass.
   - Start the server service and call every case of the rpc comments with grpcurl (`-import-path` / `-proto`, the
     server has no reflection). Download grpcurl / grpc_health_probe to `/tmp` if they are not installed. Stop every
     service you started.
7. **Report**: files added and changed, test counts per module, the grpcurl results, and anything left undone.

## Do not

- Write an HTTP `/internal/**` endpoint instead of gRPC.
- Set `spring.grpc.server.address` or `spring.grpc.server.reflection.enabled` in `application.properties`.
- Return protobuf classes from `XxxGrpcClient`, map gRPC statuses by hand, or call `onError` in `XxxGrpcService`.
- Edit a committed `.proto` field number or type, or a committed Flyway migration.
- Commit, unless the user asks.
