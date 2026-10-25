# Publication TikTok programmee (mode prive)

n8n est deja defini dans docker-compose.yml. Lancer avec `docker compose up -d n8n api frontend`, puis ouvrir http://localhost:5678.

## Import

1. Dans n8n, creer le compte administrateur local si demande.
2. Importer le fichier `n8n/workflows/tiktok-scheduled-posting.json` depuis le menu des workflows.
3. Creer une credential **Header Auth** :
   - Name: `X-TikTok-Admin-Key`
   - Value: la valeur de `TIKTOK_ADMIN_KEY` du fichier .env (ne pas l'ajouter au workflow JSON ni dans Git).
4. Affecter cette credential aux deux noeuds HTTP Request : **Publier les videos dues** et **Verifier les publications**.
5. Tester manuellement les noeuds (sans videos dues, `claimed:0` est normal).
6. Activer / publier le workflow dans n8n.

Le backend est accessible depuis le conteneur n8n par `http://api:8080` (pas localhost).

## Planification

Dans Angular, choisir un episode **APPROVED**, saisir la legende, cocher le consentement et choisir une date/heure future via **Programmer une publication privee**.

La planification est enregistree en PostgreSQL. n8n execute le workflow chaque minute et demande au backend de traiter les videos dues, puis de verifier les statuts TikTok.

Statuts : QUEUED, SENDING, PROCESSING, PUBLISHED, NEEDS_REVIEW, CANCELLED.

Les echecs d'envoi sont marques NEEDS_REVIEW sans nouvel essai automatique : en cas de coupure reseau, TikTok peut avoir accepte l'envoi. Ne pas reprogrammer avant verification du compte TikTok.

Le workflow ne choisit pas automatiquement un film et ne programme pas de nouvelles videos : l'approbation et la programmation restent manuelles dans cette premiere version. L'automatisation complete (selection de la prochaine video approuvee, 19 h Europe/Paris, maximum une par jour) sera une etape distincte.

La publication est forcee en SELF_ONLY et exige un compte TikTok prive tant que l'application n'est pas auditee.

## Securite

Ne pas exposer n8n sur le tunnel Cloudflare. Le port 5678 est lie a 127.0.0.1. Proteger le compte n8n et conserver les secrets hors du depot Git.
