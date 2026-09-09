// Time formatting shared by the board and the workflow page, so "3m ago" reads
// the same wherever it appears.

/** A short relative time — "just now", "12m ago", "3d ago". Empty for no date. */
export function relTime(iso) {
  if (!iso) return ''
  const diff = (Date.now() - new Date(iso).getTime()) / 1000
  if (diff < 60) return 'just now'
  if (diff < 3600) return `${Math.floor(diff / 60)}m ago`
  if (diff < 86400) return `${Math.floor(diff / 3600)}h ago`
  return `${Math.floor(diff / 86400)}d ago`
}

/**
 * A run's cost line — "12.3s · $0.0421". Either half may be missing (a run that
 * never started, or an older row), so whatever is known is shown on its own.
 */
export function runMeta({ durationMs, costUsd }) {
  const parts = []
  if (durationMs != null) parts.push(`${(durationMs / 1000).toFixed(1)}s`)
  if (costUsd != null) parts.push(`$${costUsd.toFixed(4)}`)
  return parts.join(' · ')
}
