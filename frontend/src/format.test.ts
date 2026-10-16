import {describe, expect, it} from 'vitest';
import {credibilityLabel, formatPercent} from './format';

describe('formatters', () => {
  it('formats probabilities as percents', () => {
    expect(formatPercent(0.047)).toBe('4.7%');
    expect(formatPercent(undefined)).toBe('Unavailable');
  });

  it('formats P(REAL)*100 credibility', () => {
    expect(credibilityLabel(4.74)).toBe('4.7 / 100');
  });
});
