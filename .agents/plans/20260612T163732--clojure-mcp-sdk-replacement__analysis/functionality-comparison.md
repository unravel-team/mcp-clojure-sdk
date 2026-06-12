# clojure-mcp: mcp-java-sdk -> mcp-cljc-sdk functionality comparison

Analysis date: 2026-06-12. Sources:
- clojure-mcp: ~/src/bhauman/clojure-mcp/clojure-mcp.root (java-sdk 1.1.3)
- mcp-java-sdk: ~/src/modelcontextprotocol/java-sdk/java-sdk.root
- ours: this repo (post client + HTTP transport work)

## Integration surface in clojure-mcp

Only 3 files touch the java SDK (~350 lines of interop):
- `core.clj` — stdio server, tool/prompt/resource registration, result adaptation
- `sse_core.clj` — LEGACY HTTP+SSE transport (HttpServletSseServerTransportProvider
  + Jetty servlet, message endpoint /mcp/message). NOT Streamable HTTP.
- `file_content.clj` — content construction: TextContent, ImageContent,
  EmbeddedResource wrapping Text/BlobResourceContents (Tika mime detection is
  SDK-independent).

All ~25 tools/prompts/resources are SDK-agnostic factory maps; handlers use
continuation-passing (clj-result-k) and never touch SDK types directly.

## java-sdk features actually used

1. McpServer/async over StdioServerTransportProvider (Reactor Mono handlers).
2. Legacy HTTP+SSE servlet transport on Jetty (`:mcp-sse` alias, port 8078).
3. ServerCapabilities: tools(listChanged), prompts(listChanged),
   resources(subscribe=true, listChanged=true). logging commented out.
4. Tool: builder w/ name/description/inputSchema(JSON string)/annotations;
   AsyncToolSpecification; handlers return Mono (async, concurrent).
5. Live add/removeTool|Resource|Prompt on running server (idempotent
   re-register; java-sdk auto-sends list_changed notifications).
6. ToolAnnotations(title, readOnly, destructive, idempotent, openWorld,
   returnDirect[non-standard]).
7. Content in tool results: TextContent | ImageContent(b64, mime) |
   EmbeddedResource(TextResourceContents | BlobResourceContents).
8. Prompts: PromptArgument, GetPromptResult, roles user/assistant.
9. Resources: builder, ReadResourceResult w/ TextResourceContents.
10. closeGracefully.

## java-sdk features NOT used by clojure-mcp

exchange object (sampling, roots, server->client ping) — always ignored;
logging notifications; progress; completions; resource templates;
subscription updates (capability advertised, never sent); pagination;
structured tool output.

## Parity matrix (needed-by-clojure-mcp vs ours)

| Capability | Ours | Verdict |
|---|---|---|
| stdio transport | yes | OK |
| Async/concurrent tool handlers | NO — handlers sync on single message loop | GAP 1 (critical: long evals/bash block ping + parallel tool calls) — jsonrpc4clj already supports future-returning handlers; our server.clj never returns futures |
| Live registration | register-*! works anytime (atoms) | OK |
| Unregistration | none | GAP 2a (small) |
| Auto list_changed on add/remove | manual notify-*-list-changed! only | GAP 2b (small) |
| Legacy HTTP+SSE transport | no (we have Streamable HTTP) | GAP 3 (decision: implement legacy SSE for drop-in parity, or port clojure-mcp's :mcp-sse mode to Streamable HTTP) |
| Tool annotations | pass through (open s/keys) but not in ::tool spec | GAP 4 (small: add :opt-un validation; returnDirect is java-sdk extension) |
| Capabilities configurability | hardcoded default in create-empty-context | GAP 5 (small: accept :capabilities in spec) |
| text/image/embedded-resource content | specs support all; plain data maps | OK (simpler than java classes) |
| Prompts w/ roles + args | yes | OK |
| Resources + ReadResourceResult | yes | OK |
| Graceful shutdown | jsonrpc.server/shutdown | OK |
| Protocol versions | 2025-03-26, 2024-11-05 (java-sdk 1.1.3 also 2025-06-18) | GAP 6 (trivial: version list) |

Ours-but-not-needed extras: completions, resource templates, logging senders,
sampling/roots, subscriptions, structured content, Streamable HTTP, full spec
validation, MCP client.

## Proposed implementation order (pending go-ahead)

1. Async handler support (GAP 1) — biggest functional + perf item.
2. unregister-*! + opt-in auto list_changed (GAP 2).
3. Transport decision (GAP 3) — recommend porting clojure-mcp to Streamable
   HTTP unless legacy-SSE clients must keep working.
4. Small spec/config items (GAPS 4-6).

## Performance comparison plan (after functionality go-ahead)

Benchmark stdio round-trips (ping, tools/list, tools/call echo, large
payloads), startup time, and concurrent in-flight requests; java-sdk
baseline via clojure-mcp as-is vs a port branch on our SDK.
