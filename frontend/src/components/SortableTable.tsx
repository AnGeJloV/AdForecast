import { useMemo, useState } from 'react'

export interface SortColumn<T> {
  id: string
  title: string
  value: (row: T) => string | number | null
}

export type SortDirection = 'asc' | 'desc'

export interface SortTable<T> {
  rows: T[]
  columns: SortColumn<T>[]
  sort: { id: string; direction: SortDirection } | null
  toggle: (id: string) => void
  directionOf: (id: string) => SortDirection | null
}

function compare(left: string | number, right: string | number): number {
  if (typeof left === 'number' && typeof right === 'number') {
    return left - right
  }
  return String(left).localeCompare(String(right), 'ru', { numeric: true, sensitivity: 'base' })
}

export function useSortTable<T>(source: T[], columns: SortColumn<T>[]): SortTable<T> {
  const [sort, setSort] = useState<{ id: string; direction: SortDirection } | null>(null)

  const rows = useMemo(() => {
    const column = columns.find((item) => item.id === sort?.id)
    if (!sort || !column) {
      return source
    }
    const factor = sort.direction === 'asc' ? 1 : -1
    return [...source].sort((left, right) => {
      const leftValue = column.value(left)
      const rightValue = column.value(right)
      const leftEmpty = leftValue === null || leftValue === ''
      const rightEmpty = rightValue === null || rightValue === ''
      if (leftEmpty || rightEmpty) {
        return leftEmpty && rightEmpty ? 0 : leftEmpty ? 1 : -1
      }
      return factor * compare(leftValue, rightValue)
    })
  }, [source, columns, sort])

  return {
    rows,
    columns,
    sort,
    toggle: (id) => setSort((current) => {
      if (!current || current.id !== id) {
        return { id, direction: 'asc' }
      }
      return current.direction === 'asc' ? { id, direction: 'desc' } : null
    }),
    directionOf: (id) => (sort?.id === id ? sort.direction : null),
  }
}

export function SortTh<T>({ table, column }: { table: SortTable<T>; column: SortColumn<T> }) {
  const direction = table.directionOf(column.id)
  return (
    <th>
      <button
        type="button"
        className={`th-sort${direction ? ' active' : ''}`}
        onClick={() => table.toggle(column.id)}
        title="Сортировать"
      >
        {column.title}
        <svg className="th-arrows" width="7" height="11" viewBox="0 0 7 11" aria-hidden="true">
          <path d="M3.5 0L7 4H0z" fill="currentColor" opacity={direction === 'asc' ? 1 : 0.3} />
          <path d="M3.5 11L0 7h7z" fill="currentColor" opacity={direction === 'desc' ? 1 : 0.3} />
        </svg>
      </button>
    </th>
  )
}
