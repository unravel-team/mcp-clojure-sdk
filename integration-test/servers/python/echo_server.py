#!/usr/bin/env python3
"""
Minimal MCP server for integration testing.
Implements tools, resources, and prompts.

Uses newline-delimited JSON (MCP standard).
"""

import json
import sys
from typing import Any, Dict, Optional


def read_message() -> Optional[Dict[str, Any]]:
    """Read a JSON-RPC message from stdin."""
    try:
        line = sys.stdin.readline()
        if not line:
            return None
        return json.loads(line.strip())
    except json.JSONDecodeError:
        return None


def write_message(msg: Dict[str, Any]) -> None:
    """Write a JSON-RPC message to stdout."""
    sys.stdout.write(json.dumps(msg) + "\n")
    sys.stdout.flush()


def handle_initialize(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "result": {
            "protocolVersion": "2025-03-26",
            "capabilities": {"tools": {}, "resources": {}, "prompts": {}},
            "serverInfo": {"name": "python-echo-server", "version": "1.0.0"},
        },
    }


def handle_ping(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    return {"jsonrpc": "2.0", "id": request_id, "result": {}}


def handle_tools_list(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "result": {
            "tools": [
                {
                    "name": "echo",
                    "description": "Echo back the input message",
                    "inputSchema": {
                        "type": "object",
                        "properties": {
                            "message": {"type": "string", "description": "Message to echo"}
                        },
                        "required": ["message"],
                    },
                },
                {
                    "name": "add",
                    "description": "Add two numbers",
                    "inputSchema": {
                        "type": "object",
                        "properties": {"a": {"type": "number"}, "b": {"type": "number"}},
                        "required": ["a", "b"],
                    },
                },
                {
                    "name": "fail",
                    "description": "A tool that always fails (for testing)",
                    "inputSchema": {"type": "object", "properties": {}},
                },
            ]
        },
    }


def handle_tools_call(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    name = params.get("name")
    args = params.get("arguments", {})

    if name == "echo":
        content = [{"type": "text", "text": args.get("message", "")}]
        return {"jsonrpc": "2.0", "id": request_id, "result": {"content": content}}

    if name == "add":
        result = args.get("a", 0) + args.get("b", 0)
        content = [{"type": "text", "text": str(result)}]
        return {"jsonrpc": "2.0", "id": request_id, "result": {"content": content}}

    if name == "fail":
        content = [{"type": "text", "text": "This tool always fails"}]
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {"content": content, "isError": True},
        }

    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {"code": -32601, "message": f"Tool not found: {name}"},
    }


def handle_resources_list(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "result": {
            "resources": [
                {
                    "uri": "test://greeting",
                    "name": "Greeting",
                    "description": "A simple greeting resource",
                    "mimeType": "text/plain",
                },
                {
                    "uri": "test://data",
                    "name": "Test Data",
                    "description": "Some test data",
                    "mimeType": "application/json",
                },
            ]
        },
    }


def handle_resources_read(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    uri = params.get("uri")

    if uri == "test://greeting":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "contents": [
                    {
                        "uri": uri,
                        "mimeType": "text/plain",
                        "text": "Hello from Python MCP server!",
                    }
                ]
            },
        }

    if uri == "test://data":
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "contents": [
                    {
                        "uri": uri,
                        "mimeType": "application/json",
                        "text": '{"key": "value", "number": 42}',
                    }
                ]
            },
        }

    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {"code": -32601, "message": f"Resource not found: {uri}"},
    }


def handle_prompts_list(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "result": {
            "prompts": [
                {
                    "name": "greeting",
                    "description": "Generate a personalized greeting",
                    "arguments": [
                        {"name": "name", "description": "Name to greet", "required": True}
                    ],
                },
                {
                    "name": "summarize",
                    "description": "Summarize text",
                    "arguments": [
                        {
                            "name": "text",
                            "description": "Text to summarize",
                            "required": True,
                        },
                        {
                            "name": "style",
                            "description": "Summary style",
                            "required": False,
                        },
                    ],
                },
            ]
        },
    }


def handle_prompts_get(request_id: Any, params: Dict[str, Any]) -> Dict[str, Any]:
    name = params.get("name")
    args = params.get("arguments", {})

    if name == "greeting":
        target = args.get("name", "World")
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "description": "A greeting prompt",
                "messages": [
                    {
                        "role": "user",
                        "content": {
                            "type": "text",
                            "text": f"Please greet {target} warmly.",
                        },
                    }
                ],
            },
        }

    if name == "summarize":
        text = args.get("text", "")
        style = args.get("style", "concise")
        return {
            "jsonrpc": "2.0",
            "id": request_id,
            "result": {
                "description": "A summarization prompt",
                "messages": [
                    {
                        "role": "user",
                        "content": {
                            "type": "text",
                            "text": "Please summarize the following text in a "
                            f"{style} style:\n\n{text}",
                        },
                    }
                ],
            },
        }

    return {
        "jsonrpc": "2.0",
        "id": request_id,
        "error": {"code": -32601, "message": f"Prompt not found: {name}"},
    }


HANDLERS = {
    "initialize": handle_initialize,
    "ping": handle_ping,
    "tools/list": handle_tools_list,
    "tools/call": handle_tools_call,
    "resources/list": handle_resources_list,
    "resources/read": handle_resources_read,
    "prompts/list": handle_prompts_list,
    "prompts/get": handle_prompts_get,
}


def main() -> None:
    """Main server loop."""
    while True:
        msg = read_message()
        if msg is None:
            break

        method = msg.get("method")
        request_id = msg.get("id")
        params = msg.get("params", {})

        if request_id is None:
            if method == "notifications/initialized":
                continue
            continue

        handler = HANDLERS.get(method)
        if handler:
            response = handler(request_id, params)
            write_message(response)
        else:
            write_message(
                {
                    "jsonrpc": "2.0",
                    "id": request_id,
                    "error": {"code": -32601, "message": f"Method not found: {method}"},
                }
            )


if __name__ == "__main__":
    main()
