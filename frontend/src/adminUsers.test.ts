import {describe, expect, it} from 'vitest';
import {HISTORY_TYPE_OPTIONS, hasNextHistoryPage, historyPageFooter, modelRows, roleChoices, saveBlockedReason, selfChangeBlocked, typeLabel} from './adminUsers';

const ADMIN_ID = 'admin-1';

describe('admin role editing policy', () => {
  it('blocks every choice for the signed-in administrator themself', () => {
    const self = {id: ADMIN_ID, role: 'ADMIN', active: true};
    expect(selfChangeBlocked(self, ADMIN_ID)).toBe(true);
    expect(roleChoices(self, ADMIN_ID, [self]).every(choice => choice.disabled)).toBe(true);
    expect(saveBlockedReason(self, ADMIN_ID, [self], 'USER')).toBe('You cannot change your own account role.');
  });

  it('allows demotion while another active administrator remains', () => {
    const target = {id: 'u-admin-2', role: 'ADMIN', active: true};
    const users = [{id: ADMIN_ID, role: 'ADMIN', active: true}, target];
    expect(roleChoices(target, ADMIN_ID, users).find(choice => choice.role === 'USER')?.disabled).toBe(false);
    expect(saveBlockedReason(target, ADMIN_ID, users, 'USER')).toBe('');
  });

  it('refuses to remove the last active administrator', () => {
    const last = {id: 'u-solo', role: 'ADMIN', active: true};
    const users = [last, {id: ADMIN_ID, role: 'FACT_CHECKER', active: true}];
    expect(roleChoices(last, ADMIN_ID, users).find(choice => choice.role === 'USER')?.disabled).toBe(true);
    expect(saveBlockedReason(last, ADMIN_ID, users, 'USER')).toBe('At least one active administrator must remain.');
  });

  it('does not count inactive administrators as remaining coverage', () => {
    const last = {id: 'u-last', role: 'ADMIN', active: true};
    const users = [last, {id: 'u-dead', role: 'ADMIN', active: false}, {id: ADMIN_ID, role: 'USER', active: true}];
    expect(roleChoices(last, ADMIN_ID, users).find(choice => choice.role === 'FACT_CHECKER')?.disabled).toBe(true);
  });

  it('asks for a different role before saving', () => {
    const target = {id: 'u-plain', role: 'USER', active: true};
    const users = [{id: ADMIN_ID, role: 'ADMIN', active: true}, target];
    expect(saveBlockedReason(target, ADMIN_ID, users, 'USER')).toBe('Choose a different role before saving.');
    expect(saveBlockedReason(target, ADMIN_ID, users, '')).toBe('Select a role to save.');
    expect(saveBlockedReason(null, ADMIN_ID, users, 'USER')).toBe('Select a role to save.');
  });

  it('never offers a role outside the backend whitelist', () => {
    const target = {id: 'u-plain', role: 'USER', active: true};
    expect(roleChoices(target, ADMIN_ID, [{id: ADMIN_ID, role: 'ADMIN', active: true}, target]).map(choice => choice.role))
      .toEqual(['USER', 'FACT_CHECKER', 'ADMIN']);
  });
});

describe('admin model version projection', () => {
  it('reads version identifiers from existing component metadata', () => {
    const rows = modelRows({
      welfake: {name: 'WELFake', available: true, artifact: 'models\\Welfake\\x', config: {model: 'distilbert-base-uncased'}},
      fever: {name: 'FEVER', available: true, config: {nli_model: 'cross-encoder/nli-deberta-v3-small', retrieval_model: 'sentence-transformers/all-MiniLM-L6-v2', iteration: 2}},
    });
    expect(rows.find(row => row.name === 'WELFake')?.version).toBe('distilbert-base-uncased');
    expect(rows.find(row => row.name === 'FEVER')?.version).toContain('cross-encoder/nli-deberta-v3-small');
    expect(rows.find(row => row.name === 'FEVER')?.version).toContain('iteration 2');
    expect(rows.find(row => row.name === 'FEVER')?.status).toBe('available');
  });

  it('does not duplicate the same identifier across metadata fields', () => {
    const rows = modelRows({liar: {name: 'LIAR', available: true, config: {embedding_model: 'sentence-transformers/all-MiniLM-L6-v2', retrieval_model: 'sentence-transformers/all-MiniLM-L6-v2'}}});
    expect(rows[0].version.split(' · ')).toHaveLength(1);
  });

  it('omits an iteration number the component name already states', () => {
    const rows = modelRows({fever: {name: 'FEVER Iteration 2', available: true, config: {nli_model: 'cross-encoder/nli-deberta-v3-small', iteration: 2}}});
    expect(rows[0].version).toBe('cross-encoder/nli-deberta-v3-small');
    expect(modelRows({fever: {name: 'FEVER', available: true, config: {nli_model: 'nli-x', iteration: 2}}})[0].version).toContain('iteration 2');
  });

  it('labels missing versions as Unavailable instead of inventing one', () => {
    const rows = modelRows({mystery: {available: false, config: {temperature: 1.0}}});
    expect(rows).toHaveLength(1);
    expect(rows[0].version).toBe('Unavailable');
    expect(rows[0].status).toBe('unavailable');
  });

  it('returns nothing when the payload has no components', () => {
    expect(modelRows(undefined)).toEqual([]);
    expect(modelRows(null)).toEqual([]);
  });
});

describe('type filter and paginated history', () => {
  it('offers exactly All types, Text, Image and Claim, with the server codes as values', () => {
    expect(HISTORY_TYPE_OPTIONS.map(option => option.value)).toEqual(['', 'TEXT', 'IMAGE', 'CLAIM']);
    expect(HISTORY_TYPE_OPTIONS.map(option => option.label)).toEqual(['All types', 'Text', 'Image', 'Claim']);
    expect(typeLabel('IMAGE')).toBe('Image');
    expect(typeLabel('')).toBe('All types');
  });

  it('labels the empty selection as All types rather than an empty string', () => {
    expect(typeLabel(undefined)).toBe('Unknown');
    expect(typeLabel('TEXT')).toBe('Text');
    expect(typeLabel('CLAIM')).toBe('Claim');
  });

  it('describes the page using the server-capped size', () => {
    expect(historyPageFooter(0, 10, 34, 10)).toBe('Showing 1-10 of 34 · page 1');
    expect(historyPageFooter(2, 10, 34, 4)).toBe('Showing 21-24 of 34 · page 3');
    expect(historyPageFooter(0, 10, 0, 0)).toBe('Showing 0-0 of 0 · page 1');
  });

  it('enables the next page only while more rows exist', () => {
    expect(hasNextHistoryPage(0, 10, 34, 10)).toBe(true);
    expect(hasNextHistoryPage(3, 10, 34, 4)).toBe(false);
    expect(hasNextHistoryPage(0, 10, 10, 10)).toBe(false);
    expect(hasNextHistoryPage(0, 10, 0, 10)).toBe(true);
    expect(hasNextHistoryPage(0, 10, 0, 0)).toBe(false);
  });
});
