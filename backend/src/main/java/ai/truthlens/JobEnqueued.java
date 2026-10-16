package ai.truthlens;

import java.util.UUID;

/** Published after a job row is committed so an idle worker can take it without waiting for its next poll. */
record JobEnqueued(UUID analysisId) {}
