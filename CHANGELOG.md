# Change Log
All notable changes to this project will be documented in this file. This change log follows the conventions of [keepachangelog.com](http://keepachangelog.com/).

## [Unreleased]
### Changed
- Add a new arity to `make-widget-async` to provide a different widget shape.

## [1.0.105] - 2025-03-18
### Changed
- Internals change: Created Clojure specs for the entire MCP specification
  - The SDK stubs out all the request and notification methods that it does not currently support
  - Improves the error reporting of servers built on top of `mcp-clojure-sdk`
- Bumped version of `examples` jar to `1.2.0` to highlight improved internals

### Removed

### Fixed

## 1.0.65 - 2025-03-16
### Added
- `stdio_server` implementation of MCP
- `examples` folder shows `tools` and `prompts` based servers

[Unreleased]: https://github.com/io.modelcontext/clojure-sdk/compare/fb947ebc8dd59fc778b886d832850f38974cbdc6...HEAD
[1.1.105]: https://github.com/io.modelcontext/clojure-sdk/compare/e0e410ee115256362d964df1272ea42428bf9a21...fb947ebc8dd59fc778b886d832850f38974cbdc6
