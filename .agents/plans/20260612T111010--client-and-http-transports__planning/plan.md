# Plan: Complete MCP client + remaining protocol surface + Streamable HTTP transport

## Current state (verified 2026-06-12)

- `make test` green: 31 tests, 135 assertions, 0 failures.
- Server (stdio) works: `server.clj`, `stdio_server.clj`, `io_chan.clj` (newline-delimited JSON framing, NOT LSP Content-Length).
- Client layer is ~90% done already (`client.clj`, `stdio_client.clj`), with unit tests
  (`client_test.clj` exercises in-memory client<->server over crossed channels) and a
  clojure-lsp-style integration harness (`integration-test/`, mock raw client implementing
  `jsonrpc4clj.protocols.endpoint/IEndpoint`).
- jsonrpc4clj is symmetric (no separate client abstraction); client reuses
  `jsonrpc4clj.server/chan-server`. Wire framing decided by OUR `io-chan`, not jsonrpc4clj's.

## Pending work

### Gap analysis vs doc/specification-schema.ts (DRAFT-2025-v2)

Server stubs returning `::jsonrpc.server/method-not-found` in `server.clj`:
- `resources/templates/list` — no template registry exists
- `resources/subscribe` / `resources/unsubscribe` — no subscription tracking
- `logging/setLevel` — no log-level state
- `completion/complete` — no completion handler registry
- `notifications/cancelled`, `notifications/progress` — unhandled
- No server->client senders: list_changed notifications (tools/resources/prompts),
  `notifications/resources/updated`, `notifications/message` (logging),
  `notifications/progress`, `sampling/createMessage` request, `roots/list` request.

Client gaps:
- No `notify-progress!` sender.
- No roots mutation helpers (`add-root!`/`remove-root!` + list_changed notify).
- `ping` handler defined identically in BOTH `server.clj` and `client.clj` (global
  multimethod — last-loaded wins; identical bodies so harmless but a wart).
- `ping` returns "pong" (string) — schema `Result` must be an object; should be `{}`.

Transports:
- Only stdio. Spec defines stdio + Streamable HTTP (POST + SSE on a single endpoint,
  `Mcp-Session-Id` header). Old HTTP+SSE (two-endpoint) is deprecated; we implement
  Streamable HTTP only.
- JSON-RPC batching (in DRAFT-2025-v2 schema): SKIPPED — removed again in the 2025-06-18
  spec, and jsonrpc4clj's chan-server processes single messages. Documented as a
  non-goal.

## Decisions

- HTTP server: Pedestal + Jetty (mandated by CONVENTIONS.md). SSE via io.pedestal.http.sse.
- HTTP client: java.net.http (JDK built-in) was considered; CONVENTIONS.md mandates
  clj-http — use clj-http with `:as :stream` for SSE.
- Sessions: one `chan-server` per HTTP session sharing the SAME server context
  (tools/resources/prompts atoms), created on `initialize` POST; `Mcp-Session-Id`
  response header; DELETE terminates.
- POST routing: a router thread reads the session's output-ch; responses matching a
  POSTed request id resolve that POST's promise (returned as application/json);
  everything else (server-initiated requests/notifications) goes to the GET SSE stream
  (dropped with a debug log if no stream is open).

## Tasks (each = own jj change, conventional commit)

1. `feat(server)`: complete server protocol surface
   - context: + `:resource-templates` `:subscriptions` `:log-level` `:completions` atoms
   - `register-resource-template!`, `register-completion!`
   - implement the 5 request stubs + 2 notification stubs
   - notification senders: `notify-resources-list-changed!`, `notify-resource-updated!`,
     `notify-tools-list-changed!`, `notify-prompts-list-changed!`, `notify-log-message!`
     (respects setLevel), `notify-progress!`
   - request helpers: `request-sampling!`, `request-roots!`
   - capabilities advertise: resources {subscribe,listChanged}, tools/prompts
     {listChanged}, logging {}, completions {}
   - fix `ping` result "pong" -> {} (schema compliance); dedupe ping defmethod
2. `feat(client)`: client gaps — `notify-progress!`, `add-root!`/`remove-root!`
   (auto list_changed notify), expose `request-timeout` plumbing already present.
3. `feat(http-transport)`: Streamable HTTP server (`http_server.clj`) + client
   (`http_client.clj`) + deps (pedestal, clj-http) + end-to-end test over localhost.
4. `docs`: README client + transports sections, CHANGELOG, todo.org state flips.

## Workflow per task

Main agent writes failing tests first -> delegate implementation to worker subagent
(subagents never touch tests) -> review -> `make format && make check && make test`
-> `jj desc` -> `jj new`.
