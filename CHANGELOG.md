# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased
### Added
- Add `mcp-thoughtful-prompts` as an example prompt server in the README.
- Complete the server-side MCP protocol surface: `resources/templates/list`,
  `resources/subscribe`/`unsubscribe`, `logging/setLevel`,
  `completion/complete`, and the `notifications/cancelled`/`progress`
  receivers. New registration fns: `register-resource-template!`,
  `register-completion!`.
- Server -> client senders: `notify-tools-list-changed!`,
  `notify-resources-list-changed!`, `notify-prompts-list-changed!`,
  `notify-resource-updated!` (subscription-gated), `notify-log-message!`
  (respects the level set via `logging/setLevel`), `notify-progress!`,
  `request-roots!` and `request-sampling!`.
- Client additions: `notify-progress!`, `add-root!`/`remove-root!` (send
  `notifications/roots/list_changed` automatically).
- Streamable HTTP transport (spec revision 2025-03-26): `http_server.clj`
  (Pedestal + Jetty, single `/mcp` endpoint, `Mcp-Session-Id` sessions,
  SSE stream for server-initiated messages) and `http_client.clj`
  (clj-http based, mirrors the stdio client API). JSON-RPC batching is
  intentionally unsupported (removed in the 2025-06-18 spec revision).
- Public JSON helpers in `io-chan`: `message->json-str` /
  `json-str->message`, shared by the stdio and HTTP transports.

### Changed
- Update all the examples to use the new API.
- Servers now advertise their full default capabilities (tools/prompts
  `listChanged`, resources `subscribe`+`listChanged`, `logging`,
  `completions`).
- `ping` now returns `{}` instead of `"pong"` (the schema requires a
  Result object).
- New dependencies: `io.pedestal/pedestal.service` + `pedestal.jetty`
  (0.8.1, kept in sync with the logger's `pedestal.log`) and
  `clj-http/clj-http`.

### Fixed
- `io-chan` no longer crashes the reader thread at stream EOF when the
  JSON provider returns nil instead of throwing (this also prevented
  stdio servers from shutting down when their client closed stdin).

## [1.1.147] - 2025-06-07
### Added
- Add `client.clj` and `stdio-client.clj` with helper functions for implementing STDIO-based MCP clients.
- Add an example client for integration testing in `integration.client`

### Changed
- Move `examples` code and jar to `integration-test`, these servers are now used for client/server integration testing in the SDK
- Use ideas from `clojure-mcp` and improve the API. Someone else thinking about the API for tools, prompts and resources is exactly what I was looking for

## [1.0.105] - 2025-03-18
### Changed
- Internals change: Create Clojure specs for the entire MCP specification
  - The SDK stubs out all the request and notification methods that it does not currently support
  - Improves the error reporting of servers built on top of `mcp-clojure-sdk`
- Bump version of `examples` jar to `1.2.0` to highlight improved internals

### Removed

### Fixed

## 1.0.65 - 2025-03-16
### Added
- `stdio_server` implementation of MCP
- `examples` folder shows `tools` and `prompts` based servers

[Unreleased]: https://github.com/io.modelcontext/clojure-sdk/compare/fb947ebc8dd59fc778b886d832850f38974cbdc6...HEAD
[1.1.147]: https://github.com/io.modelcontext/clojure-sdk/compare/fb947ebc8dd59fc778b886d832850f38974cbdc6...HEAD
[1.0.105]: https://github.com/io.modelcontext/clojure-sdk/compare/e0e410ee115256362d964df1272ea42428bf9a21...fb947ebc8dd59fc778b886d832850f38974cbdc6
