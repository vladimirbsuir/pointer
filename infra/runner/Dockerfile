FROM rust:1-trixie AS build
WORKDIR /src
COPY . .
RUN cargo build --release --locked -p pointer-runner

FROM debian:trixie-slim AS slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends ca-certificates curl git python3 ripgrep sudo \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --uid 1000 pointer \
    && mkdir -p /workspace /etc/pointer \
    && chown pointer:pointer /workspace \
    && git config --system --add safe.directory '*' \
    && git config --system include.path /etc/pointer/git-auth.conf
COPY --from=build /src/target/release/pointer-runner /usr/local/bin/pointer-runner
ENV POINTER_GIT_AUTH_CONFIG=/etc/pointer/git-auth.conf
WORKDIR /workspace
ENTRYPOINT ["pointer-runner"]

FROM slim AS full
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential golang-go nodejs npm openjdk-21-jdk-headless python3-pip python3-venv \
    && rm -rf /var/lib/apt/lists/*
USER pointer
ENV CARGO_HOME=/home/pointer/.cargo \
    RUSTUP_HOME=/home/pointer/.rustup \
    PATH=/home/pointer/.cargo/bin:$PATH
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path
USER root

FROM full AS gui
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        xvfb xauth openbox xdotool scrot xclip x11-utils dbus dbus-x11 at-spi2-core libatk-adaptor x11vnc websockify \
        fonts-dejavu-core fonts-noto-core fonts-noto-color-emoji \
        chromium libgtk-3-0t64 libwebkit2gtk-4.1-0 libxss1 libnss3 libasound2t64 \
        libxcb-cursor0 libxkbcommon-x11-0 python3-tk openjdk-21-jre libatk-wrapper-java libatk-wrapper-java-jni \
        libgtk-3-dev libwebkit2gtk-4.1-dev libssl-dev librsvg2-dev libayatana-appindicator3-dev libxdo-dev \
    && rm -rf /var/lib/apt/lists/*
COPY --chmod=755 <<'SCRIPT' /usr/local/bin/pointer-chromium
#!/bin/sh
exec chromium \
    --no-sandbox \
    --disable-dev-shm-usage \
    --disable-gpu \
    --force-renderer-accessibility \
    --no-first-run \
    --no-default-browser-check \
    --password-store=basic \
    --window-position=0,0 \
    --window-size="${POINTER_CHROMIUM_WINDOW_SIZE:-1280,800}" \
    "$@"
SCRIPT
ENV DISPLAY=:99 \
    LANG=C.UTF-8 \
    LIBGL_ALWAYS_SOFTWARE=1 \
    GDK_BACKEND=x11 \
    QT_QPA_PLATFORM=xcb \
    QT_LINUX_ACCESSIBILITY_ALWAYS_ON=1 \
    ACCESSIBILITY_ENABLED=1 \
    WEBKIT_DISABLE_DMABUF_RENDERER=1 \
    WEBKIT_DISABLE_COMPOSITING_MODE=1 \
    WEBKIT_DISABLE_SANDBOX_THIS_IS_DANGEROUS=1 \
    ELECTRON_DISABLE_SANDBOX=1
