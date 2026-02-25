import asyncio
import os
import re


def get_display() -> str:
    return os.environ.get("DISPLAY", ":99")


def get_env() -> dict[str, str]:
    env = os.environ.copy()
    env["DISPLAY"] = get_display()
    return env


async def get_screen_size() -> tuple[int, int]:
    proc = await asyncio.create_subprocess_exec(
        "xdpyinfo", "-display", get_display(),
        stdout=asyncio.subprocess.PIPE,
        stderr=asyncio.subprocess.PIPE,
        env=get_env(),
    )
    stdout, _ = await proc.communicate()
    output = stdout.decode()
    match = re.search(r"dimensions:\s+(\d+)x(\d+)", output)
    if not match:
        raise RuntimeError("Could not determine screen size from xdpyinfo")
    return int(match.group(1)), int(match.group(2))
