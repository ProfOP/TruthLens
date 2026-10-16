import {describe, expect, it} from 'vitest';
import {nextEditorEntry, parseClaimLines, reverifyDisabled} from './claimEditor';

describe('claim editor logic', () => {
  it('parses nonempty trimmed claim lines', () => {
    expect(parseClaimLines('  A claim.  \n\n   \nAnother claim.\n')).toEqual(['A claim.', 'Another claim.']);
    expect(parseClaimLines('')).toEqual([]);
  });

  it('initializes from claims when no entry exists yet', () => {
    const entry = nextEditorEntry([{claim_text: 'Facebook was founded in 2004.'}]);
    expect(entry).toEqual({draft: 'Facebook was founded in 2004.', baseline: 'Facebook was founded in 2004.'});
  });

  it('preserves unsaved edits when claims arrive during polling', () => {
    const entry = nextEditorEntry([{claim_text: 'Original claim.'}]);
    const afterArrival = nextEditorEntry([{claim_text: 'Original claim.'}, {claim_text: 'Late claim.'}], entry);
    expect(afterArrival).toBe(entry);
    expect(afterArrival.draft).toBe('Original claim.');
  });

  it('never overwrites dirty drafts', () => {
    const entry = {draft: 'My edit', baseline: 'Server claim'};
    expect(nextEditorEntry([{claim_text: 'Server claim'}], entry)).toBe(entry);
  });

  it('disables re-verify when empty, unchanged, processing, or over the limit', () => {
    expect(reverifyDisabled({draft: '', baseline: ''}, false)).toBe(true);
    expect(reverifyDisabled({draft: 'Same', baseline: 'Same'}, false)).toBe(true);
    expect(reverifyDisabled({draft: 'Changed', baseline: 'Same'}, true)).toBe(true);
    expect(reverifyDisabled({draft: 'One\nTwo\nThree\nFour', baseline: ''}, false)).toBe(true);
    expect(reverifyDisabled(undefined, false)).toBe(true);
  });

  it('enables re-verify only for in-limit changed drafts', () => {
    expect(reverifyDisabled({draft: 'Changed', baseline: 'Same'}, false)).toBe(false);
    expect(reverifyDisabled({draft: 'One\nTwo\nThree', baseline: ''}, false)).toBe(false);
  });
});
