export function formatPercent(value: number | undefined) {
  return typeof value === 'number' ? `${(value * 100).toFixed(1)}%` : 'Unavailable';
}

export function credibilityLabel(score: number | undefined) {
  return typeof score === 'number' ? `${score.toFixed(1)} / 100` : 'Unavailable';
}
