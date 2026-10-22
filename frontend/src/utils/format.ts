const numberFormat = new Intl.NumberFormat('ru-RU', { maximumFractionDigits: 0 })
const decimalFormat = new Intl.NumberFormat('ru-RU', { minimumFractionDigits: 2, maximumFractionDigits: 2 })

export function fmtNum(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  return numberFormat.format(Math.round(value))
}

export function fmtDecimal(value: number | null | undefined): string {
  if (value === null || value === undefined || Number.isNaN(value)) {
    return '—'
  }
  return decimalFormat.format(value)
}

export function fmtMoney(value: number | null | undefined, currency?: string): string {
  const text = fmtNum(value)
  return text === '—' ? text : currency ? `${text} ${currency}` : text
}

/** Процент ROI из API (42,5) → коэффициент окупаемости (1,43) */
export function fmtRatio(roiPercent: number | null | undefined): string {
  if (roiPercent === null || roiPercent === undefined) {
    return '—'
  }
  return (1 + roiPercent / 100).toFixed(2).replace('.', ',')
}

export function ratioValue(roiPercent: number | null | undefined): number | null {
  if (roiPercent === null || roiPercent === undefined) {
    return null
  }
  return 1 + roiPercent / 100
}

/** Доля (0,9) → «90 %» */
export function fmtPercent(fraction: number | null | undefined): string {
  if (fraction === null || fraction === undefined) {
    return '—'
  }
  return `${Math.round(fraction * 100)} %`
}

export function fmtDate(iso: string | null | undefined): string {
  if (!iso) {
    return '—'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  return date.toLocaleDateString('ru-RU')
}

export function fmtDateTime(iso: string | null | undefined): string {
  if (!iso) {
    return '—'
  }
  const date = new Date(iso)
  if (Number.isNaN(date.getTime())) {
    return '—'
  }
  const day = date.toLocaleDateString('ru-RU', { day: '2-digit', month: '2-digit' })
  const time = date.toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })
  return `${day}, ${time}`
}

export function plural(n: number, forms: [string, string, string]): string {
  const mod100 = Math.abs(n) % 100
  const mod10 = mod100 % 10
  if (mod100 >= 11 && mod100 <= 14) {
    return forms[2]
  }
  if (mod10 === 1) {
    return forms[0]
  }
  if (mod10 >= 2 && mod10 <= 4) {
    return forms[1]
  }
  return forms[2]
}

export function fmtDays(n: number | null | undefined): string {
  if (n === null || n === undefined) {
    return '—'
  }
  const rounded = Math.round(n)
  return `${rounded} ${plural(rounded, ['день', 'дня', 'дней'])}`
}

/** «25–32 из 817 кампаний» — диапазон строк на текущей странице */
export function rangeLabel(page: number, pageSize: number, shown: number, total: number, forms: [string, string, string]): string {
  if (shown === 0) {
    return `ничего не найдено из ${fmtNum(total)} ${plural(total, forms)}`
  }
  const first = (page - 1) * pageSize + 1
  const last = first + shown - 1
  return `${fmtNum(first)}–${fmtNum(last)} из ${fmtNum(total)} ${plural(total, forms)}`
}

export function initials(fullName: string): string {
  const parts = fullName.trim().split(/\s+/).filter(Boolean)
  if (parts.length === 0) {
    return '??'
  }
  return parts.slice(0, 2).map((part) => part[0].toUpperCase()).join('')
}

export type PaybackTone = 'ok' | 'mid' | 'bad'

export function paybackTone(ratio: number | null): PaybackTone | null {
  if (ratio === null) {
    return null
  }
  if (ratio >= 1.05) {
    return 'ok'
  }
  if (ratio >= 0.95) {
    return 'mid'
  }
  return 'bad'
}

export function paybackLabel(tone: PaybackTone | null): string {
  switch (tone) {
    case 'ok':
      return 'окупаема'
    case 'mid':
      return 'на границе'
    case 'bad':
      return 'убыточна'
    default:
      return 'нет данных'
  }
}

export const MONTHS = [
  'Январь', 'Февраль', 'Март', 'Апрель', 'Май', 'Июнь',
  'Июль', 'Август', 'Сентябрь', 'Октябрь', 'Ноябрь', 'Декабрь',
]

export function monthName(month: number): string {
  return MONTHS[month - 1] ?? String(month)
}
