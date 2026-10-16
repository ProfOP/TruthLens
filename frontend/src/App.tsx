import React, {ChangeEvent, DragEvent, useEffect, useRef, useState} from 'react';
import type {ReactNode} from 'react';
import {useLocation, useNavigate, useParams} from 'react-router-dom';
import {Accordion, AccordionDetails, AccordionSummary, Alert, AppBar, Box, Button, Card, CardContent, Chip, Container, FormControlLabel, IconButton, LinearProgress, MenuItem, Paper, Stack, Switch, Tab, Tabs, TextField, Toolbar, Typography} from '@mui/material';
import {credibilityLabel, formatPercent} from './format';
import {HISTORY_TYPE_OPTIONS, hasNextHistoryPage, historyPageFooter, modelRows, roleChoices, saveBlockedReason, selfChangeBlocked, typeLabel} from './adminUsers';
import {ClaimEditorEntry, draftFromClaims, nextEditorEntry, parseClaimLines, reverifyDisabled} from './claimEditor';

const API = import.meta.env.VITE_API_URL || 'http://localhost:8080/api/v1';
const MAX_IMAGE_BYTES = 10 * 1024 * 1024;
const IMAGE_TYPES = new Set(['image/jpeg', 'image/png']);
const POLL_INTERVAL_MS = 700;
const MAX_POLL_ATTEMPTS = 40;

type TabKey = 'TEXT' | 'IMAGE' | 'CLAIM';
type User = {id: string; name: string; email: string; role: string};
type HistoryRow = {id: string; input_type: string; status: string; created_at: string};
export type Evidence = {
  id?: string;
  page_id?: string;
  sentence_id?: number | null;
  passage?: string;
  similarity?: number | null;
  overlap?: number | null;
  nli_scores?: number[] | null;
  nli_labels?: string[] | null;
  selected?: boolean;
  provenance?: any;
};
export type RelatedPassage = {
  rank?: number;
  page_id?: string;
  page_title?: string;
  sentence_id?: number | null;
  text?: string;
  score?: number | null;
  route?: string;
  source?: string;
  provenance?: string;
  used_for_verdict?: boolean;
};
type RelatedBlock = {
  status?: string;
  heading?: string;
  note?: string;
  error?: string;
  k?: number;
  passages?: RelatedPassage[];
  stats?: {pages_selected?: number; sentences_searched?: number; sentences_newly_encoded?: number};
  timing_ms?: {discovery_total?: number};
};
export type Claim = {
  id?: string;
  claim_text?: string;
  verdict?: string;
  component_status?: string;
  nli_output?: any;
  evidence?: Evidence[];
};
export type Explanation = {id?: string; kind?: string; status?: string; payload?: any};
type AnalysisResult = {
  id?: string;
  status?: string;
  error?: string;
  input_type?: string;
  created_at?: string;
  updated_at?: string;
  review_status?: string | null;
  reviewable?: boolean;
  reviews?: any[];
  result?: any;
  claims?: Claim[];
  explanations?: Explanation[];
};
export type TabState = {result: AnalysisResult | null; loading: boolean; error: string; timedOut: boolean};
type TextTabState = TabState & {title: string; body: string};
type ClaimTabState = TabState & {claim: string};
type ImageTabState = TabState & {image: File | null; preview: string; dragging: boolean};
type ShapToken = {token: string; start?: number; end?: number; real_contribution: number; fake_contribution: number; direction?: string};
type ShapPayload = {
  status?: string;
  target?: string;
  output_names?: string[];
  alignment?: string;
  truncated?: boolean;
  text_truncated?: boolean;
  explained_text?: string;
  tokens?: ShapToken[];
  error?: string;
};

const EMPTY_TEXT: TextTabState = {title: '', body: '', result: null, loading: false, error: '', timedOut: false};
const EMPTY_CLAIM: ClaimTabState = {claim: '', result: null, loading: false, error: '', timedOut: false};
const EMPTY_IMAGE: ImageTabState = {image: null, preview: '', dragging: false, result: null, loading: false, error: '', timedOut: false};

/** Grad-CAM overlay: a transparent attention layer sized to sit exactly on the image. */
function heatColor(value: number): string {
  // value is normalized 0..1; low attention stays transparent so the photo shows through.
  const alpha = Math.max(0, Math.min(0.68, value * 0.68));
  const hue = (1 - Math.max(0, Math.min(1, value))) * 42; // amber -> red
  return `hsla(${hue.toFixed(1)},92%,50%,${alpha.toFixed(3)})`;
}
function HeatmapOverlay({values}: {values: number[][]}) {
  const rows = values.length;
  const cols = values[0]?.length || 0;
  if (!rows || !cols) return null;
  return (
    <Box className="heatmap" aria-label="Grad-CAM attention overlay" sx={{gridTemplateColumns: `repeat(${cols}, 1fr)`, gridTemplateRows: `repeat(${rows}, 1fr)`}}>
      {values.flat().map((value, index) => <Box key={index} className="heat-cell" sx={{backgroundColor: heatColor(Number(value))}} />)}
    </Box>
  );
}

function BarRow({label, value, max, color}: {label: string; value: number; max: number; color?: string}) {
  const pct = max > 0 ? Math.round((value / max) * 100) : 0;
  return (
    <Box sx={{mb: 1}}>
      <Box sx={{display: 'flex', justifyContent: 'space-between', gap: 2}}>
        <Typography variant="body2">{label}</Typography>
        <Typography variant="body2" fontWeight={700}>{value}</Typography>
      </Box>
      <Box sx={{bgcolor: '#eceff1', borderRadius: 1, height: 10, mt: 0.5}}>
        <Box sx={{bgcolor: color || '#3f51b5', width: `${Math.max(2, pct)}%`, height: 10, borderRadius: 1}} />
      </Box>
    </Box>
  );
}

function Breakdown({title, rows, valueKey, color}: {title: string; rows: any[]; valueKey: string; color?: string}) {
  if (!rows || rows.length === 0) {
    return <Box sx={{mt: 2}}><Typography variant="subtitle2">{title}</Typography><Typography variant="caption" color="text.secondary">No records yet.</Typography></Box>;
  }
  const max = Math.max(...rows.map(r => Number(r[valueKey]) || 0));
  return (
    <Box sx={{mt: 2}}>
      <Typography variant="subtitle2">{title}</Typography>
      {rows.map((row, index) => <BarRow key={index} label={String(row[Object.keys(row).find(k => k !== valueKey) as string] ?? index)} value={Number(row[valueKey]) || 0} max={max} color={color} />)}
    </Box>
  );
}

function TrendStrip({rows}: {rows: any[]}) {
  if (!rows || rows.length === 0) return <Typography variant="caption" color="text.secondary">No daily activity yet.</Typography>;
  const max = Math.max(...rows.map(r => Number(r.total) || 0));
  return (
    <Box sx={{mt: 2}}>
      <Typography variant="subtitle2">Activity last 30 days</Typography>
      <Box sx={{display: 'flex', alignItems: 'flex-end', gap: '4px'}}>
        {[...rows].reverse().map((row, index) => {
          const value = Number(row.total) || 0;
          const h = max > 0 ? Math.max(4, Math.round((value / max) * 120)) : 4;
          const day = String(row.day || '').slice(0, 10);
          return (
            <Box key={index} sx={{textAlign: 'center'}} title={`${day}: ${value}`}>
              <Box sx={{bgcolor: '#607d8b', width: 14, height: `${h}px`, borderRadius: '2px 2px 0 0'}} />
              <Typography variant="caption" sx={{fontSize: 9}}>{day.slice(5)}</Typography>
            </Box>
          );
        })}
      </Box>
    </Box>
  );
}

function authHeaders(token: string) {
  return token ? {Authorization: `Bearer ${token}`} as Record<string, string> : {};
}

export function tabIndexForType(type?: string): number {
  return type === 'IMAGE' ? 1 : type === 'CLAIM' ? 2 : 0;
}

export function keyForTabIndex(index: number): TabKey {
  return index === 1 ? 'IMAGE' : index === 2 ? 'CLAIM' : 'TEXT';
}

function displayedClaims(result: AnalysisResult | null): Claim[] {
  return result?.claims || result?.result?.verification?.claims || [];
}

function claimsKey(result: AnalysisResult | null): string {
  return displayedClaims(result).map(c => c.claim_text || '').join('\n');
}

/** A claim whose verification call failed: no verdict exists, so it is not an INSUFFICIENT finding. */
function FailedClaim({claim}: {claim: Claim}) {
  return (
    <Box className="claim-evidence" sx={{mt: 1}} data-testid="claim-evidence">
      <Typography fontWeight={700} sx={{display: 'flex', alignItems: 'center', gap: 1}}>
        <Chip label="UNAVAILABLE" size="small" color="warning" />
        {claim.claim_text || '(claim text unavailable)'}
      </Typography>
      <Alert severity="warning" sx={{mt: 0.5}} data-testid="claim-unavailable">
        Verification could not be completed: the evidence service call failed for this claim, so no verdict was
        produced and no evidence was retrieved. This is a service failure, not a search that found no conclusive
        evidence. Resubmit the claim once the service is available.
        {claim.verdict ? ` An earlier build stored “${claim.verdict}” for it; that value was not produced by a completed verification.` : ''}
      </Alert>
    </Box>
  );
}

/** Claim verdict with reason and evidence; selected vs other candidates split. */
export function ClaimEvidence({claim, index}: {claim: Claim; index: number}) {
  if (claim.component_status === 'UNAVAILABLE') return <FailedClaim claim={claim} />;
  const verdict = claim.verdict || 'INSUFFICIENT';
  const decided = verdict === 'SUPPORTED' || verdict === 'REFUTED';
  const reason = claim.nli_output?.reason || claim.nli_output?.note;
  const evidence = Array.isArray(claim.evidence) ? claim.evidence : [];
  const hasProvenance = evidence.some(ev => ev.selected === true);
  const selected = evidence.filter(ev => ev.selected);
  const others = evidence.filter(ev => !ev.selected);
  const render = (list: Evidence[]) => list.map((ev, i) => <EvidencePassage key={ev.id || i} ev={ev} index={i} />);
  return (
    <Box className="claim-evidence" sx={{mt: 1}} data-testid="claim-evidence">
      <Typography fontWeight={700} sx={{display: 'flex', alignItems: 'center', gap: 1}}>
        <Chip label={verdict} size="small" color={verdict === 'SUPPORTED' ? 'success' : verdict === 'REFUTED' ? 'error' : 'default'} />
        {claim.claim_text || '(claim text unavailable)'}
      </Typography>
      {reason ? <Typography variant="body2" color="text.secondary" sx={{mt: 0.5}}>{reason}</Typography> : null}
      {decided ? (
        hasProvenance ? (
          <>
            <Typography variant="body2" color="text.secondary" sx={{mt: 0.5}}>Evidence used for this verdict:</Typography>
            {render(selected)}
            {others.length > 0 && (
              <Accordion disableGutters className="evidence-accordion">
                <AccordionSummary aria-controls={`other-panel-${index}`}>Other retrieved candidates — not used for this verdict ({others.length})</AccordionSummary>
                <AccordionDetails>{render(others)}</AccordionDetails>
              </Accordion>
            )}
          </>
        ) : (
          <>
            <Typography variant="caption" color="text.secondary" sx={{mt: 0.5, display: 'block'}}>
              Selected-evidence provenance is unavailable for this earlier record; all retrieved passages are shown.
            </Typography>
            <Typography variant="body2" color="text.secondary" sx={{mt: 0.5}}>Retrieved evidence:</Typography>
            {render(evidence)}
          </>
        )
      ) : (
        <>
          {!/no conclusive evidence/i.test(reason || '') ? (
            <Typography variant="body2" color="text.secondary" sx={{mt: 0.5}}>
              No conclusive evidence found in the indexed repository.
            </Typography>
          ) : null}
          {evidence.length > 0 && (
            <Accordion disableGutters className="evidence-accordion">
              <AccordionSummary aria-controls={`passage-panel-${index}`}>Related passages — inconclusive ({evidence.length})</AccordionSummary>
              <AccordionDetails>{render(evidence)}</AccordionDetails>
            </Accordion>
          )}
        </>
      )}
      <RelatedPassages rel={claim.nli_output?.related_passages} index={index} />
    </Box>
  );
}

function EvidencePassage({ev, index}: {ev: Evidence; index: number}) {
  const scores = ev.nli_scores;
  const hasScores = Array.isArray(scores) && scores.length > 0;
  const labels = Array.isArray(ev.nli_labels) && ev.nli_labels.length === scores?.length ? ev.nli_labels : null;
  const nliText = hasScores
    ? scores!.map((s, i) => `${labels ? labels[i] : `score ${i + 1}`} ${Number(s).toFixed(3)}`).join(', ')
    : '';
  return (
    <Box className="evidence-passage" sx={{mt: 1}} data-testid="evidence-passage">
      <Typography variant="body2">
        {ev.page_id ? `Source ${ev.page_id}` : `Passage ${index + 1}`}
        {typeof ev.sentence_id === 'number' ? ` · sentence ${ev.sentence_id}` : ''}
        {typeof ev.similarity === 'number' ? ` · similarity ${(ev.similarity * 100).toFixed(1)}%` : ''}
      </Typography>
      <Typography variant="body2">{ev.passage || '(passage text unavailable)'}</Typography>
      {nliText ? <Typography variant="caption" color="text.secondary">{labels ? 'NLI scores' : 'NLI scores (unlabeled metadata)'}: {nliText}</Typography> : null}
      {ev.provenance?.rejection ? <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>Inconclusive because: {ev.provenance.rejection}</Typography> : null}
    </Box>
  );
}

/** Discovery candidates from the optional title-route pilot: leads to read, never verdict evidence. */
export function RelatedPassages({rel, index}: {rel?: RelatedBlock | null; index: number}) {
  if (!rel || (rel.status !== 'ok' && rel.status !== 'unavailable')) return null;
  if (rel.status === 'unavailable') {
    return (
      <Typography variant="caption" color="text.secondary" sx={{mt: 0.5, display: 'block'}} data-testid="related-passages-unavailable">
        Candidate discovery unavailable: {rel.error || 'unknown reason'}. The verdict and evidence above are the deployed result, unchanged.
      </Typography>
    );
  }
  const passages = Array.isArray(rel.passages) ? rel.passages : [];
  if (passages.length === 0) return null;
  return (
    <Accordion disableGutters className="evidence-accordion" data-testid="related-passages">
      <AccordionSummary aria-controls={`related-panel-${index}`}>
        Related passages — candidate evidence, not used for this verdict ({passages.length})
      </AccordionSummary>
      <AccordionDetails>
        <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>
          {rel.note || 'Retrieved by the optional page-title discovery pilot. Not scored by the NLI gate; not evidence for the verdict.'}
        </Typography>
        {passages.map((p, i) => (
          <Box key={`${p.page_id}-${p.sentence_id}-${i}`} className="related-passage" sx={{mt: 1}} data-testid="related-passage">
            <Typography variant="body2">
              {`#${p.rank ?? i + 1} ${p.page_title || p.page_id || '(unknown page)'}`}
              {typeof p.sentence_id === 'number' ? ` · sentence ${p.sentence_id}` : ''}
              {typeof p.score === 'number' ? ` · similarity ${(p.score * 100).toFixed(1)}%` : ''}
            </Typography>
            <Typography variant="body2">{p.text || '(passage text unavailable)'}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>
              {p.provenance || `${p.route || 'title'} route · vector ${p.source || 'cache'}`} — candidate only, used_for_verdict={String(p.used_for_verdict ?? false)}
            </Typography>
          </Box>
        ))}
      </AccordionDetails>
    </Accordion>
  );
}

function ClaimEditor({entry, processing, onDraftChange, onSubmit}: {
  entry: ClaimEditorEntry | undefined;
  processing: boolean;
  onDraftChange: (draft: string) => void;
  onSubmit: () => void;
}) {
  const draft = entry?.draft ?? '';
  const lines = parseClaimLines(draft);
  const overLimit = lines.length > 3;
  const disabled = reverifyDisabled(entry, processing);
  return (
    <Box sx={{mt: 2}}>
      <TextField
        fullWidth
        multiline
        minRows={3}
        label="Editable claims (one per line, max 3)"
        value={draft}
        onChange={e => onDraftChange(e.target.value)}
        helperText={overLimit ? 'Maximum 3 claims. Remove extra lines to re-verify.' : 'Automatic candidates are pre-filled when available; you can edit or add claims manually.'}
        sx={{mt: 1}}
      />
      <Button sx={{mt: 1}} disabled={disabled} onClick={onSubmit}>{processing ? 'Verifying…' : 'Re-verify edited claims'}</Button>
    </Box>
  );
}

/** Renders the explained text with per-token SHAP highlights using verified offsets. */
function HighlightedText({text, tokens, maxAbs}: {text: string; tokens: ShapToken[]; maxAbs: number}) {
  const parts: ReactNode[] = [];
  let cursor = 0;
  tokens.forEach((t, i) => {
    const start = Math.max(t.start || 0, cursor);
    const end = Math.max(start, Math.min(t.end || 0, text.length));
    if (start > cursor) parts.push(<span key={`plain-${i}`}>{text.slice(cursor, start)}</span>);
    if (end > start) {
      const value = t.real_contribution || 0;
      const intensity = Math.min(0.85, (Math.abs(value) / maxAbs) * 0.85);
      const background = value > 1e-8 ? `rgba(22,163,74,${intensity})` : value < -1e-8 ? `rgba(220,38,38,${intensity})` : '#e2e8f0';
      const direction = value > 1e-8 ? 'increases' : value < -1e-8 ? 'decreases' : 'no effect on';
      parts.push(
        <mark
          key={`tok-${i}`}
          className={value > 1e-8 ? 'tok-real' : value < -1e-8 ? 'tok-fake' : 'tok-neutral'}
          title={`${t.token}: ${value >= 0 ? '+' : ''}${value.toFixed(4)} REAL (${direction} the explained output)`}
          style={{backgroundColor: background, color: 'inherit', backgroundImage: 'none'}}
        >
          {text.slice(start, end)}
        </mark>
      );
    }
    cursor = Math.max(cursor, end);
  });
  if (cursor < text.length) parts.push(<span key="plain-tail">{text.slice(cursor)}</span>);
  return <span className="shap-text">{parts}</span>;
}

export function ShapExplanation({explanation}: {explanation?: Explanation}) {
  const payload: ShapPayload = (explanation && (explanation as any).payload) || (explanation as any) || {};
  const tokens = Array.isArray(payload.tokens) ? payload.tokens : [];
  const aligned = payload.alignment === 'verified' && tokens.some(t => typeof t.start === 'number' && typeof t.end === 'number');
  const target = payload.target || 'REAL probability';
  const explained = payload.explained_text || '';
  const widthTokens = aligned ? tokens.filter(t => (t.end || 0) > (t.start || 0)) : [];
  const maxAbs = Math.max(1e-9, ...widthTokens.map(t => Math.abs(t.real_contribution || 0)));
  const status = payload.status || (explanation as any)?.status || 'unavailable';
  return (
    <Box data-testid="shap-explanation">
      <Typography variant="h6" sx={{mt: 3}}>Token explanation</Typography>
      <Typography color="text.secondary">Status: {status}</Typography>
      <Typography variant="body2" sx={{mt: 1}}>
        Explains: <strong>{target}</strong>. Positive values increase this output; negative values decrease it. Zero attribution is neutral. Token influence is not factual truth.
      </Typography>
      <Box className="shap-legend" sx={{display: 'flex', gap: 2, mt: 1, flexWrap: 'wrap'}} aria-label="attribution legend">
        <Box sx={{display: 'flex', alignItems: 'center', gap: 0.5}}><Box className="legend-swatch legend-increase" /> <Typography variant="caption">Increases output</Typography></Box>
        <Box sx={{display: 'flex', alignItems: 'center', gap: 0.5}}><Box className="legend-swatch legend-decrease" /> <Typography variant="caption">Decreases output</Typography></Box>
        <Box sx={{display: 'flex', alignItems: 'center', gap: 0.5}}><Box className="legend-swatch legend-neutral" /> <Typography variant="caption">Neutral</Typography></Box>
      </Box>
      {payload.truncated && <Alert severity="info" sx={{mt: 2}}>The model truncated this text at 256 tokens. Only the text processed by the model is explained below.</Alert>}
      {payload.text_truncated && !payload.truncated && <Alert severity="info" sx={{mt: 2}}>The explanation text was shortened to 64 words. Only the text processed by the model is explained below.</Alert>}
      {explained ? (
        <Typography variant="body2" className="shap-explained" sx={{whiteSpace: 'pre-wrap', mt: 1}}>
          {aligned ? <HighlightedText text={explained} tokens={widthTokens} maxAbs={maxAbs} /> : explained}
        </Typography>
      ) : (
        <Typography variant="body2" color="text.secondary">Explained text unavailable.</Typography>
      )}
      {explained && tokens.length > 0 && !aligned && (
        <Alert severity="warning" sx={{mt: 2}}>Token highlights unavailable for this record — token alignment could not be verified. Attribution values are listed without positions.</Alert>
      )}
      {status === 'unavailable' && <Typography variant="body2" color="text.secondary" sx={{mt: 1}}>Token explanation unavailable{payload.error ? `: ${payload.error}` : '.'}</Typography>}
      <ul className="sr-only" aria-label="token attribution values">
        {tokens.map((t, i) => (
          <li key={i}>{t.token} — REAL {t.real_contribution >= 0 ? '+' : ''}{Number(t.real_contribution).toFixed(4)}, FAKE {t.fake_contribution >= 0 ? '+' : ''}{Number(t.fake_contribution).toFixed(4)}</li>
        ))}
      </ul>
    </Box>
  );
}

function TimeoutNotice({onCheckAgain}: {onCheckAgain: () => void}) {
  return (
    <>
      <Alert severity="info" sx={{mt: 2}}>Still processing — this job is taking longer than usual. You can check it again without resubmitting.</Alert>
      <Button sx={{mt: 1}} onClick={onCheckAgain}>Check job again</Button>
    </>
  );
}

type ResultPanelProps = {
  state: TabState;
  editorEntry?: ClaimEditorEntry;
  onEditorDraft: (draft: string) => void;
  onReverify: () => void;
  onCheckAgain: () => void;
};

function ProcessingBlock({state, onCheckAgain}: {state: TabState; onCheckAgain: () => void}) {
  return <>
    <Chip label={state.result?.status || 'PROCESSING'} sx={{my: 2}} />
    {state.loading && <LinearProgress sx={{mb: 2}} />}
    <Typography>{state.result?.error || 'Processing model result…'}</Typography>
    {state.timedOut && <TimeoutNotice onCheckAgain={onCheckAgain} />}
  </>;
}

/** Analysis-level notice when the verification component itself failed, from stored claim status. */
export function VerificationNotice({verification}: {verification?: any}) {
  const status = verification?.status;
  if (status !== 'UNAVAILABLE' && status !== 'PARTIAL') return null;
  return (
    <Alert severity={status === 'UNAVAILABLE' ? 'error' : 'warning'} sx={{my: 1}} data-testid="verification-notice">
      <Typography variant="body2" fontWeight={700}>
        {status === 'UNAVAILABLE' ? 'Verification could not be completed for this analysis' : 'Verification completed only in part'}
      </Typography>
      <Typography variant="body2">
        {verification?.unavailable_note || verification?.note || 'Verification could not be completed.'}
      </Typography>
    </Alert>
  );
}

function TextResultPanel({state, editorEntry, onEditorDraft, onReverify, onCheckAgain, onReviewRequest}: ResultPanelProps & {onReviewRequest: () => void}) {
  if (!state.result) return <Box className="empty"><Typography color="text.secondary">Your result will appear here with component-level status and limitations.</Typography></Box>;
  if (state.result.status !== 'COMPLETED') return <ProcessingBlock state={state} onCheckAgain={onCheckAgain} />;
  const welfake = state.result.result;
  const verification = welfake?.verification;
  const claims = displayedClaims(state.result);
  const shapExplanation = state.result.explanations?.find(e => e.kind === 'SHAP');
  const shap = shapExplanation?.payload || welfake?.explanation?.payload;
  return <>
    <Typography variant="subtitle2">Article classification — WELFake (not evidence verification)</Typography>
    <Chip label={welfake?.label || 'UNAVAILABLE'} color="primary" sx={{my: 2}} />
    <Typography>Completed WELFake prediction</Typography>
    <Typography color="text.secondary">Credibility score P(REAL)×100: {credibilityLabel(welfake?.credibility_score ?? (typeof welfake?.probabilities?.REAL === 'number' ? welfake.probabilities.REAL * 100 : undefined))}</Typography>
    <Typography color="text.secondary">REAL: {formatPercent(welfake?.probabilities?.REAL)} · FAKE: {formatPercent(welfake?.probabilities?.FAKE)}</Typography>
    <Typography color="text.secondary">Effective deployment threshold: {welfake?.deployment_overrides?.text_confidence_threshold ?? 'Unavailable'} · Exported: {welfake?.deployment_overrides?.exported_model_threshold ?? welfake?.config?.uncertain_threshold ?? 'Unavailable'}</Typography>
    {welfake?.truncated && <Alert severity="info" sx={{mt: 2}}>The model truncated this text at 256 tokens. The explanation below covers only processed text.</Alert>}
    <Typography variant="h6" sx={{mt: 3}}>Evidence verification</Typography>
    <Typography color="text.secondary">{verification?.coverage || 'Limited local FEVER repository; not live web search.'}</Typography>
    <VerificationNotice verification={verification} />
    {claims.length === 0
      ? <Typography sx={{mt: 1}}>No verifiable factual claims extracted.</Typography>
      : claims.map((claim, index) => <ClaimEvidence key={claim.id || index} claim={claim} index={index} />)}
    <ClaimEditor entry={editorEntry} processing={state.loading} onDraftChange={onEditorDraft} onSubmit={onReverify} />
    <ShapExplanation explanation={{kind: 'SHAP', status: shapExplanation?.status, payload: shap}} />
    {state.timedOut && <TimeoutNotice onCheckAgain={onCheckAgain} />}
    <Button sx={{mt: 2}} onClick={onReviewRequest}>Send to fact-checker review</Button>
  </>;
}

export function ClaimResultPanel({state, editorEntry, onEditorDraft, onReverify, onCheckAgain}: ResultPanelProps) {
  if (!state.result) return <Box className="empty"><Typography color="text.secondary">No verification result yet.</Typography></Box>;
  if (state.result.status !== 'COMPLETED') return <ProcessingBlock state={state} onCheckAgain={onCheckAgain} />;
  const claims = displayedClaims(state.result);
  return <>
    <Typography variant="subtitle2">Claim verification — FEVER NLI (separate from article classification)</Typography>
    <Typography color="text.secondary" sx={{mb: 1}}>Local FEVER Iteration 2 repository only. Missing evidence is INSUFFICIENT, not false.</Typography>
    <VerificationNotice verification={state.result.result?.verification} />
    {claims.length === 0 ? <Typography>No verification result yet.</Typography> : claims.map((claim, index) => <ClaimEvidence key={claim.id || index} claim={claim} index={index} />)}
    <ClaimEditor entry={editorEntry} processing={state.loading} onDraftChange={onEditorDraft} onSubmit={onReverify} />
    {state.timedOut && <TimeoutNotice onCheckAgain={onCheckAgain} />}
  </>;
}

export function App() {
  const nav = useNavigate();
  const location = useLocation();
  const params = useParams();
  const [tab, setTab] = useState(0);
  const [textTab, setTextTab] = useState<TextTabState>(EMPTY_TEXT);
  const [claimTab, setClaimTab] = useState<ClaimTabState>(EMPTY_CLAIM);
  const [imageTab, setImageTab] = useState<ImageTabState>(EMPTY_IMAGE);
  const [token, setToken] = useState(() => sessionStorage.getItem('truthlens_token') || '');
  const [user, setUser] = useState<User | null>(() => {
    try { return JSON.parse(sessionStorage.getItem('truthlens_user') || 'null'); } catch { return null; }
  });
  const [authMode, setAuthMode] = useState(location.pathname === '/register' ? 'register' : 'login');
  const [authName, setAuthName] = useState('');
  const [authEmail, setAuthEmail] = useState('');
  const [authPassword, setAuthPassword] = useState('');
  const [authError, setAuthError] = useState('');
  const [history, setHistory] = useState<HistoryRow[]>([]);
  const [historyQ, setHistoryQ] = useState('');
  const [historyType, setHistoryType] = useState('');
  const [historyStatus, setHistoryStatus] = useState('');
  const [historyPage, setHistoryPage] = useState(0);
  const [historySize, setHistorySize] = useState(10);
  const [dashboard, setDashboard] = useState<any>(null);
  const [queue, setQueue] = useState<HistoryRow[]>([]);
  const [admin, setAdmin] = useState<any>(null);
  const [adminError, setAdminError] = useState('');
  const [selectedUserId, setSelectedUserId] = useState('');
  const [detailNonce, setDetailNonce] = useState(0);
  const [userDetail, setUserDetail] = useState<any>(null);
  const [roleDraft, setRoleDraft] = useState('');
  const [adminNotice, setAdminNotice] = useState('');
  const [adminSaveError, setAdminSaveError] = useState('');
  const [adminSaving, setAdminSaving] = useState(false);
  const [settingsRecord, setSettingsRecord] = useState<any>(null);
  const [settingsDraft, setSettingsDraft] = useState(true);
  const [settingsSaving, setSettingsSaving] = useState(false);
  const [settingsNotice, setSettingsNotice] = useState('');
  const [settingsError, setSettingsError] = useState('');
  const [submissionsEnabled, setSubmissionsEnabled] = useState(true);
  const [userHistory, setUserHistory] = useState<any[]>([]);
  const [userHistoryTotal, setUserHistoryTotal] = useState(0);
  const [userHistoryPage, setUserHistoryPage] = useState(0);
  const [userHistoryType, setUserHistoryType] = useState('');
  const [userHistoryError, setUserHistoryError] = useState('');
  const [userHistoryLoading, setUserHistoryLoading] = useState(false);
  const detailPanelRef = useRef<HTMLDivElement | null>(null);
  const [editors, setEditors] = useState<Record<string, ClaimEditorEntry>>({});
  const [detailKey, setDetailKey] = useState<TabKey>('TEXT');
  const [detailError, setDetailError] = useState('');

  // Request invalidation: per-tab epoch (new submission / image swap) plus a
  // session epoch (logout or account change). Every awaited response is checked
  // against both before it is allowed to update state.
  const reqEpoch = useRef<Record<TabKey, number>>({TEXT: 0, IMAGE: 0, CLAIM: 0});
  const sessionEpoch = useRef(0);
  const tokenRef = useRef(token);
  tokenRef.current = token;
  const userRef = useRef(user);
  userRef.current = user;

  const setters: Record<TabKey, React.Dispatch<React.SetStateAction<any>>> = {TEXT: setTextTab, CLAIM: setClaimTab, IMAGE: setImageTab};

  function setTabState(key: TabKey, updater: (prev: any) => any) {
    setters[key](updater);
  }

  function getTabState(key: TabKey): TabState {
    return key === 'IMAGE' ? imageTab : key === 'CLAIM' ? claimTab : textTab;
  }

  function isCurrentRequest(key: TabKey, myEpoch: number, mySession: number, myToken: string, myUserId: string): boolean {
    return reqEpoch.current[key] === myEpoch
      && sessionEpoch.current === mySession
      && tokenRef.current === myToken
      && (userRef.current?.id ?? '') === myUserId;
  }

  useEffect(() => () => { if (imageTab.preview) URL.revokeObjectURL(imageTab.preview); }, [imageTab.preview]);

  function signOut() {
    sessionStorage.clear();
    sessionEpoch.current += 1;
    reqEpoch.current = {TEXT: 0, IMAGE: 0, CLAIM: 0};
    setToken('');
    setUser(null);
    setTextTab(EMPTY_TEXT);
    setClaimTab(EMPTY_CLAIM);
    setImageTab(EMPTY_IMAGE);
    setEditors({});
    setHistory([]);
    setDashboard(null);
    setQueue([]);
    setSettingsRecord(null);
    setSettingsDraft(true);
    setSubmissionsEnabled(true);
    setTab(0);
    nav('/login');
  }

  // A session/account change invalidates pending requests and clears private data.
  const lastToken = useRef(token);
  useEffect(() => {
    if (lastToken.current === token) return;
    lastToken.current = token;
    sessionEpoch.current += 1;
    setTextTab(EMPTY_TEXT);
    setClaimTab(EMPTY_CLAIM);
    setImageTab(EMPTY_IMAGE);
    setEditors({});
    setHistory([]);
    setDashboard(null);
    setQueue([]);
    setSettingsRecord(null);
    setSubmissionsEnabled(true);
  }, [token]);

  async function readJson(response: Response, onUnauthorized?: () => void) {
    const data = await response.json().catch(() => ({}));
    if (response.status === 401) {
      if (onUnauthorized) onUnauthorized(); else signOut();
      throw Error('Your session expired. Sign in again.');
    }
    if (!response.ok) throw Error(data.error || 'Request failed');
    return data;
  }

  function rowToResult(row: any): AnalysisResult {
    return {id: row.id, status: row.status, error: row.error_message, input_type: row.input_type, created_at: row.created_at, updated_at: row.updated_at, review_status: row.review_status, reviewable: row.reviewable, reviews: row.reviews, result: row.result_json, claims: row.claims, explanations: row.explanations};
  }

  async function pollAnalysis(key: TabKey, id: string, isCurrent: () => boolean, applyRow: (row: any) => void): Promise<boolean> {
    for (let attempt = 0; attempt < MAX_POLL_ATTEMPTS; attempt++) {
      await new Promise(resolve => setTimeout(resolve, POLL_INTERVAL_MS));
      if (!isCurrent()) return false;
      try {
        const poll = await fetch(`${API}/analysis/${id}`, {headers: authHeaders(tokenRef.current)});
        const row = await readJson(poll, () => signOut());
        if (!isCurrent()) return false;
        applyRow(row);
        if (row.status === 'COMPLETED' || row.status === 'FAILED') return true;
      } catch (e) {
        if (!isCurrent()) return false;
        if (e instanceof Error && e.message.includes('session expired')) return false;
        // Transient poll failure (e.g. 500): keep polling.
      }
    }
    return false;
  }

  function applyRowFor(key: TabKey) {
    return (row: any) => setTabState(key, prev => ({
      ...prev,
      result: rowToResult(row),
      loading: row.status !== 'COMPLETED' && row.status !== 'FAILED',
      timedOut: false,
    }));
  }

  async function submitAnalysis(key: TabKey, path: string, body: BodyInit | null, contentType?: string) {
    if (!token) { nav('/login'); return; }
    const myEpoch = ++reqEpoch.current[key];
    const mySession = sessionEpoch.current;
    const myToken = token;
    const myUserId = user?.id ?? '';
    const isCurrent = () => isCurrentRequest(key, myEpoch, mySession, myToken, myUserId);
    setTabState(key, prev => ({...prev, loading: true, error: '', timedOut: false, result: null}));
    try {
      const headers: Record<string, string> = authHeaders(myToken);
      if (contentType) headers['Content-Type'] = contentType;
      const accepted = await readJson(await fetch(`${API}${path}`, {method: 'POST', headers, body}), () => signOut());
      if (!isCurrent()) return;
      setTabState(key, prev => ({...prev, result: rowToResult(accepted)}));
      if (accepted.id) {
        const finished = await pollAnalysis(key, accepted.id, isCurrent, applyRowFor(key));
        if (!isCurrent()) return;
        if (!finished) setTabState(key, prev => ({...prev, loading: false, timedOut: true}));
      }
    } catch (e) {
      if (!isCurrent()) return;
      setTabState(key, prev => ({...prev, error: e instanceof Error ? e.message : 'The analysis failed.', loading: false}));
    }
  }

  /** Loads an existing analysis (history reopen, deep link, manual re-check) into its tab. */
  async function loadExisting(key: TabKey, id: string, opts: {reset?: boolean} = {}) {
    const myEpoch = ++reqEpoch.current[key];
    const mySession = sessionEpoch.current;
    const myToken = token;
    const myUserId = user?.id ?? '';
    const isCurrent = () => isCurrentRequest(key, myEpoch, mySession, myToken, myUserId);
    if (opts.reset !== false) setTabState(key, prev => ({...prev, loading: true, error: '', timedOut: false, result: null}));
    else setTabState(key, prev => ({...prev, loading: true, error: '', timedOut: false}));
    try {
      const row = await readJson(await fetch(`${API}/analysis/${id}`, {headers: authHeaders(myToken)}), () => signOut());
      if (!isCurrent()) return;
      applyRowFor(key)(row);
      if (row.status !== 'COMPLETED' && row.status !== 'FAILED') {
        const finished = await pollAnalysis(key, id, isCurrent, applyRowFor(key));
        if (!isCurrent()) return;
        if (!finished) setTabState(key, prev => ({...prev, loading: false, timedOut: true}));
      }
    } catch (e) {
      if (!isCurrent()) return;
      setTabState(key, prev => ({...prev, error: e instanceof Error ? e.message : 'Unable to open this result', loading: false}));
    }
  }

  function analyze() {
    void submitAnalysis('TEXT', '/analysis/text', JSON.stringify({title: textTab.title, body: textTab.body}), 'application/json');
  }

  function analyzeClaim() {
    void submitAnalysis('CLAIM', '/analysis/claim', JSON.stringify({claim: claimTab.claim}), 'application/json');
  }

  function analyzeImage() {
    const file = imageTab.image;
    if (!file || !token) { if (!token) nav('/login'); return; }
    const myEpoch = ++reqEpoch.current.IMAGE;
    const mySession = sessionEpoch.current;
    const myToken = token;
    const myUserId = user?.id ?? '';
    const isCurrent = () => isCurrentRequest('IMAGE', myEpoch, mySession, myToken, myUserId);
    setTabState('IMAGE', prev => ({...prev, loading: true, error: '', timedOut: false, result: null}));
    const form = new FormData();
    form.append('file', file, file.name);
    (async () => {
      try {
        const accepted = await readJson(await fetch(`${API}/analysis/image`, {method: 'POST', headers: authHeaders(myToken), body: form}), () => signOut());
        if (!isCurrent()) return;
        setTabState('IMAGE', prev => ({...prev, result: rowToResult(accepted)}));
        if (accepted.id) {
          const finished = await pollAnalysis('IMAGE', accepted.id, isCurrent, applyRowFor('IMAGE'));
          if (!isCurrent()) return;
          if (!finished) setTabState('IMAGE', prev => ({...prev, loading: false, timedOut: true}));
        }
      } catch (e) {
        if (!isCurrent()) return;
        setTabState('IMAGE', prev => ({...prev, error: e instanceof Error ? e.message : 'The image analysis failed.', loading: false}));
      }
    })();
  }

  function chooseImage(file: File | null) {
    // Replacing or removing an image invalidates its pending responses.
    reqEpoch.current.IMAGE += 1;
    setImageTab(prev => ({...prev, image: null, preview: '', result: null, error: '', timedOut: false, loading: false}));
    if (!file) return;
    if (!IMAGE_TYPES.has(file.type)) { setImageTab(prev => ({...prev, error: 'Choose a JPG, JPEG, or PNG image.'})); return; }
    if (file.size > MAX_IMAGE_BYTES) { setImageTab(prev => ({...prev, error: 'Images must be 10 MB or smaller.'})); return; }
    setImageTab(prev => ({...prev, image: file, preview: URL.createObjectURL(file)}));
  }

  function onPicker(e: ChangeEvent<HTMLInputElement>) { chooseImage(e.target.files?.[0] || null); e.target.value = ''; }
  function onDrop(e: DragEvent<HTMLDivElement>) { e.preventDefault(); setImageTab(prev => ({...prev, dragging: false})); chooseImage(e.dataTransfer.files?.[0] || null); }

  /** Editor entries are keyed by analysis id: switching analyses resets the editor. */
  function syncEditor(key: TabKey) {
    const state = key === 'TEXT' ? textTab : claimTab;
    const id = state.result?.id;
    if (!id) return;
    setEditors(prev => {
      if (prev[id]) return prev; // never overwrite unsaved edits
      const claims = displayedClaims(state.result);
      if (claims.length === 0) {
        // Claims may arrive after the accepted response; only materialize a
        // manual-entry placeholder once the analysis has completed.
        if (state.result?.status === 'COMPLETED') return {...prev, [id]: {draft: '', baseline: ''}};
        return prev;
      }
      const draft = draftFromClaims(claims);
      return {...prev, [id]: {draft, baseline: draft}};
    });
  }

  useEffect(() => { syncEditor('TEXT'); }, [textTab.result?.id, claimsKey(textTab.result), textTab.result?.status]);
  useEffect(() => { syncEditor('CLAIM'); }, [claimTab.result?.id, claimsKey(claimTab.result), claimTab.result?.status]);

  function editorDraftChange(key: TabKey, id: string, draft: string) {
    if (!id) return;
    setEditors(prev => {
      const existing = prev[id];
      if (existing) return {...prev, [id]: {...existing, draft}};
      const claims = displayedClaims((key === 'TEXT' ? textTab : claimTab).result);
      return {...prev, [id]: {draft, baseline: draftFromClaims(claims)}};
    });
  }

  async function saveClaims(key: TabKey) {
    const state = key === 'TEXT' ? textTab : claimTab;
    const id = state.result?.id;
    const entry = id ? editors[id] : undefined;
    if (!id || !entry || reverifyDisabled(entry, state.loading)) return;
    const lines = parseClaimLines(entry.draft).slice(0, 3);
    const myEpoch = ++reqEpoch.current[key];
    const mySession = sessionEpoch.current;
    const myToken = token;
    const myUserId = user?.id ?? '';
    const isCurrent = () => isCurrentRequest(key, myEpoch, mySession, myToken, myUserId);
    setTabState(key, prev => ({...prev, loading: true, error: ''}));
    try {
      // Backend enforces ownership and the max-claims limit.
      await readJson(await fetch(`${API}/analysis/${id}/claims`, {method: 'POST', headers: {'Content-Type': 'application/json', ...authHeaders(myToken)}, body: JSON.stringify({claims: lines})}), () => signOut());
      if (!isCurrent()) return;
      const fresh = await readJson(await fetch(`${API}/analysis/${id}`, {headers: authHeaders(myToken)}), () => signOut());
      if (!isCurrent()) return;
      setTabState(key, prev => ({...prev, result: rowToResult(fresh), loading: fresh.status !== 'COMPLETED' && fresh.status !== 'FAILED', timedOut: false}));
      setEditors(prev => ({...prev, [id]: {draft: lines.join('\n'), baseline: lines.join('\n')}}));
    } catch (e) {
      if (!isCurrent()) return;
      setTabState(key, prev => ({...prev, error: e instanceof Error ? e.message : 'Re-verification failed.', loading: false}));
    }
  }

  async function requestReview(key: TabKey) {
    const id = getTabState(key).result?.id;
    if (!id) return;
    try {
      await readJson(await fetch(`${API}/analysis/${id}/review-request`, {method: 'POST', headers: authHeaders(token)}), () => signOut());
    } catch { /* surfaced via alert on next load */ }
  }

  async function submitReview(id: string, status: string, notes: string) {
    setDetailError('');
    try {
      await readJson(await fetch(`${API}/reviews/${id}`, {method: 'POST', headers: {'Content-Type': 'application/json', ...authHeaders(token)}, body: JSON.stringify({status, notes})}), () => signOut());
      await loadExisting(detailKey, id);
    } catch (e) {
      setDetailError(e instanceof Error ? e.message : 'Unable to record the review note');
    }
  }

  async function submitAuth() {
    setAuthError('');
    try {
      const path = authMode === 'register' ? '/users/register' : '/users/login';
      const payload = authMode === 'register' ? {name: authName, email: authEmail, password: authPassword} : {email: authEmail, password: authPassword};
      const response = await fetch(`${API}${path}`, {method: 'POST', headers: {'Content-Type': 'application/json'}, body: JSON.stringify(payload)});
      const data = await response.json();
      if (!response.ok) throw Error(data.error || 'Authentication failed');
      if (authMode === 'register') {
        setAuthMode('login');
        setAuthError('Registration succeeded. Sign in to continue.');
        return;
      }
      sessionStorage.setItem('truthlens_token', data.token);
      sessionStorage.setItem('truthlens_user', JSON.stringify(data.user));
      setToken(data.token);
      setUser(data.user);
      nav('/');
    } catch (e) {
      setAuthError(e instanceof Error ? e.message : 'Authentication failed');
    }
  }

  useEffect(() => {
    if (token) {
      fetch(`${API}/users/profile`, {headers: authHeaders(token)}).then(async r => {
        if (r.status === 401) { signOut(); return; }
        const profile = await r.json();
        setUser(profile);
        sessionStorage.setItem('truthlens_user', JSON.stringify(profile));
      }).catch(() => {});
    }
  }, [token]);

  useEffect(() => {
    if (!token && !['/login', '/register'].includes(location.pathname)) nav('/login');
  }, [token, location.pathname]);

  useEffect(() => {
    if (location.pathname === '/history' && token) {
      const params = new URLSearchParams();
      if (historyQ) params.set('q', historyQ);
      if (historyType) params.set('type', historyType);
      if (historyStatus) params.set('status', historyStatus);
      params.set('page', String(historyPage));
      params.set('size', String(historySize));
      fetch(`${API}/analysis?${params.toString()}`, {headers: authHeaders(token)}).then(async r => {
        if (r.status === 401) { signOut(); return; }
        setHistory(await r.json());
      }).catch(() => setHistory([]));
    }
  }, [location.pathname, token, historyQ, historyType, historyStatus, historyPage, historySize]);

  useEffect(() => {
    if (location.pathname === '/dashboard' && token) {
      fetch(`${API}/dashboard`, {headers: authHeaders(token)}).then(async r => setDashboard(await readJson(r))).catch(() => setDashboard(null));
    }
  }, [location.pathname, token]);

  useEffect(() => {
    if (location.pathname === '/reviews' && token) {
      fetch(`${API}/reviews/queue`, {headers: authHeaders(token)}).then(async r => {
        if (r.status === 403) { setQueue([]); return; }
        setQueue(await readJson(r));
      }).catch(() => setQueue([]));
    }
  }, [location.pathname, token]);

  useEffect(() => {
    if (location.pathname === '/admin' && token && user?.role === 'ADMIN') {
      setAdminError('');
      void refreshAdminData();
    }
  }, [location.pathname, token, user?.role]);

  async function refreshAdminData() {
    try {
      const [users, models, health] = await Promise.all([
        fetch(`${API}/admin/users`, {headers: authHeaders(tokenRef.current)}).then(r => readJson(r)),
        fetch(`${API}/admin/models`, {headers: authHeaders(tokenRef.current)}).then(r => readJson(r)),
        fetch(`${API}/admin/health`, {headers: authHeaders(tokenRef.current)}).then(r => readJson(r)),
      ]);
      setAdmin({users, models, health});
      return users as any[];
    } catch (e) {
      setAdmin(null);
      setAdminError(e instanceof Error ? e.message : 'Unable to load administrative data');
      return [];
    }
  }

  // The operational settings are a separate record: a failure to load them must not hide the
  // user list, and saving them must not resend the whole administration payload.
  useEffect(() => {
    if (location.pathname !== '/admin' || !token || user?.role !== 'ADMIN') return;
    setSettingsError('');
    fetch(`${API}/admin/settings`, {headers: authHeaders(token)})
      .then(r => readJson(r))
      .then(data => {
        setSettingsRecord(data);
        const enabled = data?.submissionsEnabled !== false;
        setSettingsDraft(enabled);
        setSubmissionsEnabled(enabled);
      })
      .catch(e => {
        setSettingsRecord(null);
        setSettingsError(e instanceof Error ? e.message : 'Unable to load the system settings.');
      });
  }, [location.pathname, token, user?.role]);

  // A signed-in user is told about a pause before submitting, not after being refused.
  useEffect(() => {
    if (location.pathname !== '/' || !token) return;
    fetch(`${API}/system/status`, {headers: authHeaders(token)})
      .then(r => readJson(r))
      .then(data => setSubmissionsEnabled(data?.submissionsEnabled !== false))
      .catch(() => {});
  }, [location.pathname, token]);

  async function saveSubmissionsSetting() {
    setSettingsSaving(true);
    setSettingsNotice('');
    setSettingsError('');
    try {
      const updated = await readJson(await fetch(`${API}/admin/settings`, {
        method: 'PATCH',
        headers: {'Content-Type': 'application/json', ...authHeaders(tokenRef.current)},
        body: JSON.stringify({submissionsEnabled: settingsDraft}),
      }));
      setSettingsRecord(updated);
      const enabled = updated?.submissionsEnabled !== false;
      setSettingsDraft(enabled);
      setSubmissionsEnabled(enabled);
      setSettingsNotice(enabled
        ? 'New analyses are accepted again. The change is stored and applies to every account at once.'
        : 'New analyses are paused. Existing reports stay available. The change is stored and applies to every account at once.');
    } catch (e) {
      setSettingsError(e instanceof Error ? e.message : 'The setting change was rejected.');
    } finally {
      setSettingsSaving(false);
    }
  }

  function openUser(id: string) {
    setSelectedUserId(id);
    setUserDetail(null);
    setRoleDraft('');
    setAdminNotice('');
    setAdminSaveError('');
    setUserHistoryPage(0);
    setUserHistoryType('');
    setUserHistory([]);
    setUserHistoryTotal(0);
    setUserHistoryError('');
    // Re-selecting the same row must still reload it, so the fetch is keyed on a counter too.
    setDetailNonce(n => n + 1);
  }

  useEffect(() => {
    if (location.pathname !== '/admin' || !token || !selectedUserId) return;
    let current = true;
    fetch(`${API}/admin/users/${selectedUserId}`, {headers: authHeaders(token)})
      .then(r => readJson(r))
      .then(row => { if (current) { setUserDetail(row); setRoleDraft(row.role); setAdminSaveError(''); } })
      .catch(e => { if (current) { setUserDetail(null); setAdminSaveError(e instanceof Error ? e.message : 'Unable to load this account.'); } });
    return () => { current = false; };
  }, [selectedUserId, detailNonce, location.pathname, token]);

  // ADMIN-only, owner-filtered history for the selected account. The server caps the page size.
  useEffect(() => {
    if (location.pathname !== '/admin' || !token || !selectedUserId) return;
    let current = true;
    const params = new URLSearchParams({page: String(userHistoryPage), size: '10'});
    if (userHistoryType) params.set('type', userHistoryType);
    setUserHistoryLoading(true);
    fetch(`${API}/admin/users/${selectedUserId}/analyses?${params.toString()}`, {headers: authHeaders(token)})
      .then(r => readJson(r))
      .then(data => {
        if (!current) return;
        setUserHistory(data?.rows ?? []);
        setUserHistoryTotal(Number(data?.total ?? 0));
        setUserHistoryError('');
      })
      .catch(e => {
        if (!current) return;
        setUserHistory([]);
        setUserHistoryTotal(0);
        setUserHistoryError(e instanceof Error ? e.message : 'Unable to load this account\'s analyses.');
      })
      .finally(() => { if (current) setUserHistoryLoading(false); });
    return () => { current = false; };
  }, [selectedUserId, detailNonce, userHistoryPage, userHistoryType, location.pathname, token]);

  // Scroll the selected row and its freshly opened record in together, so the row never ends up
  // above the fold while its details sit below it.
  useEffect(() => {
    const panel = detailPanelRef.current;
    if (!selectedUserId || !panel) return;
    panel.focus({preventScroll: true});
    (panel.parentElement ?? panel).scrollIntoView({block: 'start'});
  }, [selectedUserId, detailNonce, userDetail]);

  async function saveUserRole() {
    if (!userDetail) return;
    setAdminSaving(true);
    setAdminNotice('');
    setAdminSaveError('');
    try {
      const updated = await readJson(await fetch(`${API}/admin/users/${userDetail.id}`, {
        method: 'PATCH',
        headers: {'Content-Type': 'application/json', ...authHeaders(tokenRef.current)},
        body: JSON.stringify({role: roleDraft}),
      }));
      setUserDetail(updated);
      setRoleDraft(updated.role);
      const users = await refreshAdminData();
      const others = users.filter((row: any) => row.id === updated.id && row.role === updated.role).length;
      setAdminNotice(others
        ? `${updated.name || updated.email} is now ${updated.role}. Backend authorization uses this role immediately; they must sign out and sign in again for the role shown in their own interface to update.`
        : `${updated.name || updated.email} is now ${updated.role}, but the refreshed list did not confirm it. Reload this page to re-check.`);
    } catch (e) {
      setAdminSaveError(e instanceof Error ? e.message : 'The role change was rejected.');
      await refreshAdminData();
    } finally {
      setAdminSaving(false);
    }
  }

  // History reopening: load the analysis into the tab matching its input type.
  useEffect(() => {
    const state = location.state as {openAnalysis?: string; openType?: string} | null;
    if (!state?.openAnalysis) return;
    const id = state.openAnalysis;
    nav('.', {replace: true, state: {}});
    if (!token) return;
    const key: TabKey = state.openType === 'IMAGE' ? 'IMAGE' : state.openType === 'CLAIM' ? 'CLAIM' : 'TEXT';
    setTab(tabIndexForType(state.openType));
    setDetailKey(key);
    void loadExisting(key, id);
  }, [location.state, token]);

  // Analysis deep link: fetch once to learn the type, then load into that tab.
  useEffect(() => {
    if (!(location.pathname.startsWith('/analysis/') && params.id && token)) return;
    const id = params.id;
    let cancelled = false;
    setDetailError('');
    (async () => {
      try {
        const probe = await readJson(await fetch(`${API}/analysis/${id}`, {headers: authHeaders(token)}), () => signOut());
        if (cancelled) return;
        const key: TabKey = probe.input_type === 'IMAGE' ? 'IMAGE' : probe.input_type === 'CLAIM' ? 'CLAIM' : 'TEXT';
        setDetailKey(key);
        setTab(tabIndexForType(probe.input_type));
        await loadExisting(key, id);
      } catch (e) {
        if (!cancelled) setDetailError(e instanceof Error ? e.message : 'Unable to open this result');
      }
    })();
    return () => { cancelled = true; };
  }, [location.pathname, params.id, token]);

  const navBar = (
    <AppBar position="sticky" elevation={0}>
      <Toolbar sx={{flexWrap: 'wrap', rowGap: .5}}>
        <Typography variant="h6" sx={{flexGrow: 1, fontWeight: 800, mr: 1}}>TruthLens <span className="muted">AI</span></Typography>
        {user && <Typography sx={{mr: 2}}>{user.name} · {user.role}</Typography>}
        <Box sx={{display: 'flex', flexWrap: 'wrap', columnGap: .25}}>
          <Button color="inherit" onClick={() => nav('/')}>Analyze</Button>
          <Button color="inherit" onClick={() => nav('/history')}>History</Button>
          <Button color="inherit" onClick={() => nav('/dashboard')}>Dashboard</Button>
          {(user?.role === 'FACT_CHECKER' || user?.role === 'ADMIN') && <Button color="inherit" onClick={() => nav('/reviews')}>Reviews</Button>}
          {user?.role === 'ADMIN' && <Button color="inherit" onClick={() => nav('/admin')}>Admin</Button>}
          <Button color="inherit" onClick={() => nav('/profile')}>Profile</Button>
          {user ? <Button color="inherit" onClick={signOut}>Log out</Button> : <Button color="inherit" onClick={() => nav('/login')}>Sign in</Button>}
        </Box>
      </Toolbar>
    </AppBar>
  );

  if (location.pathname === '/login' || location.pathname === '/register') {
    return (
      <Container maxWidth="sm">
        <Card className="panel" sx={{mt: 8}}>
          <CardContent>
            <Typography variant="h4" sx={{mb: 2}}>{authMode === 'register' ? 'Create your account' : 'Sign in to TruthLens'}</Typography>
            {authError && <Alert severity={authMode === 'register' && authError.includes('succeeded') ? 'success' : 'error'} sx={{mb: 2}}>{authError}</Alert>}
            {authMode === 'register' && <TextField fullWidth label="Name" value={authName} onChange={e => setAuthName(e.target.value)} sx={{mb: 2}} />}
            <TextField fullWidth label="Email" type="email" value={authEmail} onChange={e => setAuthEmail(e.target.value)} sx={{mb: 2}} />
            <TextField fullWidth label="Password" type="password" value={authPassword} onChange={e => setAuthPassword(e.target.value)} sx={{mb: 2}} helperText="At least 8 characters. Guest submission is not enabled." />
            <Button fullWidth variant="contained" onClick={submitAuth}>{authMode === 'register' ? 'Register' : 'Sign in'}</Button>
            <Button fullWidth sx={{mt: 1}} onClick={() => { setAuthMode(authMode === 'register' ? 'login' : 'register'); setAuthError(''); }}>{authMode === 'register' ? 'Already have an account? Sign in' : 'Create an account'}</Button>
          </CardContent>
        </Card>
      </Container>
    );
  }

  if (!token) return null;

  if (location.pathname === '/profile') {
    return <>{navBar}<Container maxWidth="sm"><Card className="panel" sx={{mt: 4}}><CardContent>
      <Typography variant="h4">Profile</Typography>
      <Typography sx={{mt: 2}}>{user?.name}</Typography>
      <Typography color="text.secondary">{user?.email}</Typography>
      <Chip label={user?.role} sx={{mt: 2}} />
      <Button sx={{mt: 3}} variant="outlined" onClick={signOut}>Log out</Button>
    </CardContent></Card></Container></>;
  }

  if (location.pathname === '/history') {
    return <>{navBar}<Container maxWidth="md"><Card className="panel" sx={{mt: 4}}><CardContent>
      <Typography variant="h4" sx={{mb: 2}}>Your history</Typography>
      <Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{mb: 2}}>
        <TextField fullWidth label="Search" value={historyQ} onChange={e => { setHistoryQ(e.target.value); setHistoryPage(0); }} />
        <TextField select size="small" label="Type" value={historyType} onChange={e => { setHistoryType(e.target.value); setHistoryPage(0); }} helperText="Filter by analysis type" sx={{minWidth: 150}}>
          {HISTORY_TYPE_OPTIONS.map(option => <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>)}
        </TextField>
        <TextField label="Status" value={historyStatus} onChange={e => { setHistoryStatus(e.target.value); setHistoryPage(0); }} helperText="Empty for all statuses" />
      </Stack>
      {history.length === 0 ? <Typography color="text.secondary">No analyses found.</Typography> : history.map(row => (
        <Box key={row.id} sx={{p: 2, borderBottom: '1px solid #ddd', cursor: 'pointer'}} onClick={() => nav(`/analysis/${row.id}`)}>
          <Typography fontWeight={700}>{row.input_type} · {row.status}</Typography>
          <Typography variant="caption" color="text.secondary">{row.created_at} · {row.id}</Typography>
        </Box>
      ))}
      <Stack direction="row" spacing={2} sx={{mt: 2, alignItems: 'center'}}>
        <Button size="small" disabled={historyPage === 0} onClick={() => setHistoryPage(p => Math.max(0, p - 1))}>Previous</Button>
        <Typography variant="caption">Page {historyPage + 1} · {historySize} per page</Typography>
        <Button size="small" disabled={history.length < historySize} onClick={() => setHistoryPage(p => p + 1)}>Next</Button>
        <TextField select size="small" label="Size" value={historySize} onChange={e => { setHistorySize(Number(e.target.value)); setHistoryPage(0); }} sx={{minWidth: 90}}>
          {( [5, 10, 20, 50]).map(n => <MenuItem key={n} value={n}>{n}</MenuItem>)}
        </TextField>
      </Stack>
    </CardContent></Card></Container></>;
  }

  if (location.pathname === '/dashboard') {
    const statuses = dashboard?.by_status ?? [];
    const total = statuses.reduce((sum: number, s: any) => sum + (Number(s.total) || 0), 0);
    const byType = dashboard?.by_type ?? [];
    const typeCount = (t: string) => Number(byType.find((x: any) => x.input_type === t)?.total || 0);
    return <>{navBar}<Container maxWidth="md"><Card className="panel" sx={{mt: 4}}><CardContent>
      <Typography variant="h4">Dashboard</Typography>
      <Typography color="text.secondary" sx={{mb: 2}}>Live PostgreSQL counts for records you are authorized to see. Text, image, and verification outcomes are kept separate. Scope: {dashboard?.scope || 'unknown'}.</Typography>
      {!dashboard ? <Typography color="text.secondary">Loading dashboard…</Typography> : <>
        <Stack direction="row" spacing={2} sx={{mb: 2}} flexWrap="wrap">
          <Box sx={{px: 2, py: 1, borderRadius: 2, bgcolor: '#e8eaf6', minWidth: 110}}><Typography variant="caption" color="text.secondary">Total analyses</Typography><Typography variant="h6">{total}</Typography></Box>
          <Box sx={{px: 2, py: 1, borderRadius: 2, bgcolor: '#ede7f6', minWidth: 110}}><Typography variant="caption" color="text.secondary">Text</Typography><Typography variant="h6">{typeCount('TEXT')}</Typography></Box>
          <Box sx={{px: 2, py: 1, borderRadius: 2, bgcolor: '#e0f2f1', minWidth: 110}}><Typography variant="caption" color="text.secondary">Image</Typography><Typography variant="h6">{typeCount('IMAGE')}</Typography></Box>
          <Box sx={{px: 2, py: 1, borderRadius: 2, bgcolor: '#fff3e0', minWidth: 110}}><Typography variant="caption" color="text.secondary">Claims</Typography><Typography variant="h6">{typeCount('CLAIM')}</Typography></Box>
          <Box sx={{px: 2, py: 1, borderRadius: 2, bgcolor: '#fce4ec', minWidth: 110}}><Typography variant="caption" color="text.secondary">Failed</Typography><Typography variant="h6">{Number(statuses.find((s: any) => s.status === 'FAILED')?.total || 0)}</Typography></Box>
        </Stack>
        <Breakdown title="Processing status" rows={statuses} valueKey="total" color="#5c6bc0" />
        <Breakdown title="Text WELFake outcomes" rows={dashboard?.text_labels} valueKey="total" color="#26a69a" />
        <Breakdown title="Image verdicts (FaceForensics++)" rows={dashboard?.image_verdicts} valueKey="total" color="#ef5350" />
        <Breakdown title="Evidence verification verdicts (claims)" rows={dashboard?.verification_verdicts} valueKey="total" color="#ab47bc" />
        <TrendStrip rows={dashboard?.daily_trend} />
      </>}
    </CardContent></Card></Container></>;
  }

  if (location.pathname.startsWith('/analysis/') && params.id) {
    const state = getTabState(detailKey);
    const id = state.result?.id || params.id || '';
    const canReview = user?.role === 'FACT_CHECKER' || user?.role === 'ADMIN';
    return <>{navBar}<Container maxWidth="md"><Card className="panel" sx={{mt: 4}}><CardContent>
      <Typography variant="h4">Analysis report</Typography>
      <Typography variant="caption" color="text.secondary">ID {params.id}</Typography>
      {detailError && <Alert severity="error" sx={{mt: 2}}>{detailError}</Alert>}
      {state.error && <Alert severity="error" sx={{mt: 2}}>{state.error}</Alert>}
      <Box sx={{mt: 2}}><ReportHeader state={state} /></Box>
      {detailKey === 'TEXT' && <TextResultPanel
        state={state}
        editorEntry={editors[id]}
        onEditorDraft={d => editorDraftChange('TEXT', id, d)}
        onReverify={() => saveClaims('TEXT')}
        onCheckAgain={() => state.result?.id && void loadExisting(detailKey, state.result.id)}
        onReviewRequest={() => void requestReview('TEXT')}
      />}
      {detailKey === 'CLAIM' && <ClaimResultPanel
        state={state}
        editorEntry={editors[id]}
        onEditorDraft={d => editorDraftChange('CLAIM', id, d)}
        onReverify={() => saveClaims('CLAIM')}
        onCheckAgain={() => state.result?.id && void loadExisting(detailKey, state.result.id)}
      />}
      {detailKey === 'IMAGE' && <ImageResultPanel state={state} preview={imageTab.preview} onCheckAgain={() => state.result?.id && void loadExisting('IMAGE', state.result.id)} />}
      <HumanReviewSection result={state.result} />
      {canReview && state.result?.id && <ReviewForm onSubmit={async (status, notes) => void submitReview(state.result!.id!, status, notes)} />}
    </CardContent></Card></Container></>;
  }

  if (location.pathname === '/reviews') {
    return <>{navBar}<Container maxWidth="md"><Card className="panel" sx={{mt: 4}}><CardContent>
      <Typography variant="h4">Review queue</Typography>
      <Typography color="text.secondary" sx={{mb: 2}}>Fact-checker reviews do not overwrite model predictions.</Typography>
      {queue.map(row => <Box key={row.id} sx={{p: 2, borderBottom: '1px solid #ddd', cursor: 'pointer'}} onClick={() => nav(`/analysis/${row.id}`)}><Typography fontWeight={700}>{row.input_type} · {row.status}</Typography></Box>)}
    </CardContent></Card></Container></>;
  }

  if (location.pathname === '/admin') {
    if (user?.role !== 'ADMIN') {
      return <>{navBar}<Container maxWidth="md"><Alert severity="error" sx={{mt: 4}}>Administrator access required.</Alert></Container></>;
    }
    const aiComponents = admin?.health?.aiService?.components || {};
    const versions = modelRows(aiComponents);
    const hasVersion = versions.some(row => row.version !== 'Unavailable');
    const users = admin?.users ?? [];
    const blocked = saveBlockedReason(userDetail, user?.id ?? '', users, roleDraft);
    const changed = !!userDetail && roleDraft !== userDetail.role;
    return <>{navBar}<Container maxWidth="lg"><Card className="panel" sx={{mt: 4}}><CardContent>
      <Typography variant="h4">Administration</Typography>
      {adminError && <Alert severity="error" sx={{mt: 2}}>{adminError}</Alert>}
      {!admin ? <Typography color="text.secondary" sx={{mt: 2}}>Loading…</Typography> : <>
        <Typography variant="h6" sx={{mt: 2}}>Service health</Typography>
        <Typography variant="body2" color="text.secondary">Backend: {admin.health?.status || 'unknown'} · AI service: {admin.health?.aiService?.status || 'unknown'}</Typography>
        <Typography variant="h6" sx={{mt: 2}}>{hasVersion ? 'Model versions' : 'AI component status'}</Typography>
        {versions.length === 0 && <Typography variant="body2" color="text.secondary">AI component metadata is unavailable.</Typography>}
        {versions.map(row => <Box key={row.name} sx={{mt: 1}}>
          <Typography variant="body2">
            <strong>{row.name}</strong> · {row.status}
            {' · version: '}{row.version}
            {row.version === 'Unavailable' && ' (the AI service publishes no version identifier for this component)'}
          </Typography>
          {row.detail && <Typography variant="caption" color="text.secondary">artifact: {row.detail}</Typography>}
        </Box>)}
        {!hasVersion && versions.length > 0 && <Alert severity="warning" sx={{mt: 1}} variant="outlined">Model-version display is incomplete: only availability is published by the AI service.</Alert>}
        <Typography variant="h6" sx={{mt: 3}}>System controls</Typography>
        {!settingsRecord ? (
          <Typography variant="body2" color="text.secondary">The system settings are unavailable{settingsError ? `: ${settingsError}` : '.'}</Typography>
        ) : <>
          <FormControlLabel
            control={<Switch checked={settingsDraft} onChange={e => setSettingsDraft(e.target.checked)} inputProps={{'aria-label': 'Accept new analyses'}} />}
            label={<Typography variant="body2">Accept new analyses</Typography>} />
          <Typography variant="body2" color="text.secondary" sx={{display: 'block'}}>
            {submissionsEnabled ? 'Currently accepting new text, image and claim analyses.' : 'Currently paused: new analyses are refused, existing reports stay available.'}
            {' '}Last change: {formatDateTime(settingsRecord.updatedAt)}
            {settingsRecord.updatedByName ? ` by ${settingsRecord.updatedByName}` : ''}.
          </Typography>
          <Stack direction="row" spacing={2} useFlexGap sx={{mt: 1, alignItems: 'center', flexWrap: 'wrap'}}>
            <Button variant="contained" onClick={() => void saveSubmissionsSetting()} disabled={settingsSaving || settingsDraft === (settingsRecord.submissionsEnabled !== false)}>
              {settingsSaving ? 'Saving…' : settingsDraft ? 'Resume new analyses' : 'Pause new analyses'}
            </Button>
            {settingsDraft !== (settingsRecord.submissionsEnabled !== false) && <Typography variant="caption" color="text.secondary">Not applied until you press the button.</Typography>}
          </Stack>
          {settingsNotice && <Alert severity="success" sx={{mt: 1}}>{settingsNotice}</Alert>}
          {settingsError && <Alert severity="error" sx={{mt: 1}}>{settingsError}</Alert>}
          <Typography variant="caption" color="text.secondary" sx={{display: 'block', mt: 1}}>
            The backend reads this setting from the database on every submission, so it takes effect immediately for all accounts.
            Model thresholds and claim limits are deliberately not editable here: they are fixed by the verified artefacts the published accuracy figures depend on.
          </Typography>
        </>}
        <Typography variant="h6" sx={{mt: 3}}>Users ({users.length})</Typography>
        <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>Select a user to open the account record, change its role, and review that account&apos;s analyses.</Typography>
        {users.map((u: any) => <Box key={u.id}>
          <Box role="button" tabIndex={0} aria-expanded={u.id === selectedUserId}
            onClick={() => openUser(u.id)}
            onKeyDown={e => { if (e.key === 'Enter' || e.key === ' ') { e.preventDefault(); openUser(u.id); } }}
            sx={{p: 1, borderBottom: '1px solid #eee', cursor: 'pointer', background: u.id === selectedUserId ? 'rgba(25,118,210,0.08)' : undefined}}>
            <Typography fontWeight={700}>{u.name} · {u.role}{u.active === false ? ' (inactive)' : ''}</Typography>
            <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>{u.email}</Typography>
          </Box>
          {u.id === selectedUserId && <Box ref={detailPanelRef} tabIndex={-1} sx={{p: 2, my: 1, border: '1px solid #ddd', borderRadius: 1, outline: 'none'}}>
            {!userDetail ? <Typography color="text.secondary">Loading account…</Typography> : <>
              <Typography variant="h6">Account details</Typography>
              <Typography variant="body2">Name: {userDetail.name ?? 'Unavailable'}</Typography>
              <Typography variant="body2">Email: {userDetail.email ?? 'Unavailable'}</Typography>
              <Typography variant="body2">User ID: {userDetail.id}</Typography>
              <Typography variant="body2">Current role: {userDetail.role ?? 'Unavailable'}</Typography>
              <Typography variant="body2">Account status: {userDetail.active === false ? 'inactive' : 'active'}</Typography>
              <Typography variant="body2">Created: {formatDateTime(userDetail.created_at)}</Typography>
              <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>
                The account API never returns password material. The analysis list below shows status summaries only; opening a row uses the same report authorization as every other report.
              </Typography>
              {selfChangeBlocked(userDetail, user?.id ?? '')
                ? <Alert severity="info" sx={{mt: 1}}>This is your own account. Administrators cannot change their own role or status.</Alert>
                : <>
                  <TextField select label="Role" size="small" value={roleDraft} onChange={e => setRoleDraft(e.target.value)}
                    helperText="The change is applied only when you press Save." sx={{mt: 1, width: {xs: '100%', sm: 240}}}>
                    {roleChoices(userDetail, user?.id ?? '', users).map(choice => <MenuItem key={choice.role} value={choice.role} disabled={choice.disabled}>
                      {choice.role}{choice.disabled ? ` — ${choice.reason}` : ''}
                    </MenuItem>)}
                  </TextField>
                  <Stack direction="row" spacing={2} useFlexGap sx={{mt: 1, alignItems: 'center', flexWrap: 'wrap'}}>
                    <Button variant="contained" onClick={() => void saveUserRole()} disabled={adminSaving || !changed || blocked !== ''}>
                      {adminSaving ? 'Saving…' : 'Save role'}
                    </Button>
                    {changed && blocked !== '' && <Typography variant="caption" color="error">{blocked}</Typography>}
                  </Stack>
                  <Typography variant="caption" color="text.secondary" sx={{display: 'block', mt: 1}}>
                    The backend reads each user&apos;s role from the database on every request, so the new role is enforced immediately.
                    The changed user still has to sign out and sign in again for the role shown in their own interface to refresh.
                  </Typography>
                </>}
              {adminNotice && <Alert severity="success" sx={{mt: 1}}>{adminNotice}</Alert>}
              {adminSaveError && <Alert severity="error" sx={{mt: 1}}>{adminSaveError}</Alert>}
              <Typography variant="h6" sx={{mt: 3}}>Analyses</Typography>
              <Stack direction={{xs: 'column', sm: 'row'}} spacing={1} sx={{mt: 1}} alignItems={{xs: 'stretch', sm: 'center'}}>
                <TextField select size="small" label="Type" value={userHistoryType}
                  onChange={e => { setUserHistoryType(e.target.value); setUserHistoryPage(0); }}
                  sx={{width: {xs: '100%', sm: 170}}}>
                  {HISTORY_TYPE_OPTIONS.map(option => <MenuItem key={option.value} value={option.value}>{option.label}</MenuItem>)}
                </TextField>
                <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>
                  {userHistoryLoading ? 'Loading analyses…' : historyPageFooter(userHistoryPage, 10, userHistoryTotal, userHistory.length)}
                </Typography>
              </Stack>
              {userHistoryError && <Alert severity="error" sx={{mt: 1}}>{userHistoryError}</Alert>}
              {!userHistoryError && !userHistoryLoading && userHistory.length === 0 && <Typography variant="body2" color="text.secondary" sx={{mt: 1}}>This account has no analyses.</Typography>}
              {userHistory.map((row: any) => <Box key={row.id} sx={{py: 1, borderBottom: '1px solid #eee'}}>
                <Button size="small" sx={{textTransform: 'none'}} onClick={() => nav(`/analysis/${row.id}`)}>{typeLabel(row.input_type)} · {row.status}</Button>
                <Typography variant="caption" color="text.secondary" sx={{display: 'block'}}>
                  {formatDateTime(row.created_at)} · review: {row.review_status || 'none'} · {row.id}
                </Typography>
              </Box>)}
              <Stack direction="row" spacing={1} useFlexGap sx={{mt: 1, alignItems: 'center', flexWrap: 'wrap'}}>
                <Button size="small" disabled={userHistoryPage === 0 || userHistoryLoading} onClick={() => setUserHistoryPage(p => Math.max(0, p - 1))}>Previous</Button>
                <Button size="small" disabled={!hasNextHistoryPage(userHistoryPage, 10, userHistoryTotal, userHistory.length) || userHistoryLoading} onClick={() => setUserHistoryPage(p => p + 1)}>Next</Button>
              </Stack>
            </>}
          </Box>}
        </Box>)}
      </>}
    </CardContent></Card></Container></>;
  }

  return <>
    {navBar}
    <Container maxWidth="lg">
      <Box className="hero">
        <Chip label="Evidence-aware content assessment" color="primary" />
        <Typography variant="h2">Pause before you share.</Typography>
        <Typography variant="h6" color="text.secondary">Analyze an article or image with transparent model signals and uncertainty. Sign-in is required; guest submission is not enabled.</Typography>
      </Box>
      {!submissionsEnabled && <Alert severity="warning" sx={{mt: 2}}>New analyses are paused by the administrator. Existing reports remain available.</Alert>}
      <Paper className="mode-tabs">
        <Tabs value={tab} onChange={(_, v) => setTab(v)} aria-label="analysis type" variant="scrollable" allowScrollButtonsMobile>
          <Tab label="Text Analysis" />
          <Tab label="Deepfake Image Analysis" />
          <Tab label="Claim verification" />
        </Tabs>
      </Paper>
      {tab === 0 && <Stack direction={{xs: 'column', md: 'row'}} spacing={3} sx={{mt: 3}}>
        <Card className="panel"><CardContent>
          <Typography variant="h5">Start a text assessment</Typography>
          <Typography color="text.secondary" sx={{mb: 3}}>Automated assessment requiring human verification; not definitive proof.</Typography>
          <TextField fullWidth label="Headline (optional)" value={textTab.title} onChange={e => setTextTab(prev => ({...prev, title: e.target.value}))} sx={{mb: 2}} />
          <TextField fullWidth multiline minRows={9} label="Article or claim" value={textTab.body} onChange={e => setTextTab(prev => ({...prev, body: e.target.value}))} helperText="Use the original wording. Long text may be truncated by the model." />
          <Button fullWidth variant="contained" size="large" sx={{mt: 3}} disabled={!textTab.body.trim() || textTab.loading || !submissionsEnabled} onClick={analyze}>{textTab.loading ? 'Submitting…' : 'Analyze content'}</Button>
          {textTab.loading && <LinearProgress sx={{mt: 2}} />}
          {textTab.error && <Alert severity="error" sx={{mt: 2}}>{textTab.error}</Alert>}
        </CardContent></Card>
        <Card className="panel result"><CardContent>
          <Typography variant="h5">Assessment</Typography>
          <TextResultPanel
            state={textTab}
            editorEntry={editors[textTab.result?.id || '']}
            onEditorDraft={d => editorDraftChange('TEXT', textTab.result?.id || '', d)}
            onReverify={() => saveClaims('TEXT')}
            onCheckAgain={() => textTab.result?.id && void loadExisting('TEXT', textTab.result.id)}
            onReviewRequest={() => void requestReview('TEXT')}
          />
        </CardContent></Card>
      </Stack>}
      {tab === 1 && <Stack direction={{xs: 'column', md: 'row'}} spacing={3} sx={{mt: 3}}>
        <Card className="panel"><CardContent>
          <Typography variant="h5">Analyze a face image</Typography>
          <Typography color="text.secondary" sx={{mb: 2}}>FaceForensics++ assesses facial manipulation in a single JPG, JPEG, or PNG image.</Typography>
          <Box className={`dropzone ${imageTab.dragging ? 'dragging' : ''}`} onDragEnter={e => { e.preventDefault(); setImageTab(prev => ({...prev, dragging: true})); }} onDragOver={e => e.preventDefault()} onDragLeave={() => setImageTab(prev => ({...prev, dragging: false}))} onDrop={onDrop}>
            <input id="image-picker" hidden type="file" accept=".jpg,.jpeg,.png,image/jpeg,image/png" onChange={onPicker} />
            {imageTab.image ? <>
              <img src={imageTab.preview} className="image-preview" alt="Selected upload preview" />
              <Typography fontWeight={700}>{imageTab.image.name}</Typography>
              <Typography variant="body2" color="text.secondary">{(imageTab.image.size / 1024 / 1024).toFixed(2)} MB</Typography>
              <Stack direction="row" spacing={1} justifyContent="center" sx={{mt: 1}}>
                <Button component="label" htmlFor="image-picker" variant="outlined" size="small">Replace</Button>
                <IconButton aria-label="Remove image" onClick={() => chooseImage(null)}>×</IconButton>
              </Stack>
            </> : <>
              <Typography fontWeight={700}>Drop an image here</Typography>
              <Button component="label" htmlFor="image-picker" variant="outlined">Choose image</Button>
              <Typography variant="caption" display="block" sx={{mt: 1}}>JPG, JPEG, or PNG · maximum 10 MB</Typography>
            </>}
          </Box>
          <Button fullWidth variant="contained" size="large" sx={{mt: 3}} disabled={!imageTab.image || imageTab.loading || !submissionsEnabled} onClick={analyzeImage}>{imageTab.loading ? 'Analyzing image…' : 'Analyze Image'}</Button>
          {imageTab.loading && <LinearProgress sx={{mt: 2}} />}
          {imageTab.error && <Alert severity="error" sx={{mt: 2}}>{imageTab.error}</Alert>}
        </CardContent></Card>
        <Card className="panel result"><CardContent>
          <Typography variant="h5">Image Assessment</Typography>
          <ImageResultPanel state={imageTab} preview={imageTab.preview} onCheckAgain={() => imageTab.result?.id && void loadExisting('IMAGE', imageTab.result.id)} />
        </CardContent></Card>
      </Stack>}
      {tab === 2 && <Stack direction={{xs: 'column', md: 'row'}} spacing={3} sx={{mt: 3}}>
        <Card className="panel"><CardContent>
          <Typography variant="h5">Verify a claim</Typography>
          <Typography color="text.secondary" sx={{mb: 2}}>Uses the local FEVER Iteration 2 repository only. Missing evidence is INSUFFICIENT, not false.</Typography>
          <TextField fullWidth multiline minRows={6} label="Claim" value={claimTab.claim} onChange={e => setClaimTab(prev => ({...prev, claim: e.target.value}))} />
          <Button fullWidth variant="contained" sx={{mt: 3}} disabled={!claimTab.claim.trim() || claimTab.loading || !submissionsEnabled} onClick={analyzeClaim}>Verify claim</Button>
          {claimTab.loading && <LinearProgress sx={{mt: 2}} />}
          {claimTab.error && <Alert severity="error" sx={{mt: 2}}>{claimTab.error}</Alert>}
        </CardContent></Card>
        <Card className="panel result"><CardContent>
          <Typography variant="h5">Verification</Typography>
          <ClaimResultPanel
            state={claimTab}
            editorEntry={editors[claimTab.result?.id || '']}
            onEditorDraft={d => editorDraftChange('CLAIM', claimTab.result?.id || '', d)}
            onReverify={() => saveClaims('CLAIM')}
            onCheckAgain={() => claimTab.result?.id && void loadExisting('CLAIM', claimTab.result.id)}
          />
        </CardContent></Card>
      </Stack>}
    </Container>
  </>;
}

function ImageResultPanel({state, preview, onCheckAgain}: {state: TabState; preview: string; onCheckAgain: () => void}) {
  if (!state.result) return <Box className="empty"><Typography color="text.secondary">Select an image to see its verdict and model signals.</Typography></Box>;
  if (state.result.status !== 'COMPLETED') return <ProcessingBlock state={state} onCheckAgain={() => undefined} />;
  const imageData = state.result.result;
  const probabilities = imageData?.probabilities;
  const heatmap = imageData?.gradcam?.heatmap_values;
  const camReady = imageData?.gradcam?.status === 'generated' && Array.isArray(heatmap) && heatmap.length > 0;
  return <>
    <Box className="cam-wrap">
      {preview && <img src={preview} className="result-image cam-base" alt="Uploaded image" />}
      {camReady && <HeatmapOverlay values={heatmap} />}
    </Box>
    {camReady
      ? <Typography className="cam-legend" color="text.secondary" variant="caption">Colors indicate where the model focused its attention, not a proven manipulation location.</Typography>
      : <Typography color="text.secondary" variant="body2">Heatmap unavailable for this analysis.</Typography>}
    <Chip label={imageData?.verdict || state.result.status || 'UNAVAILABLE'} color={imageData?.verdict === 'MANIPULATED' ? 'error' : 'primary'} sx={{my: 2}} />
    <Typography variant="h6">Manipulation probability: {formatPercent(probabilities?.MANIPULATED)}</Typography>
    <Typography color="text.secondary">Confidence: {formatPercent(imageData?.confidence)}</Typography>
    <Typography color="text.secondary">Effective deployment margin: {imageData?.deployment_overrides?.image_uncertain_margin ?? 'Unavailable'} · Exported margin: {imageData?.deployment_overrides?.exported_model_margin ?? 'Unavailable'}</Typography>
  </>;
}

function formatDateTime(value?: string): string {
  if (!value) return 'Unavailable';
  const date = new Date(value);
  return Number.isNaN(date.getTime()) ? value : date.toLocaleString();
}

function reportInput(result: AnalysisResult | null): string {
  const raw = result?.result;
  if (result?.input_type === 'IMAGE') return typeof raw === 'string' ? raw : (raw?.input_text || 'Uploaded image');
  return '';
}

function ReportHeader({state}: {state: TabState}) {
  const result = state.result;
  if (!result) return null;
  const model = result.result;
  const analysisModel = model?.model || 'Unavailable';
  const verificationModel = result.claims?.find(c => c.nli_output?.model)?.nli_output?.model || (result.input_type === 'IMAGE' ? 'Not applicable' : 'Unavailable');
  const inputText = result.input_type === 'IMAGE' ? reportInput(result) : (typeof model?.input === 'string' ? model.input : '');
  return <Box sx={{mb: 2}}>
    <Stack direction="row" spacing={1} sx={{flexWrap: 'wrap', gap: 1, mb: 1}}>
      <Chip size="small" label={result.input_type || 'UNKNOWN'} />
      <Chip size="small" color={result.status === 'COMPLETED' ? 'success' : result.status === 'FAILED' ? 'error' : 'default'} label={result.status || 'UNKNOWN'} />
      {result.review_status && <Chip size="small" variant="outlined" label={`Review: ${result.review_status}`} />}
    </Stack>
    <Typography variant="body2" color="text.secondary">Created: {formatDateTime(result.created_at)} · Updated: {formatDateTime(result.updated_at)}</Typography>
    <Typography variant="body2" color="text.secondary">Analysis model: {analysisModel} · Verification model: {verificationModel}</Typography>
    {inputText && <Typography variant="body2" color="text.secondary" sx={{mt: 1}}>Input: {inputText}</Typography>}
    <Alert severity="info" sx={{mt: 2}}>Automated assessment requiring human verification; not definitive proof. Model outputs are not calibrated truth probabilities.</Alert>
  </Box>;
}

const REVIEW_STATUSES = ['NEEDS_INFO', 'CONFIRMED', 'DISPUTED', 'CLOSED'];

function ReviewForm({onSubmit}: {onSubmit: (status: string, notes: string) => Promise<void>}) {
  const [status, setStatus] = useState('CONFIRMED');
  const [notes, setNotes] = useState('');
  const [saving, setSaving] = useState(false);
  return <Box sx={{mt: 3, p: 2, border: '1px solid #e2e8f0', borderRadius: 2}}>
    <Typography variant="subtitle1" fontWeight={700}>Record fact-checker review</Typography>
    <Typography variant="caption" color="text.secondary">Saved as a separate human review; it does not overwrite the model prediction.</Typography>
    <Stack direction={{xs: 'column', sm: 'row'}} spacing={2} sx={{mt: 1}}>
      <TextField select size="small" label="Review status" value={status} onChange={e => setStatus(e.target.value)} sx={{minWidth: 160}}>
        {REVIEW_STATUSES.map(s => <MenuItem key={s} value={s}>{s}</MenuItem>)}
      </TextField>
      <TextField fullWidth size="small" label="Notes" value={notes} onChange={e => setNotes(e.target.value)} placeholder="Analyst note (optional)" />
      <Button variant="contained" disabled={saving} onClick={async () => { setSaving(true); try { await onSubmit(status, notes); setNotes(''); } finally { setSaving(false); } }}>Save review</Button>
    </Stack>
  </Box>;
}

function HumanReviewSection({result}: {result: AnalysisResult | null}) {
  const reviews = result?.reviews ?? [];
  return <Box sx={{mt: 3}}>
    <Typography variant="h6">Human review (separate from model output)</Typography>
    {reviews.length === 0
      ? <Typography color="text.secondary" variant="body2">No human reviews recorded yet.</Typography>
      : reviews.map((r, i) => <Box key={r.id || i} sx={{p: 1.5, borderBottom: '1px solid #eee'}}>
        <Typography fontWeight={700}>{r.status}</Typography>
        {r.notes && <Typography variant="body2">{r.notes}</Typography>}
        <Typography variant="caption" color="text.secondary">{formatDateTime(r.created_at)}</Typography>
      </Box>)}
  </Box>;
}
