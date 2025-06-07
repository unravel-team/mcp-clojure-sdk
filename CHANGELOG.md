# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## Unreleased
### Added
- Add `mcp-thoughtful-prompts` as an example prompt server in the README.

### Changed
- Update all the examples to use the new API.

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
