import asyncio
import base64
import io
import time
from pathlib import Path

from PIL import Image, ImageDraw

from screen_mcp.display import get_env, get_screen_size as _get_screen_size

SCREENSHOT_PATH = Path("/tmp/screenshot.png")

# Recent actions for overlay annotations (timestamp, label, x, y)
_action_log: list[tuple[float, str, int, int]] = []
ACTION_TTL = 5.0  # show markers for actions within the last 5 seconds


def _record_action(label: str, x: int, y: int) -> None:
    now = time.monotonic()
    _action_log.append((now, label, x, y))
    # prune old entries
    cutoff = now - ACTION_TTL
    while _action_log and _action_log[0][0] < cutoff:
        _action_log.pop(0)


async def _run(cmd: list[str]) -> str:
    proc = await asyncio.create_subprocess_exec(
        *cmd,
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        env=get_env(),
    )
    stdout, stderr = await proc.communicate()
    if proc.returncode != 0:
        raise RuntimeError(
            f"Command {cmd} failed (rc={proc.returncode}): {stderr.decode()}"
        )
    return stdout.decode()


def _annotate_screenshot(img_bytes: bytes) -> bytes:
    """Draw red crosshairs + labels on the screenshot for recent actions."""
    now = time.monotonic()
    cutoff = now - ACTION_TTL
    recent = [(t, label, x, y) for t, label, x, y in _action_log if t >= cutoff]
    if not recent:
        return img_bytes

    img = Image.open(io.BytesIO(img_bytes))
    draw = ImageDraw.Draw(img)
    for i, (t, label, x, y) in enumerate(recent):
        age = now - t
        # Fade opacity: 255 when fresh, 80 when about to expire
        alpha = int(255 - (175 * age / ACTION_TTL))
        color = (255, 0, 0, max(alpha, 80))
        r = 16
        # Crosshair
        draw.line([(x - r, y), (x + r, y)], fill=color[:3], width=2)
        draw.line([(x, y - r), (x, y + r)], fill=color[:3], width=2)
        # Circle
        draw.ellipse(
            [(x - r, y - r), (x + r, y + r)], outline=color[:3], width=2,
        )
        # Label
        draw.text((x + r + 4, y - 8), label, fill=color[:3])

    buf = io.BytesIO()
    img.save(buf, format="PNG")
    return buf.getvalue()


async def screenshot() -> str:
    await _run(["scrot", "-o", str(SCREENSHOT_PATH)])
    raw = SCREENSHOT_PATH.read_bytes()
    annotated = _annotate_screenshot(raw)
    return base64.standard_b64encode(annotated).decode()


async def click(x: int, y: int, button: int = 1) -> str:
    await _run(["xdotool", "mousemove", str(x), str(y), "click", str(button)])
    _record_action(f"click(b{button})", x, y)
    return f"Clicked at ({x}, {y}) with button {button}"


async def double_click(x: int, y: int) -> str:
    await _run([
        "xdotool", "mousemove", str(x), str(y), "click", "--repeat", "2", "1",
    ])
    _record_action("dblclick", x, y)
    return f"Double-clicked at ({x}, {y})"


async def type_text(text: str) -> str:
    await _run(["xdotool", "type", "--clearmodifiers", text])
    return f"Typed {len(text)} characters"


async def key(combo: str) -> str:
    await _run(["xdotool", "key", combo])
    return f"Pressed key: {combo}"


async def scroll(x: int, y: int, direction: str, amount: int = 3) -> str:
    await _run(["xdotool", "mousemove", str(x), str(y)])
    # button 4 = scroll up, button 5 = scroll down
    button = "4" if direction == "up" else "5"
    await _run(["xdotool", "click", "--repeat", str(amount), button])
    _record_action(f"scroll({direction})", x, y)
    return f"Scrolled {direction} {amount} clicks at ({x}, {y})"


async def wait(seconds: float = 1) -> str:
    clamped = min(seconds, 30)
    await asyncio.sleep(clamped)
    return f"Waited {clamped} seconds"


async def open_url(url: str) -> str:
    # Find the Chromium window ID
    output = await _run([
        "xdotool", "search", "--onlyvisible", "--class", "chromium",
    ])
    window_ids = output.strip().splitlines()
    if not window_ids:
        raise RuntimeError("No Chromium window found")
    wid = window_ids[0]

    # Focus and activate it
    await _run(["xdotool", "windowactivate", "--sync", wid])
    await asyncio.sleep(0.3)

    # Focus address bar, clear, type URL, navigate
    await _run(["xdotool", "key", "ctrl+l"])
    await asyncio.sleep(0.2)
    await _run(["xdotool", "key", "ctrl+a"])
    await asyncio.sleep(0.1)
    await _run(["xdotool", "type", "--clearmodifiers", url])
    await asyncio.sleep(0.1)
    await _run(["xdotool", "key", "Return"])
    return f"Navigated to {url}"


async def cursor_position() -> dict[str, int]:
    output = await _run(["xdotool", "getmouselocation"])
    # output like: x:640 y:512 screen:0 window:12345
    parts = {}
    for token in output.split():
        if ":" in token:
            k, v = token.split(":", 1)
            if k in ("x", "y"):
                parts[k] = int(v)
    return parts


async def get_screen_size() -> dict[str, int]:
    w, h = await _get_screen_size()
    return {"width": w, "height": h}
