import type { ReactNode } from 'react'
import type { PaybackTone } from '../utils/format'

export function PageLoader() {
  return (
    <div className="page-loader">
      <div className="spinner" />
      <span>загрузка…</span>
    </div>
  )
}

export function ErrorBox({ message, errors }: { message: string; errors?: string[] }) {
  if (!message) {
    return null
  }
  return (
    <div className="error-box">
      <div className="t">{message}</div>
      {errors && errors.length > 0 && (
        <ul>
          {errors.map((error, index) => (
            <li key={index}>{error}</li>
          ))}
        </ul>
      )}
    </div>
  )
}

export function EmptyState({ children }: { children: ReactNode }) {
  return <div className="empty-state">{children}</div>
}

export function StatusPill({ tone, label }: { tone: PaybackTone | 'plan' | 'run' | 'admin' | 'mark' | 'cur' | null; label: string }) {
  return <span className={`pill ${tone ?? 'plan'}`}>{label}</span>
}

function pageTokens(page: number, pageCount: number): (number | 'gap')[] {
  const numbers = new Set<number>([1, pageCount])
  for (let number = page - 2; number <= page + 2; number += 1) {
    if (number >= 1 && number <= pageCount) {
      numbers.add(number)
    }
  }
  const result: (number | 'gap')[] = []
  let previous = 0
  for (const number of Array.from(numbers).sort((left, right) => left - right)) {
    if (previous && number - previous > 1) {
      result.push('gap')
    }
    result.push(number)
    previous = number
  }
  return result
}

export function Pager({ page, pageCount, onChange }: { page: number; pageCount: number; onChange: (page: number) => void }) {
  if (pageCount <= 1) {
    return null
  }
  return (
    <div className="pager">
      <button disabled={page === 1} onClick={() => onChange(page - 1)}>‹</button>
      {pageTokens(page, pageCount).map((token, index) => token === 'gap'
        ? <span key={`gap-${index}`} className="pager-gap">…</span>
        : (
          <button
            key={token}
            className={token === page ? 'cur' : ''}
            onClick={() => onChange(token)}
          >
            {token}
          </button>
        ))}
      <button disabled={page === pageCount} onClick={() => onChange(page + 1)}>›</button>
    </div>
  )
}

export function ProbBar({ fraction }: { fraction: number | null }) {
  if (fraction === null) {
    return <span className="mono">—</span>
  }
  const percent = Math.round(fraction * 100)
  const color = percent >= 70 ? 'var(--ok)' : percent >= 40 ? 'var(--warn)' : 'var(--bad)'
  return (
    <div className="prob">
      <div className="track">
        <div className="fill" style={{ width: `${percent}%`, background: color }} />
      </div>
      <span className="mono">{percent} %</span>
    </div>
  )
}
