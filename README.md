# Pointer

An agent-first desktop development environment: describe a task, and an AI agent plans and carries it out in your project while you direct, review and approve.

Built with Tauri and React on the desktop and a Spring Boot backend that orchestrates agents over any OpenAI-compatible model, including local ones.

- [Vision and scope](docs/00-vision.md)
- [Requirements](docs/01-requirements.md)
- [Architecture](docs/02-architecture.md)
- [Client-server protocol](docs/03-protocol.md)
- [Data model](docs/04-data-model.md)
- [Roadmap](docs/05-roadmap.md)
- [Architecture decisions](docs/adr/README.md)
- [Project rules for contributors and agents](AGENTS.md)

## Development

Prerequisites: JDK 25, Node.js 24 with pnpm, Rust stable, Docker Desktop.

### Backend

```bash
cd backend
./gradlew bootRun
```

PostgreSQL from `infra/docker-compose.yml` starts automatically. The API runs on `http://localhost:8080`, Swagger UI on `/swagger-ui`.

```bash
./gradlew spotlessApply build
```

Formats the code and runs all tests (Testcontainers, Docker required).

### Desktop

```bash
cd desktop
pnpm install
pnpm tauri dev
```

Checks: `pnpm format:check`, `pnpm lint`, `pnpm build`, and in `src-tauri`: `cargo fmt --check`, `cargo clippy -- -D warnings`.

With the backend running, `pnpm api:generate` regenerates TypeScript types from the OpenAPI document.
