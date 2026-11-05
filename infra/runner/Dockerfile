FROM rust:1-trixie AS build
WORKDIR /src
COPY . .
RUN cargo build --release --locked -p pointer-runner

FROM debian:trixie-slim
RUN apt-get update \
    && apt-get install -y --no-install-recommends \
        build-essential ca-certificates curl git golang-go nodejs npm \
        openjdk-21-jdk-headless python3 python3-pip python3-venv ripgrep \
    && rm -rf /var/lib/apt/lists/*
RUN useradd --create-home --uid 1000 pointer \
    && mkdir -p /workspace \
    && chown pointer:pointer /workspace
USER pointer
ENV CARGO_HOME=/home/pointer/.cargo \
    RUSTUP_HOME=/home/pointer/.rustup \
    PATH=/home/pointer/.cargo/bin:$PATH
RUN curl -sSf https://sh.rustup.rs | sh -s -- -y --profile minimal --no-modify-path
COPY --from=build /src/target/release/pointer-runner /usr/local/bin/pointer-runner
WORKDIR /workspace
ENTRYPOINT ["pointer-runner"]
