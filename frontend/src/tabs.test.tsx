import {describe, expect, it} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {ClaimResultPanel, TabState, keyForTabIndex, tabIndexForType} from './App';

const TEXT_RESULT: TabState = {
  result: {
    id: 'analysis-1',
    status: 'COMPLETED',
    result: {label: 'REAL', probabilities: {REAL: 0.9, FAKE: 0.1}, credibility_score: 90},
    claims: [{id: 'c1', claim_text: 'Facebook was founded in 2004.', verdict: 'SUPPORTED', evidence: []}],
  },
  loading: false,
  error: '',
  timedOut: false,
};

const EMPTY_CLAIM_STATE: TabState = {result: null, loading: false, error: '', timedOut: false};

describe('analysis tab isolation', () => {
  it('maps input types to the matching tab', () => {
    expect(tabIndexForType('TEXT')).toBe(0);
    expect(tabIndexForType('IMAGE')).toBe(1);
    expect(tabIndexForType('CLAIM')).toBe(2);
    expect(tabIndexForType(undefined)).toBe(0);
    expect(keyForTabIndex(0)).toBe('TEXT');
    expect(keyForTabIndex(1)).toBe('IMAGE');
    expect(keyForTabIndex(2)).toBe('CLAIM');
  });

  it('empty claim tab shows its own empty state, not the text tab result', () => {
    const html = renderToStaticMarkup(
      <ClaimResultPanel
        state={EMPTY_CLAIM_STATE}
        editorEntry={undefined}
        onEditorDraft={() => undefined}
        onReverify={() => undefined}
        onCheckAgain={() => undefined}
      />,
    );
    expect(html).toContain('No verification result yet.');
    expect(html).not.toContain('Facebook was founded in 2004.');
    expect(html).not.toContain('SUPPORTED');
  });

  it('claim tab renders only its own result claims', () => {
    const html = renderToStaticMarkup(
      <ClaimResultPanel
        state={TEXT_RESULT}
        editorEntry={{draft: 'Facebook was founded in 2004.', baseline: 'Facebook was founded in 2004.'}}
        onEditorDraft={() => undefined}
        onReverify={() => undefined}
        onCheckAgain={() => undefined}
      />,
    );
    expect(html).toContain('Facebook was founded in 2004.');
    expect(html).toContain('SUPPORTED');
  });

  it('panels are driven purely by their own state object (no shared result)', () => {
    // The text result and the empty claim state are independent objects; the
    // claim panel must never read the text panel's result.
    const textHtml = renderToStaticMarkup(
      <ClaimResultPanel state={TEXT_RESULT} editorEntry={undefined} onEditorDraft={() => undefined} onReverify={() => undefined} onCheckAgain={() => undefined} />,
    );
    const emptyHtml = renderToStaticMarkup(
      <ClaimResultPanel state={EMPTY_CLAIM_STATE} editorEntry={undefined} onEditorDraft={() => undefined} onReverify={() => undefined} onCheckAgain={() => undefined} />,
    );
    expect(textHtml).toContain('Facebook was founded in 2004.');
    expect(emptyHtml).not.toContain('Facebook was founded in 2004.');
  });
});
