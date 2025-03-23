# io.modelcontext/clojure-sdk

A `clojure-sdk` for creating Model Context Protocol servers!

## Usage

The [calculator_server.clj file](examples/src/calculator_server.clj)
and [vegalite_server.clj file](examples/src/vegalite_server.clj)
examples contains a full working code for defining an MCP server.

`examples` is a `deps-new` app project, and instructions for compiling
and running the various example servers are in [the examples/README.md
file](examples/README.md) (also copied below this section)

The deps for `clojure-sdk` are:

```clojure
{io.modelcontextprotocol/mcp-clojure-sdk
 {:git/url "https://github.com/unravel-team/mcp-clojure-sdk.git"
  :git/sha "039cf220ac6bb3858f71e823016035e257a5380d"}}
```

<!-- Add the complete Examples README information here. Indent the headings properly. //ai! -->
## Pending Work

You can help dear reader! Head over to the [todo.org file](todo.org)
to see the list of pending changes, arranged roughly in the order I
plan to tackle them.

## Development of the SDK

The `clojure-sdk` is a standard `deps-new` project, so you should
expect all the `deps-new` commands to work as expected. Even so:

Run the project's tests:

    $ make test ## or clojure -T:build test

Run the project's CI pipeline and build a JAR:

    $ make build ## or clojure -T:build ci

This will produce an updated `pom.xml` file with synchronized
dependencies inside the `META-INF` directory inside `target/classes`
and the JAR in `target`. You can update the version (and SCM tag)
information in generated `pom.xml` by updating `build.clj`.

Install it locally:

    $ make install ## or clojure -T:build install

Deploy it to Clojars -- needs `CLOJARS_USERNAME` and
`CLOJARS_PASSWORD` environment variables (requires the `ci` task be
run first):

    $ make deploy ## or clojure -T:build deploy

Your library will be deployed to io.modelcontext/clojure-sdk on
clojars.org by default.

## Inspiration

This SDK is built on top of
[lsp4clj](https://github.com/clojure-lsp/lsp4clj), which solves the
hard part of handling all the edge-cases of a JSON-RPC based server. I
built this layer by hand and discovered all the edge-cases before
realising that `lsp4clj` was the smarter approach. The code is super
well written and easy to modify for my requirements.

## License

Copyright © 2025 Unravel.tech

Distributed under the MIT License
