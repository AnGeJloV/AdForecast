import { useMemo, useState } from 'react'
import type { CashflowGranularity, CashflowPoint, DailyMetricPoint } from '../api/types'
import { MONTHS_SHORT } from '../utils/format'

const AXIS_COLOR = 'rgba(255,255,255,0.4)'
const GRID_COLOR = 'rgba(255,255,255,0.06)'

/**
 * Палитра диаграмм — цвета текста статусных бейджей (--ok / --warn / --bad в index.css).
 * Фон бейджа — тинт того же цвета, он для заливки круга слишком тусклый; читаемый
 * и при этом родной для темы — именно текстовый оттенок.
 */
export const CHART_COLORS = {
  ok: '#34D399',
  mid: '#FBBF24',
  bad: '#F87171',
  accent: '#F59E0B',
  info: '#60A5FA',
  violet: '#A78BFA',
  slate: '#94A3B8',
  muted: '#6B7280',
}

interface DonutSegment {
  label: string
  value: number
  color: string
}

export function Donut({ segments, centerValue, centerLabel, size = 150 }: {
  segments: DonutSegment[]
  centerValue: string
  centerLabel: string
  size?: number
}) {
  const total = segments.reduce((sum, segment) => sum + segment.value, 0)
  let offset = 25
  return (
    <div className="donut-wrap">
      <svg width={size} height={size} viewBox="0 0 42 42">
        {/* Подложка только для пустой диаграммы: под секторами она подмешивалась
            к их прозрачному цвету и сдвигала оттенок относительно бейджей. */}
        {total === 0 && (
          <circle cx="21" cy="21" r="15.9" fill="none" stroke="rgba(255,255,255,0.06)" strokeWidth="6.5" />
        )}
        {total > 0 && segments.map((segment) => {
          const length = (segment.value / total) * 100
          const dash = (
            <circle
              key={segment.label}
              cx="21" cy="21" r="15.9" fill="none"
              stroke={segment.color} strokeWidth="6.5"
              strokeDasharray={`${length} ${100 - length}`}
              strokeDashoffset={offset}
            />
          )
          offset -= length
          return dash
        })}
        <text x="21" y="20" textAnchor="middle" fill="#F5F5F4" fontSize="9" fontWeight="700" fontFamily="Plus Jakarta Sans">
          {centerValue}
        </text>
        <text x="21" y="28" textAnchor="middle" fill="rgba(255,255,255,0.5)" fontSize="3.6">
          {centerLabel}
        </text>
      </svg>
      <div className="donut-legend">
        {segments.map((segment) => (
          <div className="r" key={segment.label}>
            <span className="dot" style={{ background: segment.color }} />
            <span className="n">{segment.label}</span>
            <span className="v">
              {segment.value}{total > 0 ? ` · ${Math.round((segment.value / total) * 100)} %` : ''}
            </span>
          </div>
        ))}
      </div>
    </div>
  )
}

type BarsMode = 'revenue' | 'cost' | 'profit'

const compactNumber = new Intl.NumberFormat('ru-RU', { notation: 'compact', maximumFractionDigits: 1 })
const plainNumber = new Intl.NumberFormat('ru-RU')

/** Подпись корзины: дни и недели — «12.10», месяцы — «окт», кварталы — «3 кв». */
function periodLabel(iso: string, granularity: CashflowGranularity): string {
  const [, month, day] = iso.split('-')
  if (granularity === 'DAY' || granularity === 'WEEK') {
    return `${day}.${month}`
  }
  if (granularity === 'MONTH') {
    return MONTHS_SHORT[Number(month) - 1]
  }
  return `${Math.floor((Number(month) - 1) / 3) + 1} кв`
}

/** Подписи значений помещаются примерно до 16 столбиков; дальше наезжают друг на друга. */
const LABELLED_BARS = 16

export function CashflowBars({ points, granularity, periods, period, onPeriodChange }: {
  points: CashflowPoint[]
  granularity: CashflowGranularity
  periods: { value: string; label: string }[]
  period: string
  onPeriodChange: (value: string) => void
}) {
  const [mode, setMode] = useState<BarsMode>('revenue')
  const values = points.map((point) => (
    mode === 'revenue' ? point.revenue : mode === 'cost' ? point.cost : point.revenue - point.cost
  ))
  const max = Math.max(...values.map((value) => Math.abs(value)), 1)
  const lastIndex = values.length - 1
  const labelEveryBar = points.length <= LABELLED_BARS
  // самую крупную корзину подписываем всегда: по ней читается масштаб графика
  let peakIndex = 0
  values.forEach((value, index) => {
    if (Math.abs(value) > Math.abs(values[peakIndex])) {
      peakIndex = index
    }
  })

  return (
    <>
      <div className="chart-controls">
        <div className="chart-tabs">
          <button className={mode === 'revenue' ? 'active' : ''} onClick={() => setMode('revenue')}>Доход</button>
          <button className={mode === 'cost' ? 'active' : ''} onClick={() => setMode('cost')}>Расход</button>
          <button className={mode === 'profit' ? 'active' : ''} onClick={() => setMode('profit')}>Прибыль</button>
        </div>
        <div className="chart-tabs">
          {periods.map((option) => (
            <button
              key={option.value}
              className={period === option.value ? 'active' : ''}
              onClick={() => onPeriodChange(option.value)}
            >
              {option.label}
            </button>
          ))}
        </div>
      </div>
      {points.length === 0
        ? <div className="chart-empty">пока нет данных по выгрузкам</div>
        : (
          <div className="bars">
            {values.map((value, index) => (
              <div className="bar-col" key={points[index].periodStart}>
                <div
                  className={`bar${value < 0 ? ' neg' : ''}${index === lastIndex ? ' hot' : ''}`}
                  style={{ height: `${Math.max((Math.abs(value) / max) * 88, 2)}%` }}
                  title={`${periodLabel(points[index].periodStart, granularity)}: ${plainNumber.format(Math.round(value))}`}
                >
                  {(labelEveryBar || index === peakIndex || index === lastIndex) && (
                    <span className="bar-val">{compactNumber.format(value)}</span>
                  )}
                </div>
                <div className="bar-lbl">{periodLabel(points[index].periodStart, granularity)}</div>
              </div>
            ))}
          </div>
          )}
    </>
  )
}

function niceCeil(value: number): number {
  if (value <= 0) {
    return 1
  }
  const power = Math.pow(10, Math.floor(Math.log10(value)))
  const normalized = value / power
  const factor = normalized <= 1 ? 1 : normalized <= 2 ? 2 : normalized <= 2.5 ? 2.5 : normalized <= 5 ? 5 : 10
  return factor * power
}

export function CashflowChart({ metrics, compact = false }: { metrics: DailyMetricPoint[]; compact?: boolean }) {
  const series = useMemo(() => {
    let cost = 0
    let revenue = 0
    return metrics.map((point) => {
      cost += point.cost
      revenue += point.revenue
      return { date: point.date, cost, revenue }
    })
  }, [metrics])

  if (series.length === 0) {
    return <div className="chart-empty">нет загруженных метрик — загрузите выгрузку рекламного кабинета</div>
  }

  const W = 780
  const H = compact ? 240 : 300
  const L = 50
  const R = 14
  const T = 14
  const B = 30
  const max = niceCeil(Math.max(...series.map((point) => Math.max(point.cost, point.revenue))) * 1.05)
  const x = (index: number) => L + ((W - L - R) * index) / Math.max(series.length - 1, 1)
  const y = (value: number) => T + (H - T - B) * (1 - value / max)
  const path = (values: number[]) =>
    values.map((value, index) => `${index ? 'L' : 'M'}${x(index).toFixed(1)},${y(value).toFixed(1)}`).join(' ')

  const gridLines = []
  const steps = 4
  for (let step = 0; step <= steps; step += 1) {
    const value = (max / steps) * step
    gridLines.push(
      <g key={step}>
        <line className="grid-line" x1={L} y1={y(value)} x2={W - R} y2={y(value)} />
        <text className="axis-label" x={L - 8} y={y(value) + 4} textAnchor="end">
          {formatAxis(value)}
        </text>
      </g>,
    )
  }

  const labelEvery = Math.max(1, Math.ceil(series.length / 6))
  const xLabels = series.map((point, index) => {
    if (index % labelEvery !== 0 && index !== series.length - 1) {
      return null
    }
    return (
      <text key={point.date} className="axis-label" x={x(index)} y={H - 8} textAnchor="middle">
        {point.date.slice(8, 10)}.{point.date.slice(5, 7)}
      </text>
    )
  })

  return (
    <svg className="chart" viewBox={`0 0 ${W} ${H}`}>
      {gridLines}
      {xLabels}
      <path d={path(series.map((point) => point.cost))} fill="none" stroke="#6B7280" strokeWidth="2" strokeDasharray="5 5" />
      <path d={path(series.map((point) => point.revenue))} fill="none" stroke="#F59E0B" strokeWidth="2.5" />
      {series.map((point, index) => (
        index % labelEvery === 0 || index === series.length - 1
          ? <circle key={point.date} cx={x(index)} cy={y(point.revenue)} r="3" fill="#F59E0B" />
          : null
      ))}
    </svg>
  )
}

function formatAxis(value: number): string {
  if (value >= 1000) {
    const thousands = value / 1000
    return `${Number.isInteger(thousands) ? thousands : thousands.toFixed(1).replace('.', ',')} тыс.`
  }
  return String(Math.round(value))
}

export function axisLabelStyle(): { fill: string; fontSize: number; fontFamily: string } {
  return { fill: AXIS_COLOR, fontSize: 10.5, fontFamily: 'Inter, sans-serif' }
}

export { GRID_COLOR, AXIS_COLOR }
