export type ClaimLike = {claim_text?: string};

export type ClaimEditorEntry = {
  draft: string;
  baseline: string;
};

export const MAX_CLAIMS = 3;

export function parseClaimLines(draft: string): string[] {
  return draft.split('\n').map(v => v.trim()).filter(Boolean);
}

export function draftFromClaims(claims: ClaimLike[] | undefined | null): string {
  return (claims || []).map(c => c.claim_text || '').join('\n');
}

/**
 * Returns the editor entry for an analysis. Claims may arrive after the
 * initial accepted response (via polling or history load), so the entry is
 * initialized the first time claims are available. An existing entry is
 * never overwritten, so unsaved edits survive polling updates.
 */
export function nextEditorEntry(
  claims: ClaimLike[] | undefined | null,
  existing?: ClaimEditorEntry,
): ClaimEditorEntry {
  if (existing) return existing;
  const draft = draftFromClaims(claims);
  return {draft, baseline: draft};
}

export function reverifyDisabled(entry: ClaimEditorEntry | undefined, processing: boolean): boolean {
  if (!entry) return true;
  if (processing) return true;
  const lines = parseClaimLines(entry.draft);
  if (lines.length === 0) return true;
  if (lines.length > MAX_CLAIMS) return true;
  if (entry.draft === entry.baseline) return true;
  return false;
}
