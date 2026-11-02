# Runtime for a Linux IntelliJ-based IDE on a virtual X display (Xvfb). The IDE itself is mounted, not baked in.
FROM ubuntu:24.04

RUN apt-get update && DEBIAN_FRONTEND=noninteractive apt-get install -y --no-install-recommends \
        xvfb xauth \
        libx11-6 libxext6 libxrender1 libxtst6 libxi6 libxrandr2 libxkbcommon0 libxkbcommon-x11-0 \
        libfreetype6 fontconfig fonts-dejavu-core fonts-liberation \
        libglib2.0-0t64 libnss3 libgbm1 libasound2t64 libdbus-1-3 libatk1.0-0t64 libatk-bridge2.0-0t64 libcups2t64 \
        libdrm2 libxcomposite1 libxdamage1 libxfixes3 libpango-1.0-0 libcairo2 \
        git ca-certificates procps \
    && rm -rf /var/lib/apt/lists/*

COPY entrypoint.sh /entrypoint.sh
RUN chmod +x /entrypoint.sh
ENTRYPOINT ["/entrypoint.sh"]
