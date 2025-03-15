# examples

1. Calculator
2. Code Analysis
3. Vega-lite

## Building the Jar

    $ make build ## or clojure -T:build ci

## Running the MCP Server

### In Claude Desktop

```json
    "calculator": {
      "command": "java",
      "args": [
        "-Dclojure.tools.logging.factory=clojure.tools.logging.impl/log4j2-factory",
        "-Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog",
        "-Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector",
        "-Dlog4j2.configurationFile=log4j2-mcp.xml",
        "-Dbabashka.json.provider=metosin/jsonista",
        "-Dlogging.level=INFO",
        "-Dlog4j2.statusLoggerLevel=OFF",
        "-cp",
        "<full-path>/examples/target/io.modelcontextprotocol.clojure-sdk/examples-1.1.0.jar",
        "calculator_server"
      ]
    }
```

### In MCP Inspector

```shell
npx @modelcontextprotocol/inspector java -Dclojure.tools.logging.factory=clojure.tools.logging.impl/log4j2-factory -Dorg.eclipse.jetty.util.log.class=org.eclipse.jetty.util.log.Slf4jLog -Dlog4j2.contextSelector=org.apache.logging.log4j.core.async.AsyncLoggerContextSelector -Dlog4j2.configurationFile=log4j2-mcp.xml -Dbabashka.json.provider=metosin/jsonista -Dlogging.level=INFO -cp examples/target/io.modelcontextprotocol.clojure-sdk/examples-1.1.0.jar calculator_server
```

## License

Copyright © 2025 Unravel.tech

Distributed under the MIT License.
