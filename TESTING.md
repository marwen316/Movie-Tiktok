# MVP smoke test

The pipeline currently produces one recap per generation request using six AI-generated images and a French AI voice. Licensed asset ingestion, recurring scheduling and TikTok posting are not implemented. Subtitles are intentionally out of scope: videos use French AI narration only.

## Start
```bash
cp .env.example .env
# Set a real OpenAI API key and strong passwords in .env
docker compose up --build -d
curl http://localhost:8080/actuator/health
```

## Create a film
Provide a **detailed, factual synopsis you have permission to use**:
```bash
curl -X POST http://localhost:8080/api/movies \
 -H 'Content-Type: application/json' \
 -d '{"title":"Example","releaseYear":2024,"sourceSynopsis":"A fictional investigator follows clues in a coastal town and discovers a hidden archive."}'
```
Copy the returned `id`.

## Generate
```bash
curl -X POST http://localhost:8080/api/movies/MOVIE_ID/generate
curl http://localhost:8080/api/episodes/EPISODE_ID
curl http://localhost:8080/api/episodes/EPISODE_ID/assets
```
Wait for `READY_FOR_REVIEW`, then download:
```bash
curl -o recap.mp4 http://localhost:8080/api/episodes/EPISODE_ID/video
```

Assets default to unverified. Review every image, then POST `{"evidence":"Reviewed generated illustration and confirmed appropriate usage"}` to `/api/assets/ASSET_ID/verify` for each asset. Finally POST `/api/episodes/EPISODE_ID/approve`.

## Limitations
- Do not expose the API publicly; authentication and authorization are not implemented.
- API processing currently uses an in-memory executor; jobs are not restart-resilient.
- Image generation can be expensive. API model availability and image sizes must be confirmed against your account.
- Media files are stored in a Docker volume rather than MinIO; MinIO is provisioned but not integrated.
- Subtitles are intentionally disabled (French narration only). Licensed image upload, multi-episode narrative planning and automated TikTok publishing are not implemented yet.
- The repository has not been built or integration-tested in the target environment.
