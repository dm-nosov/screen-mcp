import argparse
import json

import uvicorn
from mcp.server import Server
from mcp.server.sse import SseServerTransport
from mcp.types import ImageContent, TextContent, Tool
from starlette.applications import Starlette
from starlette.routing import Mount, Route

from screen_mcp import tools

server = Server("screen-mcp")

TOOL_DEFINITIONS: list[Tool] = [
    Tool(
        name="screenshot",
        description="Take a screenshot of the current screen. Returns a base64-encoded PNG image.",
        inputSchema={"type": "object", "properties": {}, "required": []},
    ),
    Tool(
        name="click",
        description="Click at the given screen coordinates.",
        inputSchema={
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate"},
                "y": {"type": "integer", "description": "Y coordinate"},
                "button": {
                    "type": "integer",
                    "description": "Mouse button (1=left, 2=middle, 3=right)",
                    "default": 1,
                },
            },
            "required": ["x", "y"],
        },
    ),
    Tool(
        name="double_click",
        description="Double-click at the given screen coordinates.",
        inputSchema={
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate"},
                "y": {"type": "integer", "description": "Y coordinate"},
            },
            "required": ["x", "y"],
        },
    ),
    Tool(
        name="type_text",
        description="Type text at the current cursor position.",
        inputSchema={
            "type": "object",
            "properties": {
                "text": {"type": "string", "description": "Text to type"},
            },
            "required": ["text"],
        },
    ),
    Tool(
        name="key",
        description="Press a key or key combination (xdotool key names, e.g. Return, Tab, ctrl+a, alt+F4).",
        inputSchema={
            "type": "object",
            "properties": {
                "combo": {
                    "type": "string",
                    "description": "Key combo in xdotool format",
                },
            },
            "required": ["combo"],
        },
    ),
    Tool(
        name="scroll",
        description="Scroll at the given screen coordinates.",
        inputSchema={
            "type": "object",
            "properties": {
                "x": {"type": "integer", "description": "X coordinate"},
                "y": {"type": "integer", "description": "Y coordinate"},
                "direction": {
                    "type": "string",
                    "enum": ["up", "down"],
                    "description": "Scroll direction",
                },
                "amount": {
                    "type": "integer",
                    "description": "Number of scroll clicks",
                    "default": 3,
                },
            },
            "required": ["x", "y", "direction"],
        },
    ),
    Tool(
        name="wait",
        description="Wait for a specified number of seconds (max 30).",
        inputSchema={
            "type": "object",
            "properties": {
                "seconds": {
                    "type": "number",
                    "description": "Seconds to wait",
                    "default": 1,
                },
            },
            "required": [],
        },
    ),
    Tool(
        name="open_url",
        description="Navigate the browser to a URL.",
        inputSchema={
            "type": "object",
            "properties": {
                "url": {"type": "string", "description": "URL to navigate to"},
            },
            "required": ["url"],
        },
    ),
    Tool(
        name="cursor_position",
        description="Get the current cursor position.",
        inputSchema={"type": "object", "properties": {}, "required": []},
    ),
    Tool(
        name="get_screen_size",
        description="Get the screen resolution.",
        inputSchema={"type": "object", "properties": {}, "required": []},
    ),
]


@server.list_tools()
async def list_tools() -> list[Tool]:
    return TOOL_DEFINITIONS


@server.call_tool()
async def call_tool(name: str, arguments: dict) -> list[TextContent | ImageContent]:
    match name:
        case "screenshot":
            b64 = await tools.screenshot()
            return [ImageContent(type="image", data=b64, mimeType="image/png")]

        case "click":
            result = await tools.click(
                arguments["x"], arguments["y"], arguments.get("button", 1),
            )
            return [TextContent(type="text", text=result)]

        case "double_click":
            result = await tools.double_click(arguments["x"], arguments["y"])
            return [TextContent(type="text", text=result)]

        case "type_text":
            result = await tools.type_text(arguments["text"])
            return [TextContent(type="text", text=result)]

        case "key":
            result = await tools.key(arguments["combo"])
            return [TextContent(type="text", text=result)]

        case "scroll":
            result = await tools.scroll(
                arguments["x"],
                arguments["y"],
                arguments["direction"],
                arguments.get("amount", 3),
            )
            return [TextContent(type="text", text=result)]

        case "wait":
            result = await tools.wait(arguments.get("seconds", 1))
            return [TextContent(type="text", text=result)]

        case "open_url":
            result = await tools.open_url(arguments["url"])
            return [TextContent(type="text", text=result)]

        case "cursor_position":
            pos = await tools.cursor_position()
            return [TextContent(type="text", text=json.dumps(pos))]

        case "get_screen_size":
            size = await tools.get_screen_size()
            return [TextContent(type="text", text=json.dumps(size))]

        case _:
            raise ValueError(f"Unknown tool: {name}")


def main() -> None:
    parser = argparse.ArgumentParser()
    parser.add_argument("--port", type=int, default=8075)
    args = parser.parse_args()

    sse = SseServerTransport("/messages/")

    async def handle_sse(request):
        async with sse.connect_sse(
            request.scope, request.receive, request._send,
        ) as streams:
            await server.run(
                streams[0], streams[1], server.create_initialization_options(),
            )

    app = Starlette(
        routes=[
            Route("/sse", endpoint=handle_sse),
            Mount("/messages/", app=sse.handle_post_message),
        ],
    )

    uvicorn.run(app, host="0.0.0.0", port=args.port)


if __name__ == "__main__":
    main()
