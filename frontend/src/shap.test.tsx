import {describe, expect, it} from 'vitest';
import {renderToStaticMarkup} from 'react-dom/server';
import {Explanation, ShapExplanation} from './App';

const VERIFIED_PAYLOAD = {
  method: 'SHAP',
  status: 'generated',
  target: 'REAL probability',
  output_names: ['REAL', 'FAKE'],
  alignment: 'verified',
  truncated: false,
  text_truncated: false,
  explained_text: 'facebook was founded in 2004 .',
  tokens: [
    {token: 'facebook', start: 0, end: 8, real_contribution: 0.4, fake_contribution: -0.4, direction: 'supports REAL'},
    {token: 'was', start: 9, end: 12, real_contribution: -0.2, fake_contribution: 0.2, direction: 'supports FAKE'},
    {token: 'founded', start: 13, end: 20, real_contribution: 0.0, fake_contribution: 0.0, direction: 'neutral'},
    {token: 'in', start: 21, end: 23, real_contribution: 0.05, fake_contribution: -0.05, direction: 'supports REAL'},
    {token: '2004', start: 24, end: 28, real_contribution: 0.3, fake_contribution: -0.3, direction: 'supports REAL'},
    {token: '.', start: 28, end: 29, real_contribution: 0.01, fake_contribution: -0.01, direction: 'supports REAL'},
    {token: '', start: 0, end: 0, real_contribution: 0.0, fake_contribution: 0.0, direction: 'neutral'},
  ],
};

const LEGACY_PAYLOAD = {
  method: 'SHAP',
  status: 'generated',
  explained_text: 'facebook was founded in 2004 .',
  tokens: [
    {token: 'facebook', real_contribution: 0.4, fake_contribution: -0.4, direction: 'supports REAL'},
    {token: 'was', real_contribution: -0.2, fake_contribution: 0.2, direction: 'supports FAKE'},
  ],
};

describe('SHAP explanation presentation', () => {
  it('states the explained output, sign meaning, and legend', () => {
    const html = renderToStaticMarkup(<ShapExplanation explanation={{kind: 'SHAP', status: 'generated', payload: VERIFIED_PAYLOAD}} />);
    expect(html).toContain('REAL probability');
    expect(html).toContain('Positive values increase this output');
    expect(html).toContain('negative values decrease it');
    expect(html).toContain('Zero attribution is neutral');
    expect(html).toContain('Token influence is not factual truth');
    expect(html).toContain('Increases output');
    expect(html).toContain('Decreases output');
    expect(html).toContain('Neutral');
  });

  it('highlights verified tokens in place with tooltips and accessible list', () => {
    const html = renderToStaticMarkup(<ShapExplanation explanation={{kind: 'SHAP', status: 'generated', payload: VERIFIED_PAYLOAD as any}} />);
    // exact explained text preserved, including punctuation spacing
    expect(html).toContain('facebook');
    expect(html).toContain('2004');
    // tooltip with signed value
    expect(html).toContain('+0.4000 REAL');
    expect(html).toContain('-0.2000 REAL');
    // accessible attribution list present with values
    expect(html).toContain('token attribution values');
    expect(html).toContain('facebook — REAL +0.4000');
  });

  it('marks zero attribution as neutral', () => {
    const html = renderToStaticMarkup(<ShapExplanation explanation={{kind: 'SHAP', status: 'generated', payload: VERIFIED_PAYLOAD as any}} />);
    const neutralMark = html.match(/<mark[^>]*tok-neutral[^>]*>/);
    expect(neutralMark).not.toBeNull();
    expect(neutralMark![0]).toContain('no effect on');
  });

  it('marks truncation and scopes explanation to processed text', () => {
    const payload = {...VERIFIED_PAYLOAD, truncated: true};
    const html = renderToStaticMarkup(<ShapExplanation explanation={{kind: 'SHAP', status: 'generated', payload: payload as any}} />);
    expect(html).toContain('truncated this text at 256 tokens');
    expect(html).toContain('text processed by the model');
  });

  it('shows readable text and marks highlights unavailable for legacy payloads', () => {
    const html = renderToStaticMarkup(<ShapExplanation explanation={{kind: 'SHAP', status: 'generated', payload: LEGACY_PAYLOAD as any}} />);
    expect(html).toContain('facebook was founded in 2004 .');
    expect(html).toContain('Token highlights unavailable for this record');
    // legacy attribution values still preserved in the accessible list
    expect(html).toContain('facebook — REAL +0.4000');
  });

  it('reports unavailable status without inventing content', () => {
    const html = renderToStaticMarkup(<ShapExplanation explanation={{kind: 'SHAP', status: 'unavailable', payload: {status: 'unavailable', error: 'boom'} as any}} />);
    expect(html).toContain('Token explanation unavailable');
    expect(html).toContain('boom');
  });
});
