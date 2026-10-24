# Movie TikTok Factory

Studio Angular 20 de création de vidéos verticales avec narration française, illustrations IA et rendu FFmpeg.

## Démarrage sous Windows CMD

1. Configurer `.env` avec les identifiants PostgreSQL et la clé API OpenAI.
2. Exécuter `docker compose up --build -d postgres api frontend` (sans n8n).
3. Ouvrir http://localhost:4200 dans le navigateur.
4. Créer un film avec son synopsis ou charger un film existant.
5. Lancer une génération, suivre son statut, visionner et télécharger le MP4.

L'interface Angular est servie par Nginx, qui relaie `/api/` vers Spring Boot. PostgreSQL conserve les films et épisodes. Les vidéos sont dans le volume Docker `media_data`.

**Important :** chaque génération appelle OpenAI et entraîne des frais. La publication TikTok reste manuelle. Vérifier les droits des visuels avant publication.

n8n reste facultatif pour utiliser le studio. Pour le lancer séparément : `docker compose up -d n8n`.
