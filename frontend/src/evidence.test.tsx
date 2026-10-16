import {describe, expect, it} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {Claim, ClaimEvidence, VerificationNotice} from './App';

function renderClaim(claim: Partial<Claim>) {
  return renderToStaticMarkup(<ClaimEvidence claim={claim as Claim} index={0} />);
}

function renderNotice(verification?: any) {
  return renderToStaticMarkup(<VerificationNotice verification={verification} />);
}

describe('claim evidence presentation', () => {
  it('collapses passages for INSUFFICIENT verdicts by default', () => {
    const html = renderClaim({
      claim_text: 'Facebook was founded in 2001.',
      verdict: 'INSUFFICIENT',
      evidence: [{page_id: 'Facebook', passage: 'Facebook launched in 2004.', similarity: 0.71, nli_scores: [0.1, 0.6, 0.3], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL']}],
    });
    expect(html).toContain('Related passages — inconclusive');
    expect(html).toContain('aria-expanded="false"'); // collapsed by default
    expect(html).toContain('MuiCollapse-hidden');
    expect(html).toContain('No conclusive evidence found in the indexed repository');
    // actual passages, references, similarity and NLI scores are preserved
    expect(html).toContain('Facebook launched in 2004.');
    expect(html).toContain('Facebook');
    expect(html).toContain('71.0%');
    expect(html).toContain('NLI scores');
    expect(html).toContain('0.100');
  });

  it('splits selected evidence from other candidates for decided verdicts', () => {
    const html = renderClaim({
      claim_text: 'Facebook was founded in 2004.',
      verdict: 'SUPPORTED',
      nli_output: {reason: 'SUPPORTED: strongest entailment signal 0.950 from Facebook#0.', note: 'Local corpus only; NLI scores are not calibrated truth probabilities.'},
      evidence: [
        {id: 'e1', page_id: 'Facebook', sentence_id: 0, passage: 'Facebook was founded in 2004.', similarity: 0.9, selected: true, nli_scores: [0.95, 0.02, 0.03], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL']},
        {id: 'e2', page_id: 'Meta_Platforms', sentence_id: 3, passage: 'A related but unused passage.', similarity: 0.6, selected: false, nli_scores: [0.1, 0.1, 0.8], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL']},
      ],
    });
    expect(html).not.toContain('Related passages — inconclusive');
    expect(html).toContain('Evidence used for this verdict');
    expect(html).toContain('Facebook was founded in 2004.');
    expect(html).toContain('ENTAILMENT 0.950');
    expect(html).toContain('strongest entailment signal'); // selected-evidence reason shown
    expect(html).toContain('Other retrieved candidates — not used for this verdict (1)');
    expect(html).toContain('A related but unused passage.');
  });

  it('labels provenance unavailable for older decided records lacking a selected flag', () => {
    const html = renderClaim({
      claim_text: 'A historical claim.',
      verdict: 'REFUTED',
      evidence: [{page_id: 'Old', passage: 'Some passage', similarity: 0.5, nli_scores: [0.05, 0.9, 0.05], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL']}],
    });
    expect(html).toContain('Selected-evidence provenance is unavailable for this earlier record');
    expect(html).toContain('CONTRADICTION 0.900');
  });

  it('falls back to raw score display when label metadata is absent', () => {
    const html = renderClaim({
      claim_text: 'A claim.',
      verdict: 'REFUTED',
      evidence: [{passage: 'Some passage', similarity: 0.5, nli_scores: [0.9, 0.05, 0.05]}],
    });
    expect(html).toContain('unlabeled metadata');
    expect(html).toContain('0.900');
  });

  it('shows the INSUFFICIENT statement once when the stored reason already states it', () => {
    const html = renderClaim({
      claim_text: 'Michael Jordan retired to play baseball.',
      verdict: 'INSUFFICIENT',
      nli_output: {reason: 'No conclusive evidence found in the indexed repository.'},
      evidence: [{page_id: 'Michael_Jordan', sentence_id: 1, passage: 'Jordan played 15 seasons in the NBA .', similarity: 0.61, nli_scores: [0.0, 0.996, 0.004], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL'], provenance: {rejection: "passage does not address the claim's assertion (unmatched terms: baseball, play)"}}],
    });
    expect(html.match(/No conclusive evidence found in the indexed repository/g)?.length).toBe(1);
    expect(html).toContain('Related passages — inconclusive (1)');
    expect(html).toContain('aria-expanded="false"'); // still collapsed
    expect(html).toContain('Jordan played 15 seasons in the NBA .'); // and readable
    expect(html).toContain('Inconclusive because:');
    expect(html).toContain('unmatched terms: baseball, play');
  });

  it('shows the INSUFFICIENT statement once for older saved results without a reason', () => {
    const html = renderClaim({
      claim_text: 'A historical claim.',
      verdict: 'INSUFFICIENT',
      evidence: [{page_id: 'Old', passage: 'An older passage', similarity: 0.58, nli_scores: [0.02, 0.93, 0.05], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL']}],
    });
    expect(html.match(/No conclusive evidence found in the indexed repository/g)?.length).toBe(1);
    expect(html).toContain('An older passage');
    expect(html).toContain('CONTRADICTION 0.930');
    expect(html).not.toContain('Inconclusive because:'); // no reason was recorded for this earlier record
  });

  it('does not invent scores, reasons, or citations when absent', () => {
    const html = renderClaim({claim_text: 'A bare claim.', verdict: 'INSUFFICIENT', evidence: [{passage: 'Only passage'}]});
    expect(html).not.toContain('similarity');
    expect(html).not.toContain('NLI scores');
    expect(html).not.toContain('Source');
  });

  it('shows exactly one verdict chip per claim', () => {
    const html = renderClaim({claim_text: 'One verdict.', verdict: 'INSUFFICIENT', evidence: []});
    expect(html.match(/<span[^>]*>INSUFFICIENT<\/span>/g)?.length).toBe(1);
  });

  it('labels discovery candidates as not verdict evidence and keeps verdict evidence intact', () => {
    const html = renderClaim({
      claim_text: 'Stripey keeps bees.',
      verdict: 'SUPPORTED',
      nli_output: {
        reason: 'SUPPORTED: strongest entailment signal 0.900 from Beekeeping#0.',
        related_passages: {
          status: 'ok', k: 5, discovery_only: true, used_for_verdict: false,
          note: 'Retrieved by the optional page-title discovery pilot (frozen merge@N=5). These passages were NOT scored by the NLI gate and did NOT establish the verdict above.',
          passages: [
            {rank: 1, page_id: 'Beekeeping', page_title: 'Beekeeping', sentence_id: 4, text: 'Beekeeping is the maintenance of bee colonies.', score: 0.71, route: 'title', source: 'encoded_now', provenance: 'title route, sentence from wiki-pages.zip, vector from encoded_now', used_for_verdict: false},
            {rank: 2, page_id: 'Stripey', page_title: 'Stripey', sentence_id: 0, text: 'Stripey is a breed of sheep.', score: 0.66, route: 'faiss', source: 'deployed_v2', provenance: 'faiss route, sentence from the deployed FEVER corpus, vector from deployed_v2', used_for_verdict: false},
          ],
        },
      },
      evidence: [{id: 'e1', page_id: 'Beekeeping', sentence_id: 0, passage: 'Beekeeping is a method of apiculture.', similarity: 0.9, selected: true, nli_scores: [0.9, 0.05, 0.05], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL']}],
    });
    expect(html).toContain('Related passages — candidate evidence, not used for this verdict (2)');
    expect(html).toContain('Beekeeping is the maintenance of bee colonies.');
    expect(html).toContain('#1 Beekeeping · sentence 4');
    expect(html).toContain('similarity 71.0%');
    expect(html).toContain('vector from encoded_now');
    expect(html).toContain('used_for_verdict=false');
    expect(html).toContain('did NOT establish the verdict');
    // the verdict section is untouched: same selected evidence, same NLI scores, same reason
    expect(html).toContain('Evidence used for this verdict');
    expect(html).toContain('Beekeeping is a method of apiculture.');
    expect(html).toContain('ENTAILMENT 0.900');
    expect(html).toContain('strongest entailment signal');
    // discovery candidates carry no NLI presentation at all
    const related = html.slice(html.indexOf('Related passages — candidate evidence'));
    expect(related).not.toContain('NLI scores');
    expect(related).not.toContain('Inconclusive because');
  });

  it('renders nothing for records made before the discovery path existed', () => {
    const html = renderClaim({
      claim_text: 'An older claim.',
      verdict: 'SUPPORTED',
      nli_output: {reason: 'SUPPORTED: strongest entailment signal 0.900 from Old#0.'},
      evidence: [{page_id: 'Old', sentence_id: 0, passage: 'An older passage', similarity: 0.9, selected: true, nli_scores: [0.9, 0.05, 0.05], nli_labels: ['ENTAILMENT', 'CONTRADICTION', 'NEUTRAL']}],
    });
    expect(html).not.toContain('candidate evidence, not used for this verdict');
    expect(html).toContain('Evidence used for this verdict');
  });

  it('says so when discovery was enabled but could not run', () => {
    const html = renderClaim({
      claim_text: 'A claim with a failed discovery run.',
      verdict: 'INSUFFICIENT',
      nli_output: {reason: 'No conclusive evidence found in the indexed repository.', related_passages: {status: 'unavailable', passages: [], error: 'missing title index', used_for_verdict: false, note: 'Candidate discovery failed; the verdict and evidence above are the deployed FAISS result, unchanged.'}},
      evidence: [],
    });
    expect(html).toContain('Candidate discovery unavailable: missing title index');
    expect(html).toContain('the deployed result, unchanged');
    expect(html).not.toContain('candidate evidence, not used for this verdict');
  });
});

describe('verification service failure presentation', () => {
  it('labels a failed verification UNAVAILABLE, never as an evidence finding', () => {
    const html = renderClaim({
      claim_text: 'Paris is the capital of France.',
      // a failed verification stores SQL NULL; the API serialises it as JSON null
      verdict: null as unknown as string,
      component_status: 'UNAVAILABLE',
      nli_output: {status: 'UNAVAILABLE', error: 'Verification could not be completed.', service_error: '503 FEVER_UNAVAILABLE'},
      evidence: [],
    });
    expect(html).toContain('Verification could not be completed');
    expect(html).toContain('UNAVAILABLE');
    expect(html).toContain('Resubmit the claim once the service is available');
    // the two messages that would make a service failure look like an evidence result
    expect(html).not.toContain('No conclusive evidence found in the indexed repository');
    expect(html).not.toContain('Related passages — inconclusive');
    // the raw service error stays in the API payload for diagnosis, out of the user-facing message
    expect(html).not.toContain('503 FEVER_UNAVAILABLE');
    expect(html.match(/<span[^>]*>INSUFFICIENT<\/span>/g)).toBeNull();
  });

  it('reads a row stored by the old fallback honestly and names the value it kept', () => {
    const html = renderClaim({
      claim_text: 'Helsinki is the capital of Finland.',
      verdict: 'INSUFFICIENT',
      component_status: 'UNAVAILABLE',
      nli_output: {error: 'Verification unavailable: 503 FEVER_UNAVAILABLE'},
      evidence: [],
    });
    expect(html).toContain('An earlier build stored “INSUFFICIENT” for it; that value was not produced by a completed verification.');
    expect(html).not.toContain('No conclusive evidence found in the indexed repository');
  });

  it('leaves a completed INSUFFICIENT verdict on the evidence path', () => {
    const html = renderClaim({
      claim_text: 'A checked claim with no conclusive evidence.',
      verdict: 'INSUFFICIENT',
      component_status: 'COMPLETED',
      nli_output: {reason: 'No conclusive evidence found in the indexed repository.'},
      evidence: [],
    });
    expect(html).toContain('No conclusive evidence found in the indexed repository');
    expect(html).not.toContain('Verification could not be completed');
  });
});

describe('analysis-level verification notice', () => {
  it('stays silent when verification completed', () => {
    expect(renderNotice({status: 'COMPLETED', note: 'fine'})).toBe('');
    expect(renderNotice(undefined)).toBe('');
  });

  it('names a wholly unavailable verification and how many claims it covers', () => {
    const html = renderNotice({status: 'UNAVAILABLE', failed_claims: 3, total_claims: 3, unavailable_note: 'Verification could not be completed. 3 of 3 claim(s) could not be verified because the evidence service call failed.'});
    expect(html).toContain('Verification could not be completed for this analysis');
    expect(html).toContain('3 of 3 claim(s)');
  });

  it('marks a partly completed verification as partial', () => {
    const html = renderNotice({status: 'PARTIAL', failed_claims: 1, total_claims: 3});
    expect(html).toContain('Verification completed only in part');
    expect(html).toContain('Verification could not be completed.');
  });
});
