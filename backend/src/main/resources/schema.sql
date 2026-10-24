CREATE TABLE IF NOT EXISTS movies (
 id UUID PRIMARY KEY, title TEXT NOT NULL, release_year INTEGER, synopsis TEXT NOT NULL,
 status VARCHAR(40) NOT NULL DEFAULT 'DRAFT', error_message TEXT,
 created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE TABLE IF NOT EXISTS episodes (
 id UUID PRIMARY KEY, movie_id UUID NOT NULL REFERENCES movies(id),
 episode_number INTEGER NOT NULL DEFAULT 1, narration TEXT,
 status VARCHAR(40) NOT NULL DEFAULT 'DRAFT', error_message TEXT,
 video_path TEXT, created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
 UNIQUE(movie_id, episode_number)
);
CREATE TABLE IF NOT EXISTS assets (
 id UUID PRIMARY KEY, episode_id UUID NOT NULL REFERENCES episodes(id),
 file_path TEXT NOT NULL, origin VARCHAR(30) NOT NULL,
 license_evidence TEXT, rights_verified BOOLEAN NOT NULL DEFAULT false
);
