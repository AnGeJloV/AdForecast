import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, downloadFile } from '../api/client'
import type {
  CampaignDetail,
  CampaignStatus,
  DailyMetricPoint,
  ForecastResult,
  UploadItem,
} from '../api/types'
import { Layout } from '../components/Layout'
import { Modal } from '../components/Modal'
import { CashflowChart } from '../components/Charts'
import { Select } from '../components/Select'
import { EmptyState, ErrorBox, PageLoader, StatusPill } from '../components/Ui'
import {
  fmtDate,
  fmtDateTime,
  fmtDays,
  fmtDecimal,
  fmtMoney,
  fmtNum,
  fmtPercent,
  fmtRatio,
  MONTHS,
  paybackLabel,
  paybackTone,
  plural,
  ratioValue,
} from '../utils/format'

export function CampaignPage() {
  const { id } = useParams<{ id: string }>()
  const campaignId = Number(id)
  const navigate = useNavigate()
  const [campaign, setCampaign] = useState<CampaignDetail | null>(null)
  const [uploads, setUploads] = useState<UploadItem[]>([])
  const [metrics, setMetrics] = useState<DailyMetricPoint[]>([])
  const [lastForecast, setLastForecast] = useState<ForecastResult | null>(null)
  const [error, setError] = useState('')
  const [editOpen, setEditOpen] = useState(false)

  const load = useCallback(() => {
    Promise.all([
      api.campaign(campaignId),
      api.campaignUploads(campaignId),
      api.campaignMetrics(campaignId),
      api.campaignForecasts(campaignId),
    ])
      .then(([detail, uploadList, metricList, forecastList]) => {
        setCampaign(detail)
        setUploads(uploadList)
        setMetrics(metricList)
        setLastForecast(forecastList.find((forecast) => forecast.status === 'COMPLETED') ?? null)
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить кампанию'))
  }, [campaignId])

  useEffect(load, [load])

  if (!campaign) {
    return <Layout>{error ? <ErrorBox message={error} /> : <PageLoader />}</Layout>
  }

  const spent = campaign.budget > 0 ? Math.min((campaign.totalCost / campaign.budget) * 100, 100) : 0
  const ctr = campaign.totalImpressions > 0 ? (campaign.totalClicks / campaign.totalImpressions) * 100 : null
  const cpc = campaign.totalClicks > 0 ? campaign.totalCost / campaign.totalClicks : null
  const roi = campaign.latestForecastRoi ?? campaign.actualRoi
  const tone = campaign.status === 'PLANNED' ? null : paybackTone(ratioValue(roi))

  return (
    <Layout>
      <Link className="back" to="/campaigns">← Все кампании</Link>
      <div className="page-head">
        <div>
          <h1>
            {campaign.campaignName}
            {campaign.status === 'PLANNED'
              ? <StatusPill tone="plan" label="планируется" />
              : <StatusPill tone={tone} label={paybackLabel(tone)} />}
          </h1>
          <div className="sub">
            {campaign.platform} · {campaign.accountName} · {campaign.daysCount > 0
              ? `${fmtNum(campaign.daysCount)} ${plural(Number(campaign.daysCount), ['день', 'дня', 'дней'])} данных`
              : 'нет загруженных данных'}
          </div>
        </div>
        <div className="actions">
          <button className="ghost-btn" onClick={() => setEditOpen(true)}>Редактировать</button>
          <button className="ghost-btn" onClick={() => navigate(`/upload?campaign=${campaign.id}`)}>Загрузить выгрузку</button>
        </div>
      </div>

      <div className="grid cols">
        <div className="stack">
          <div className="card">
            <div className="card-head"><h2>Параметры</h2></div>
            <div className="budget">
              <div className="row">
                <span style={{ color: 'var(--muted)' }}>Израсходовано</span>
                <b>{fmtMoney(campaign.totalCost)} из {fmtMoney(campaign.budget, campaign.currency)}</b>
              </div>
              <div className="track"><div className="fill" style={{ width: `${spent}%` }} /></div>
            </div>
            <div className="kv"><span className="k">Доход (факт)</span><span className="v">{fmtMoney(campaign.totalRevenue, campaign.currency)}</span></div>
            <div className="kv"><span className="k">ROI (факт)</span><span className="v">{fmtRatio(campaign.actualRoi)}</span></div>
            <div className="kv"><span className="k">Показы</span><span className="v">{fmtNum(campaign.totalImpressions)}</span></div>
            <div className="kv"><span className="k">Клики</span><span className="v">{fmtNum(campaign.totalClicks)}</span></div>
            <div className="kv"><span className="k">CTR</span><span className="v">{ctr === null ? '—' : `${fmtDecimal(ctr)} %`}</span></div>
            <div className="kv"><span className="k">Стоимость клика</span><span className="v">{cpc === null ? '—' : fmtMoney(cpc, campaign.currency)}</span></div>
          </div>

          <div className="card">
            <div className="card-head">
              <h2>Выгрузки</h2>
              <span className="csub">{uploads.length} {plural(uploads.length, ['файл', 'файла', 'файлов'])}</span>
            </div>
            {uploads.length === 0
              ? <EmptyState>выгрузок пока нет — загрузите первую, чтобы появились метрики</EmptyState>
              : uploads.map((upload) => (
                <div className="upl" key={upload.id}>
                  <div className="fic">{upload.fileFormat === 'XLSX' ? 'XLS' : 'CSV'}</div>
                  <div className="main">
                    <div className="t">{upload.fileName}</div>
                    <div className="s">
                      {fmtDateTime(upload.uploadedAt)} · {fmtNum(upload.rowsCount)} {plural(Number(upload.rowsCount), ['строка', 'строки', 'строк'])}
                      {' · период '}{fmtDate(upload.periodStart)} – {fmtDate(upload.periodEnd)}
                    </div>
                  </div>
                  <span className="st" style={{ color: 'var(--ok)' }}>✓</span>
                </div>
              ))}
          </div>
        </div>

        <div className="stack">
          <div className="card">
            <div className="card-head">
              <div>
                <h2>Денежный поток</h2>
                <div className="csub">накопленный доход и расход по дням, {campaign.currency}</div>
              </div>
              <div className="legend">
                <span><i style={{ background: 'var(--accent)' }} />Доход</span>
                <span><i style={{ background: '#6B7280' }} />Расход</span>
              </div>
            </div>
            <CashflowChart metrics={metrics} />
          </div>

          <ForecastCard
            campaign={campaign}
            lastForecast={lastForecast}
            onCompleted={(forecast) => setLastForecast(forecast)}
          />
        </div>
      </div>

      <EditCampaignModal
        open={editOpen}
        campaign={campaign}
        onClose={() => setEditOpen(false)}
        onSaved={() => {
          setEditOpen(false)
          setCampaign(null)
          load()
        }}
      />
    </Layout>
  )
}

function ForecastCard({ campaign, lastForecast, onCompleted }: {
  campaign: CampaignDetail
  lastForecast: ForecastResult | null
  onCompleted: (forecast: ForecastResult) => void
}) {
  const [horizon, setHorizon] = useState('90')
  const [startMonth, setStartMonth] = useState(String(new Date().getMonth() + 1))
  const [confidence, setConfidence] = useState('0.9')
  const [seasonality, setSeasonality] = useState(true)
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<ForecastResult | null>(lastForecast)
  const [exportError, setExportError] = useState('')

  useEffect(() => setResult(lastForecast), [lastForecast])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) {
      return
    }
    setError('')
    const horizonValue = Number(horizon)
    if (!Number.isInteger(horizonValue) || horizonValue < 1 || horizonValue > 730) {
      setError('горизонт прогноза — целое число от 1 до 730 дней')
      return
    }
    setBusy(true)
    try {
      const forecast = await api.runForecast(campaign.id, {
        horizonDays: horizonValue,
        confidenceLevel: Number(confidence),
        useSeasonality: seasonality,
        startMonth: Number(startMonth),
      })
      setResult(forecast)
      onCompleted(forecast)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось рассчитать прогноз')
    } finally {
      setBusy(false)
    }
  }

  const exportReport = async (format: 'xlsx' | 'pdf') => {
    if (!result) {
      return
    }
    setExportError('')
    try {
      await downloadFile(`/api/forecasts/${result.id}/export/${format}`, `forecast_${result.id}.${format}`)
    } catch (cause) {
      setExportError(cause instanceof ApiError ? cause.message : 'не удалось выгрузить отчёт')
    }
  }

  const ratio = ratioValue(result?.forecastRoi ?? null)
  const verdictTone = !result
    ? null
    : result.paybackWithinHorizon && (result.paybackProbability ?? 0) >= 0.5
      ? 'ok'
      : (result.paybackProbability ?? 0) >= 0.5
        ? 'mid'
        : 'bad'

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>Прогноз окупаемости</h2>
          <div className="csub">параметры можно изменить и пересчитать</div>
        </div>
      </div>
      <form onSubmit={submit}>
        <div className="form-row">
          <div className="field">
            <label>Горизонт прогноза, дней</label>
            <input inputMode="numeric" value={horizon} onChange={(event) => setHorizon(event.target.value)} />
          </div>
          <div className="field">
            <label>Месяц старта</label>
            <Select
              value={startMonth}
              options={MONTHS.map((name, index) => ({ value: String(index + 1), label: name }))}
              onChange={setStartMonth}
            />
          </div>
          <div className="field">
            <label>Доверительный уровень</label>
            <Select
              value={confidence}
              options={[
                { value: '0.8', label: '80 %' },
                { value: '0.9', label: '90 %' },
                { value: '0.95', label: '95 %' },
              ]}
              onChange={setConfidence}
            />
          </div>
          <div className="field" style={{ flex: '0 0 auto' }}>
            <label className="checkbox-row">
              <input type="checkbox" className="chk" checked={seasonality} onChange={(event) => setSeasonality(event.target.checked)} />
              учитывать сезонность
            </label>
          </div>
          <div className="field" style={{ flex: '0 0 auto', display: 'flex', alignItems: 'flex-end' }}>
            <button className="cta" style={{ padding: '11px 22px' }} type="submit" disabled={busy}>
              {busy ? 'Считаем…' : 'Рассчитать'}
            </button>
          </div>
        </div>
      </form>
      {error && <div className="form-error" style={{ marginTop: 12 }}>{error}</div>}

      {result && verdictTone && (
        <div className={`result ${verdictTone === 'ok' ? '' : verdictTone}`}>
          <div className="verdict">
            {verdictTone === 'ok' && '✓ Кампания окупится в горизонте прогноза'}
            {verdictTone === 'mid' && '⚠ Окупаемость возможна, но за пределами горизонта'}
            {verdictTone === 'bad' && '✕ По прогнозу кампания не окупится'}
          </div>
          <div className="prob-track">
            <div className="prob-fill" style={{ width: `${Math.round((result.paybackProbability ?? 0) * 100)}%` }} />
          </div>
          <div className="nums">
            <div className="n">
              <div className="v">{fmtRatio(result.forecastRoi)}</div>
              <div className="k">коэффициент окупаемости</div>
            </div>
            <div className="n">
              <div className="v">{fmtPercent(result.paybackProbability)}</div>
              <div className="k">вероятность окупаемости</div>
            </div>
            <div className="n">
              <div className="v">{result.paybackDays === null ? '—' : fmtDays(result.paybackDays)}</div>
              <div className="k">срок окупаемости</div>
            </div>
            {ratio !== null && (
              <div className="n">
                <div className="v">{result.paybackWithinHorizon ? 'да' : 'нет'}</div>
                <div className="k">в горизонте {result.horizonDays} дн.</div>
              </div>
            )}
          </div>
          <div className="foot">
            <span className="model">
              модель {result.modelVersion ?? '—'} · CatBoost ×10 · MAPE {result.mape === null ? '—' : `${fmtDecimal(result.mape)} %`}
              {' · рассчитано '}{fmtDateTime(result.startedAt)}
            </span>
            <div className="dl">
              <button onClick={() => exportReport('xlsx')}>Экспорт XLSX</button>
              <button onClick={() => exportReport('pdf')}>Экспорт PDF</button>
            </div>
          </div>
          {exportError && <div className="form-error" style={{ marginTop: 10 }}>{exportError}</div>}
        </div>
      )}
    </div>
  )
}

function EditCampaignModal({ open, campaign, onClose, onSaved }: {
  open: boolean
  campaign: CampaignDetail
  onClose: () => void
  onSaved: () => void
}) {
  const [name, setName] = useState(campaign.campaignName)
  const [budget, setBudget] = useState(String(campaign.budget))
  const [currency, setCurrency] = useState(campaign.currency)
  const [status, setStatus] = useState<CampaignStatus>(campaign.status)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (open) {
      setName(campaign.campaignName)
      setBudget(String(campaign.budget))
      setCurrency(campaign.currency)
      setStatus(campaign.status)
      setError('')
    }
  }, [open, campaign])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) {
      return
    }
    setError('')
    const budgetValue = Number(budget.replace(/\s/g, '').replace(',', '.'))
    if (!name.trim()) {
      setError('укажите название кампании')
      return
    }
    if (!Number.isFinite(budgetValue) || budgetValue <= 0) {
      setError('бюджет должен быть положительным числом')
      return
    }
    setBusy(true)
    try {
      await api.updateCampaign(campaign.id, {
        campaignName: name.trim(),
        budget: budgetValue,
        currency: currency.trim() || campaign.currency,
        status,
      })
      onSaved()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось сохранить кампанию')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="Редактировать кампанию" subtitle={campaign.campaignName}>
      <form className="form" onSubmit={submit}>
        {error && <div className="form-error">{error}</div>}
        <div className="field">
          <label>Название</label>
          <input value={name} onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="form-row">
          <div className="field">
            <label>Бюджет</label>
            <input inputMode="decimal" value={budget} onChange={(event) => setBudget(event.target.value)} />
          </div>
          <div className="field" style={{ flex: '0 0 110px' }}>
            <label>Валюта</label>
            <input maxLength={10} value={currency} onChange={(event) => setCurrency(event.target.value)} />
          </div>
        </div>
        <div className="field">
          <label>Статус</label>
          <Select
            value={status}
            options={[
              { value: 'PLANNED', label: 'планируется' },
              { value: 'ACTIVE', label: 'активна' },
              { value: 'FINISHED', label: 'завершена' },
            ]}
            onChange={(next) => setStatus(next as CampaignStatus)}
          />
        </div>
        <div className="foot">
          <button type="button" className="ghost-btn" onClick={onClose}>Отмена</button>
          <button type="submit" className="cta" disabled={busy}>{busy ? 'Сохраняем…' : 'Сохранить'}</button>
        </div>
      </form>
    </Modal>
  )
}
