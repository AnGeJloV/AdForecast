import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError, downloadFile } from '../api/client'
import type { CampaignSummary, CompareResult, CompareVariant, ForecastListItem } from '../api/types'
import { Layout } from '../components/Layout'
import { Modal } from '../components/Modal'
import { SortTh, useSortTable } from '../components/SortableTable'
import type { SortColumn } from '../components/SortableTable'
import { Select } from '../components/Select'
import { EmptyState, ErrorBox, PageLoader, Pager, StatusPill } from '../components/Ui'
import {
  fmtDateTime,
  fmtDays,
  fmtDecimal,
  fmtMoney,
  fmtPercent,
  fmtRatio,
  MONTHS,
  paybackLabel,
  paybackTone,
  rangeLabel,
  ratioValue,
} from '../utils/format'

interface VariantDraft {
  campaignId: number | null
  budget: string
  duration: string
  startMonth: string
}

const EMPTY_DRAFT: VariantDraft = { campaignId: null, budget: '', duration: '90', startMonth: String(new Date().getMonth() + 1) }

function statusLabel(status: ForecastListItem['status']): string {
  if (status === 'COMPLETED') {
    return 'готов'
  }
  return status === 'FAILED' ? 'ошибка' : 'в работе'
}

const FORECAST_PAGE_SIZE = 10

const FORECAST_COLUMNS: SortColumn<ForecastListItem>[] = [
  { id: 'started', title: 'Дата', value: (row) => row.startedAt },
  { id: 'campaign', title: 'Кампания', value: (row) => row.campaignName },
  { id: 'roi', title: 'ROI', value: (row) => row.forecastRoi },
  { id: 'probability', title: 'Вероятность', value: (row) => (row.paybackProbability === null ? null : row.paybackProbability * 100) },
  { id: 'payback', title: 'Срок', value: (row) => (row.paybackMonths === null ? null : row.paybackMonths * 30.44) },
  { id: 'status', title: 'Статус', value: (row) => statusLabel(row.status) },
]

export function ForecastsPage() {
  const navigate = useNavigate()
  const [campaigns, setCampaigns] = useState<CampaignSummary[] | null>(null)
  const [forecasts, setForecasts] = useState<ForecastListItem[]>([])
  const [error, setError] = useState('')
  const [draftA, setDraftA] = useState<VariantDraft>(EMPTY_DRAFT)
  const [draftB, setDraftB] = useState<VariantDraft>(EMPTY_DRAFT)
  const [comparing, setComparing] = useState(false)
  const [compareError, setCompareError] = useState('')
  const [results, setResults] = useState<CompareResult[] | null>(null)
  const [pickOpen, setPickOpen] = useState(false)
  const [exportError, setExportError] = useState('')
  const table = useSortTable(forecasts, FORECAST_COLUMNS)
  const [page, setPage] = useState(1)

  useEffect(() => {
    Promise.all([api.campaigns(), api.forecasts()])
      .then(([campaignList, forecastList]) => {
        setCampaigns(campaignList)
        setForecasts(forecastList)
        const first = campaignList[0]
        if (first) {
          const base: VariantDraft = {
            campaignId: first.id,
            budget: String(first.budget),
            duration: '90',
            startMonth: String(new Date().getMonth() + 1),
          }
          setDraftA(base)
          setDraftB({ ...base, budget: String(Math.round(first.budget * 0.6)), duration: '60' })
        }
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить прогнозы'))
  }, [])

  const buildVariant = (draft: VariantDraft): CompareVariant | string => {
    const campaign = campaigns?.find((item) => item.id === draft.campaignId)
    if (!campaign) {
      return 'выберите кампанию для обоих вариантов'
    }
    const budget = Number(draft.budget.replace(/\s/g, '').replace(',', '.'))
    const duration = Number(draft.duration)
    if (!Number.isFinite(budget) || budget <= 0) {
      return 'бюджет варианта должен быть положительным числом'
    }
    if (!Number.isInteger(duration) || duration < 1 || duration > 365) {
      return 'длительность варианта — целое число от 1 до 365 дней'
    }
    return {
      platform: campaign.platform,
      budget,
      durationDays: duration,
      startMonth: Number(draft.startMonth),
    }
  }

  const compare = async () => {
    if (comparing) {
      return
    }
    setCompareError('')
    const variantA = buildVariant(draftA)
    const variantB = buildVariant(draftB)
    if (typeof variantA === 'string') {
      setCompareError(variantA)
      return
    }
    if (typeof variantB === 'string') {
      setCompareError(variantB)
      return
    }
    setComparing(true)
    try {
      setResults(await api.compare([variantA, variantB]))
    } catch (cause) {
      setCompareError(cause instanceof ApiError ? cause.message : 'не удалось сравнить варианты')
    } finally {
      setComparing(false)
    }
  }

  const exportReport = async (forecastId: number, format: 'xlsx' | 'pdf') => {
    setExportError('')
    try {
      await downloadFile(`/api/forecasts/${forecastId}/export/${format}`, `forecast_${forecastId}.${format}`)
    } catch (cause) {
      setExportError(cause instanceof ApiError ? cause.message : 'не удалось выгрузить отчёт')
    }
  }

  const winner = useMemo(() => {
    if (!results || results.length < 2) {
      return -1
    }
    return results[0].forecastRoi >= results[1].forecastRoi ? 0 : 1
  }, [results])

  if (!campaigns) {
    return <Layout>{error ? <ErrorBox message={error} /> : <PageLoader />}</Layout>
  }

  const drafts = [draftA, setDraftA, draftB, setDraftB] as const
  const forecastPages = Math.max(1, Math.ceil(table.rows.length / FORECAST_PAGE_SIZE))
  const forecastPage = Math.min(page, forecastPages)
  const pageRows = table.rows.slice((forecastPage - 1) * FORECAST_PAGE_SIZE, forecastPage * FORECAST_PAGE_SIZE)

  return (
    <Layout>
      <div className="page-head">
        <div>
          <h1>Прогнозы</h1>
          <div className="sub">Сравнение вариантов кампаний и история всех расчётов</div>
        </div>
        <button className="cta" onClick={() => setPickOpen(true)}>＋ Создать прогноз</button>
      </div>

      <div className="card">
        <div className="card-head">
          <div>
            <h2>Сравнение вариантов</h2>
            <div className="csub">два сценария бюджета и длительности — куда выгоднее вложиться</div>
          </div>
        </div>
        <div className="compare-form">
          {([0, 1] as const).map((index) => {
            const draft = index === 0 ? drafts[0] : drafts[2]
            const setDraft = index === 0 ? drafts[1] : drafts[3]
            return (
              <div className="variant-editor" key={index}>
                <span className="vtitle">Вариант {index === 0 ? 'А' : 'Б'}</span>
                <div className="field">
                  <label>Кампания (платформа)</label>
                  <Select
                    value={draft.campaignId === null ? '' : String(draft.campaignId)}
                    options={campaigns.map((campaign) => ({
                      value: String(campaign.id),
                      label: `${campaign.campaignName} · ${campaign.platform}`,
                    }))}
                    placeholder="выберите кампанию"
                    onChange={(next) => {
                      const campaign = campaigns.find((item) => item.id === Number(next))
                      setDraft({
                        ...draft,
                        campaignId: campaign?.id ?? null,
                        budget: campaign ? String(campaign.budget) : draft.budget,
                      })
                    }}
                  />
                </div>
                <div className="form-row">
                  <div className="field">
                    <label>Бюджет</label>
                    <input inputMode="decimal" value={draft.budget} onChange={(event) => setDraft({ ...draft, budget: event.target.value })} />
                  </div>
                  <div className="field">
                    <label>Длительность, дней</label>
                    <input inputMode="numeric" value={draft.duration} onChange={(event) => setDraft({ ...draft, duration: event.target.value })} />
                  </div>
                  <div className="field">
                    <label>Месяц старта</label>
                    <Select
                      value={draft.startMonth}
                      options={MONTHS.map((name, monthIndex) => ({ value: String(monthIndex + 1), label: name }))}
                      onChange={(next) => setDraft({ ...draft, startMonth: next })}
                    />
                  </div>
                </div>
              </div>
            )
          })}
          <button className="cta" style={{ padding: '12px 24px' }} onClick={compare} disabled={comparing}>
            {comparing ? 'Считаем…' : 'Сравнить'}
          </button>
        </div>
        {compareError && <div className="form-error" style={{ marginTop: 12 }}>{compareError}</div>}

        {results && (
          <>
            <div className="compare-result">
              {results.map((result, index) => (
                <div className={`variant${winner === index ? ' winner' : ''}`} key={index}>
                  <div className="name">
                    {index === 0 ? 'А' : 'Б'} · {fmtMoney(result.budget)} / {fmtDays(result.durationDays)}
                    {winner === index && <span className="crown">✓ выгоднее</span>}
                  </div>
                  <div className="roi">{fmtRatio(result.forecastRoi)}</div>
                  <div className="meta">
                    {result.platform} · вероятность {fmtPercent(result.paybackProbability)}
                    {' · срок '}{result.paybackMonths === null ? 'не окупается' : fmtDays(result.paybackMonths * 30.44)}
                  </div>
                  <div className="track">
                    <div className="fill" style={{ width: `${Math.round(result.paybackProbability * 100)}%` }} />
                  </div>
                </div>
              ))}
            </div>
            {results.length >= 2 && (
              <div className="verdict">
                💡
                <CompareVerdict results={results} winner={winner} />
              </div>
            )}
          </>
        )}
      </div>

      <div className="card" style={{ marginTop: 18 }}>
        <div className="card-head">
          <div>
            <h2>История прогнозов</h2>
            <div className="csub">все расчёты по вашим кампаниям</div>
          </div>
        </div>
        {exportError && <div className="form-error" style={{ marginBottom: 12 }}>{exportError}</div>}
        {forecasts.length === 0
          ? <EmptyState>прогнозов пока нет — создайте первый из карточки кампании</EmptyState>
          : (
            <table>
              <thead>
                <tr>
                  {FORECAST_COLUMNS.map((column) => <SortTh key={column.id} table={table} column={column} />)}
                  <th>Отчёт</th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((forecast) => {
                  const tone = forecast.status === 'COMPLETED' ? paybackTone(ratioValue(forecast.forecastRoi)) : null
                  return (
                    <tr key={forecast.id}>
                      <td className="mono">{fmtDateTime(forecast.startedAt)}</td>
                      <td
                        className="clickable"
                        style={{ cursor: 'pointer' }}
                        onClick={() => navigate(`/campaigns/${forecast.campaignId}`)}
                      >
                        {forecast.campaignName}
                      </td>
                      {forecast.status === 'COMPLETED'
                        ? (
                          <>
                            <td className="mono" style={{ fontWeight: 700, color: tone ? `var(--${tone === 'ok' ? 'ok' : tone === 'mid' ? 'warn' : 'bad'})` : undefined }}>
                              {fmtRatio(forecast.forecastRoi)}
                            </td>
                            <td className="mono">{fmtPercent(forecast.paybackProbability)}</td>
                            <td>{forecast.paybackMonths === null ? '—' : fmtDays(forecast.paybackMonths * 30.44)}</td>
                          </>
                          )
                        : <td colSpan={3} style={{ color: 'var(--muted)' }}>{forecast.status === 'FAILED' ? 'расчёт завершился с ошибкой' : 'расчёт выполняется…'}</td>}
                      <td>
                        <StatusPill
                          tone={forecast.status === 'COMPLETED' ? 'ok' : forecast.status === 'FAILED' ? 'bad' : 'run'}
                          label={statusLabel(forecast.status)}
                        />
                      </td>
                      <td>
                        <div className="dl">
                          <button disabled={forecast.status !== 'COMPLETED'} onClick={() => exportReport(forecast.id, 'xlsx')}>XLSX</button>
                          <button disabled={forecast.status !== 'COMPLETED'} onClick={() => exportReport(forecast.id, 'pdf')}>PDF</button>
                        </div>
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            )}
        <div className="table-foot">
          <span className="rows-range">
            {rangeLabel(forecastPage, FORECAST_PAGE_SIZE, pageRows.length, table.rows.length, ['прогноз', 'прогноза', 'прогнозов'])}
          </span>
          <Pager page={forecastPage} pageCount={forecastPages} onChange={setPage} />
        </div>
      </div>

      <PickCampaignModal open={pickOpen} campaigns={campaigns} onClose={() => setPickOpen(false)} />
    </Layout>
  )
}

function CompareVerdict({ results, winner }: { results: CompareResult[]; winner: number }) {
  const best = results[winner]
  const other = results[winner === 0 ? 1 : 0]
  const gain = (best.forecastRoi - other.forecastRoi) / 100
  const bestMonths = best.paybackMonths
  const otherMonths = other.paybackMonths
  return (
    <span>
      При бюджете <b>{fmtMoney(best.budget)}</b> ({best.platform}) отдача выше на <b>{fmtDecimal(gain)}</b> коэффициента.
      {bestMonths !== null && otherMonths !== null && bestMonths !== otherMonths && (
        <>
          {' '}Вариант {winner === 0 ? 'Б' : 'А'} окупится{' '}
          {bestMonths < otherMonths ? 'позже' : 'раньше'} на <b>{fmtDays(Math.abs(otherMonths - bestMonths) * 30.44)}</b>.
        </>
      )}
      {(bestMonths === null || otherMonths === null) && bestMonths === null && (
        <> При этом вариант {winner === 0 ? 'А' : 'Б'} по прогнозу не окупается вовсе.</>
      )}
    </span>
  )
}

function PickCampaignModal({ open, campaigns, onClose }: {
  open: boolean
  campaigns: CampaignSummary[]
  onClose: () => void
}) {
  const navigate = useNavigate()
  const [search, setSearch] = useState('')

  useEffect(() => {
    if (open) {
      setSearch('')
    }
  }, [open])

  const query = search.trim().toLowerCase()
  const visible = campaigns.filter((campaign) => !query || campaign.campaignName.toLowerCase().includes(query))
  const planned = visible.filter((campaign) => campaign.status === 'PLANNED')
  const active = visible.filter((campaign) => campaign.status === 'ACTIVE')
  const finished = visible.filter((campaign) => campaign.status === 'FINISHED')

  const pick = (id: number) => {
    onClose()
    navigate(`/campaigns/${id}`)
  }

  const group = (title: string, items: CampaignSummary[]) => items.length > 0 && (
    <div key={title}>
      <div className="pick-group">{title}</div>
      {items.map((campaign) => {
        const roi = campaign.latestForecastRoi ?? campaign.actualRoi
        const tone = campaign.status === 'PLANNED' ? null : paybackTone(ratioValue(roi))
        return (
          <div className="pick" key={campaign.id} onClick={() => pick(campaign.id)}>
            <div className="main">
              <div className="t">{campaign.campaignName}</div>
              <div className="s">
                {campaign.platform} · {fmtMoney(campaign.budget, campaign.currency)}
                {roi !== null ? ` · прогноз ${fmtRatio(roi)}` : ''}
              </div>
            </div>
            {campaign.status === 'PLANNED'
              ? <StatusPill tone="plan" label="планируется" />
              : <StatusPill tone={tone} label={paybackLabel(tone)} />}
            <span className="arrow">→</span>
          </div>
        )
      })}
    </div>
  )

  return (
    <Modal
      open={open}
      onClose={onClose}
      title="Создать прогноз"
      subtitle="выберите кампанию — прогноз строится по её параметрам"
    >
      <input
        className="pick-search"
        placeholder="Поиск кампании…"
        value={search}
        onChange={(event) => setSearch(event.target.value)}
      />
      {visible.length === 0 && <EmptyState>кампании не найдены</EmptyState>}
      {group('Планируемые — прогноз до запуска', planned)}
      {group('Активные — пересчитать по текущим данным', active)}
      {group('Завершённые', finished)}
      <div className="pick-foot">
        Откроется карточка кампании с формой прогноза: горизонт, месяц старта и доверительный уровень
        можно изменить перед расчётом.
      </div>
    </Modal>
  )
}
