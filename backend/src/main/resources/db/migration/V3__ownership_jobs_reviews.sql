ALTER TABLE analyses ADD COLUMN IF NOT EXISTS reviewable BOOLEAN NOT NULL DEFAULT FALSE;
ALTER TABLE analyses ADD COLUMN IF NOT EXISTS review_status VARCHAR(32);

CREATE TABLE IF NOT EXISTS analysis_jobs (
  id UUID PRIMARY KEY,
  analysis_id UUID NOT NULL REFERENCES analyses(id),
  status VARCHAR(32) NOT NULL,
  attempts INTEGER NOT NULL DEFAULT 0,
  last_error TEXT,
  locked_at TIMESTAMPTZ,
  lock_owner VARCHAR(64),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now(),
  updated_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS analysis_jobs_status_idx ON analysis_jobs(status, updated_at);

CREATE TABLE IF NOT EXISTS claims (
  id UUID PRIMARY KEY,
  analysis_id UUID NOT NULL REFERENCES analyses(id),
  claim_text TEXT NOT NULL,
  sentence_index INTEGER,
  relevance DOUBLE PRECISION,
  method VARCHAR(64),
  verdict VARCHAR(32),
  component_status VARCHAR(32) NOT NULL DEFAULT 'PENDING',
  nli_output JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS claims_analysis_idx ON claims(analysis_id);

CREATE TABLE IF NOT EXISTS evidence (
  id UUID PRIMARY KEY,
  claim_id UUID NOT NULL REFERENCES claims(id),
  page_id TEXT,
  sentence_id INTEGER,
  passage TEXT NOT NULL,
  similarity DOUBLE PRECISION,
  overlap DOUBLE PRECISION,
  nli_scores JSONB,
  provenance JSONB
);
CREATE INDEX IF NOT EXISTS evidence_claim_idx ON evidence(claim_id);

CREATE TABLE IF NOT EXISTS reviews (
  id UUID PRIMARY KEY,
  analysis_id UUID NOT NULL REFERENCES analyses(id),
  reviewer_id UUID NOT NULL REFERENCES users(id),
  status VARCHAR(32) NOT NULL,
  notes TEXT,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
CREATE INDEX IF NOT EXISTS reviews_analysis_idx ON reviews(analysis_id, created_at DESC);

CREATE TABLE IF NOT EXISTS assets (
  id UUID PRIMARY KEY,
  analysis_id UUID NOT NULL REFERENCES analyses(id),
  kind VARCHAR(32) NOT NULL,
  storage_path TEXT NOT NULL,
  content_type VARCHAR(128),
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);

CREATE TABLE IF NOT EXISTS explanations (
  id UUID PRIMARY KEY,
  analysis_id UUID NOT NULL REFERENCES analyses(id),
  kind VARCHAR(32) NOT NULL,
  status VARCHAR(32) NOT NULL,
  payload JSONB,
  created_at TIMESTAMPTZ NOT NULL DEFAULT now()
);
