#!/usr/bin/env node
/*
 * Minimal MCP server for integration testing.
 * Implements tools, resources, and prompts.
 *
 * Uses newline-delimited JSON (MCP standard).
 */

import * as readline from "node:readline";
import { stdin as input, stdout as output } from "node:process";

type JsonRpcId = number | string | null;

interface JsonRpcMessage {
  id?: JsonRpcId;
  method?: string;
  params?: Record<string, unknown>;
}

const writeMessage = (message: Record<string, unknown>): void => {
  output.write(`${JSON.stringify(message)}\n`);
};

const handleInitialize = (id: JsonRpcId): Record<string, unknown> => ({
  jsonrpc: "2.0",
  id,
  result: {
    protocolVersion: "2025-03-26",
    capabilities: { tools: {}, resources: {}, prompts: {} },
    serverInfo: { name: "typescript-echo-server", version: "1.0.0" },
  },
});

const handlePing = (id: JsonRpcId): Record<string, unknown> => ({
  jsonrpc: "2.0",
  id,
  result: {},
});

const handleToolsList = (id: JsonRpcId): Record<string, unknown> => ({
  jsonrpc: "2.0",
  id,
  result: {
    tools: [
      {
        name: "echo",
        description: "Echo back the input message",
        inputSchema: {
          type: "object",
          properties: {
            message: { type: "string", description: "Message to echo" },
          },
          required: ["message"],
        },
      },
      {
        name: "add",
        description: "Add two numbers",
        inputSchema: {
          type: "object",
          properties: { a: { type: "number" }, b: { type: "number" } },
          required: ["a", "b"],
        },
      },
      {
        name: "fail",
        description: "A tool that always fails (for testing)",
        inputSchema: { type: "object", properties: {} },
      },
    ],
  },
});

const handleToolsCall = (
  id: JsonRpcId,
  params: Record<string, unknown>,
): Record<string, unknown> => {
  const name = params.name as string | undefined;
  const args = (params.arguments as Record<string, unknown>) || {};

  if (name === "echo") {
    return {
      jsonrpc: "2.0",
      id,
      result: { content: [{ type: "text", text: (args.message as string) || "" }] },
    };
  }

  if (name === "add") {
    const a = Number(args.a ?? 0);
    const b = Number(args.b ?? 0);
    return {
      jsonrpc: "2.0",
      id,
      result: { content: [{ type: "text", text: String(a + b) }] },
    };
  }

  if (name === "fail") {
    return {
      jsonrpc: "2.0",
      id,
      result: {
        content: [{ type: "text", text: "This tool always fails" }],
        isError: true,
      },
    };
  }

  return {
    jsonrpc: "2.0",
    id,
    error: { code: -32601, message: `Tool not found: ${name}` },
  };
};

const handleResourcesList = (id: JsonRpcId): Record<string, unknown> => ({
  jsonrpc: "2.0",
  id,
  result: {
    resources: [
      {
        uri: "test://greeting",
        name: "Greeting",
        description: "A simple greeting resource",
        mimeType: "text/plain",
      },
      {
        uri: "test://data",
        name: "Test Data",
        description: "Some test data",
        mimeType: "application/json",
      },
    ],
  },
});

const handleResourcesRead = (
  id: JsonRpcId,
  params: Record<string, unknown>,
): Record<string, unknown> => {
  const uri = params.uri as string | undefined;

  if (uri === "test://greeting") {
    return {
      jsonrpc: "2.0",
      id,
      result: {
        contents: [
          {
            uri,
            mimeType: "text/plain",
            text: "Hello from TypeScript MCP server!",
          },
        ],
      },
    };
  }

  if (uri === "test://data") {
    return {
      jsonrpc: "2.0",
      id,
      result: {
        contents: [
          {
            uri,
            mimeType: "application/json",
            text: '{"key": "value", "number": 42}',
          },
        ],
      },
    };
  }

  return {
    jsonrpc: "2.0",
    id,
    error: { code: -32601, message: `Resource not found: ${uri}` },
  };
};

const handlePromptsList = (id: JsonRpcId): Record<string, unknown> => ({
  jsonrpc: "2.0",
  id,
  result: {
    prompts: [
      {
        name: "greeting",
        description: "Generate a personalized greeting",
        arguments: [
          { name: "name", description: "Name to greet", required: true },
        ],
      },
      {
        name: "summarize",
        description: "Summarize text",
        arguments: [
          { name: "text", description: "Text to summarize", required: true },
          { name: "style", description: "Summary style", required: false },
        ],
      },
    ],
  },
});

const handlePromptsGet = (
  id: JsonRpcId,
  params: Record<string, unknown>,
): Record<string, unknown> => {
  const name = params.name as string | undefined;
  const args = (params.arguments as Record<string, unknown>) || {};

  if (name === "greeting") {
    const target = (args.name as string) || "World";
    return {
      jsonrpc: "2.0",
      id,
      result: {
        description: "A greeting prompt",
        messages: [
          {
            role: "user",
            content: {
              type: "text",
              text: `Please greet ${target} warmly.`,
            },
          },
        ],
      },
    };
  }

  if (name === "summarize") {
    const text = (args.text as string) || "";
    const style = (args.style as string) || "concise";
    return {
      jsonrpc: "2.0",
      id,
      result: {
        description: "A summarization prompt",
        messages: [
          {
            role: "user",
            content: {
              type: "text",
              text: `Please summarize the following text in a ${style} style:\n\n${text}`,
            },
          },
        ],
      },
    };
  }

  return {
    jsonrpc: "2.0",
    id,
    error: { code: -32601, message: `Prompt not found: ${name}` },
  };
};

const handlers: Record<
  string,
  (id: JsonRpcId, params: Record<string, unknown>) => Record<string, unknown>
> = {
  initialize: (_id, _params) => handleInitialize(_id),
  ping: (_id, _params) => handlePing(_id),
  "tools/list": (_id, _params) => handleToolsList(_id),
  "tools/call": handleToolsCall,
  "resources/list": (_id, _params) => handleResourcesList(_id),
  "resources/read": handleResourcesRead,
  "prompts/list": (_id, _params) => handlePromptsList(_id),
  "prompts/get": handlePromptsGet,
};

const rl = readline.createInterface({ input });

rl.on("line", (line: string) => {
  if (!line.trim()) {
    return;
  }

  let message: JsonRpcMessage;
  try {
    message = JSON.parse(line) as JsonRpcMessage;
  } catch {
    return;
  }

  const method = message.method ?? "";
  const requestId = message.id ?? null;
  const params = (message.params as Record<string, unknown>) || {};

  if (requestId === null || requestId === undefined) {
    return;
  }

  const handler = handlers[method];
  if (handler) {
    writeMessage(handler(requestId, params));
  } else {
    writeMessage({
      jsonrpc: "2.0",
      id: requestId,
      error: { code: -32601, message: `Method not found: ${method}` },
    });
  }
});
