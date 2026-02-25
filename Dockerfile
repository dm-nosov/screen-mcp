FROM python:3.12-slim

RUN apt-get update && apt-get install -y --no-install-recommends \
    xvfb xdotool scrot chromium x11-utils \
    x11vnc novnc websockify openbox \
    && rm -rf /var/lib/apt/lists/*

WORKDIR /app
COPY pyproject.toml ./
RUN pip install --no-cache-dir .

COPY screen_mcp/ screen_mcp/
COPY entrypoint.sh .
RUN chmod +x entrypoint.sh

EXPOSE 8075 6080
ENTRYPOINT ["./entrypoint.sh"]
