# Progress

- [x] Recon: jsonrpc4clj, clojure-lsp, this codebase (baseline `make test` green)
- [x] plan.md written
- [x] Task 1: feat(server) — complete protocol surface (commit dbddbe44)
- [x] Task 2: feat(client) — progress + roots helpers (commit 49d89307)
- [x] Task 3: feat(http-transport) — Streamable HTTP server + client (commit d0514c3d)
- [x] Task 3b: fix(io-chan) — nil-at-EOF crash found via integration run (commit 0f9243aa)
- [x] Task 4: docs — README/CHANGELOG/todo.org

## Verification record
- Unit: 45 tests / 195 assertions green (`make test`), `make check` green.
- stdio integration: `bb integration-test java -cp integration-test/servers/target/io.modelcontextprotocol.clojure-sdk/examples-1.2.0.jar calculator_server`
  → 4 tests / 11 assertions, 0 failures (requires `make servers-jar` first).
- HTTP transport: covered by test/io/modelcontext/clojure_sdk/http_transport_test.clj
  (real Jetty on ephemeral port).

## Notes / follow-ups (not in scope)
- `make test-integration` target is broken independently of this work:
  `-m entrypoint` needs a `-main`, and `bb` swallows `-D...` args as its own
  system properties. Use `bb integration-test <cmd...>` with repo-root-relative
  jar path instead.
- Pedestal 0.8.1 compat API prints one-time deprecation warnings; migrating to
  io.pedestal.connector is a possible follow-up.
- HTTP client v1 does not support servers that answer POSTs with SSE streams.
- Batching: explicit non-goal (removed in 2025-06-18 spec).
