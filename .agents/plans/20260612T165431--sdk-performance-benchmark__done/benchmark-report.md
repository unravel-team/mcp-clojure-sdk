# mcp-java-sdk vs mcp-cljc-sdk: stdio performance comparison

Date: 2026-06-12. Harness: bench/ (run with `make bench`). Same raw
newline-JSON client drives all servers; identical echo/bigecho/sleepy
tools; java side mirrors clojure-mcp's interop verbatim.
Raw data: 1781264626522.edn (this folder).

## Results (final run)

| benchmark | java-sdk 1.1.3 | cljc-sdk (source) | cljc-sdk (AOT) |
|---|---|---|---|
| startup, median of 5 | 893ms | 3012ms | **977ms** |
| ping RTT p50/p99 | 0.128/0.369ms | 0.168/0.515ms | 0.174/0.577ms |
| echo 24B p50/p99 | 0.097/0.505ms | 0.225/0.888ms | 0.204/0.700ms |
| bigecho 64KB p50/p99 | 0.476/3.045ms | 0.477/2.589ms | 0.509/0.765ms |
| 100x20ms pipelined | **LOST 299/300 responses** | 34.2ms, 0 lost | 34.8ms, 0 lost |

## Findings

1. CORRECTNESS, not speed, is the java-sdk's problem under concurrency:
   when >=2 async tool completions race, StdioServerTransportProvider
   silently drops responses. Root cause (mcp-core .../transport/
   StdioServerTransportProvider.java:158): `outboundSink.tryEmitNext`
   on a unicast Sinks.Many is not safe for concurrent emission ->
   FAIL_NON_SERIALIZED -> Mono.error("Failed to enqueue message") ->
   swallowed by clojure-mcp's fire-and-forget `.subscribe()`. At depth
   100 essentially every response is lost. clojure-mcp inherits this:
   parallel tool calls completing on nrepl callback threads can lose
   responses. Our SDK: 0 lost at depth 100, 34ms wall (~20ms sleep +
   pool ramp), thanks to jsonrpc4clj's single writer thread draining
   output-ch.
2. Startup gap was compile-at-load, not architecture: core.async alone
   costs ~1.6s of the ~2.1s delta. AOT compilation closes it: 3012ms ->
   977ms, within 9% of java-sdk. Action for the clojure-mcp port: AOT
   the server entrypoint (or ship an AOT-compiled SDK artifact).
3. Round-trip latency is competitive everywhere: worst case is echo
   (+0.1ms absolute, ~2x relative) attributable to spec conform on
   request AND response per call; irrelevant next to real tool work
   (nREPL evals, file IO). 64KB payloads are at parity; AOT run shows
   the best tail latencies of all three (p99 0.765ms).

## Verdict

Performance is on par where it matters (latency, large payloads,
startup with AOT) and strictly better on concurrent correctness, where
the baseline silently drops responses. Recommended next step: proceed
with the clojure-mcp port (stdio + Streamable HTTP), AOT-compiling the
entrypoint. Optional micro-opt if ever needed: a toggle to skip spec
conform in hot paths (~0.1ms/call).
