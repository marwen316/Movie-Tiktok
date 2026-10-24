# Movie TikTok Factory

MVP for generating French-language short movie recaps with licensed or original AI illustrations.

## Architecture
- Spring Boot 3 / Java 21 API
- n8n orchestration
- PostgreSQL for job metadata
- MinIO for assets
- FFmpeg rendering worker (planned)

## Quick start
1. Copy `.env.example` to `.env` and replace placeholders.
2. Run `docker compose up --build -d`.
3. Open n8n at http://localhost:5678 and API at http://localhost:8080/actuator/health.
4. Import workflows from `n8n/workflows`.

This initial scaffold provides the infrastructure, API skeleton and orchestration template. AI generation, asset licensing verification, FFmpeg rendering and TikTok publication are **not yet implemented**.

Do not publish copyrighted footage or posters without appropriate permission. Human approval is required before publication.
