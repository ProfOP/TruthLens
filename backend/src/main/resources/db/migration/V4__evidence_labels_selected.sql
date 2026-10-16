-- Add provenance columns for evidence-level named scores and the passage
-- actually selected by the FEVER Iteration 2 verdict logic.
ALTER TABLE evidence ADD COLUMN IF NOT EXISTS nli_labels JSONB;
ALTER TABLE evidence ADD COLUMN IF NOT EXISTS selected BOOLEAN NOT NULL DEFAULT FALSE;
