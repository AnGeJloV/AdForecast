import { useCallback, useEffect, useState } from 'react'
import type { FormEvent } from 'react'
import { Link, useNavigate, useParams } from 'react-router-dom'
import { api, ApiError, downloadFile } from '../api/client'
import type {
  CampaignDetail,
  CampaignStatus,
  DailyMetricPoint,
  ForecastResult,
  ModelScope,
  UploadItem,
} from '../api/types'
import { Layout } from '../components/Layout'
import { Modal } from '../components/Modal'
import { CashflowChart } from '../components/Charts'
import { Select } from '../components/Select'
import { EmptyState, ErrorBox, PageLoader, StatusPill } from '../components/Ui'
import {
  fmtCoef,
  fmtDate,
  fmtDateTime,
  fmtDays,
  fmtDecimal,
  fmtMoney,
  fmtNum,
  fmtPercent,
  fmtRatio,
  fmtSigned,
  MONTHS,
  paybackLabel,
  paybackTone,
  plural,
  ratioValue,
  scopeLabel,
  verdictLabel,
  verdictTone,
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
  const [finishOpen, setFinishOpen] = useState(false)

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
  const tone = campaign.latestVerdict
    ? verdictTone(campaign.latestVerdict)
    : campaign.status === 'PLANNED'
      ? null
      : paybackTone(ratioValue(campaign.actualRoi))
  const pillLabel = campaign.latestVerdict
    ? verdictLabel(campaign.latestVerdict).toLowerCase()
    : campaign.status === 'PLANNED'
      ? 'планируется'
      : paybackLabel(tone)

  return (
    <Layout>
      <Link className="back" to="/campaigns">← Все кампании</Link>
      <div className="page-head">
        <div>
          <h1>
            {campaign.campaignName}
            {campaign.status !== 'FINISHED' && <StatusPill tone={tone} label={pillLabel} />}
            {campaign.status === 'FINISHED' && (
              <StatusPill tone={paybackTone(ratioValue(campaign.actualRoi))} label={paybackLabel(paybackTone(ratioValue(campaign.actualRoi)))} />
            )}
          </h1>
          <div className="sub">
            {campaign.platform} · {campaign.accountName} · {campaign.daysCount > 0
              ? `${fmtNum(campaign.daysCount)} ${plural(Number(campaign.daysCount), ['день', 'дня', 'дней'])} данных`
              : 'нет загруженных данных'}
          </div>
        </div>
        <div className="actions">
          {campaign.status !== 'FINISHED' && (
            <button className="ghost-btn" onClick={() => setFinishOpen(true)}>Завершить кампанию</button>
          )}
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

          {campaign.status === 'FINISHED' ? (
            <div className="card">
              <div className="card-head">
                <div>
                  <h2>Окупаемость по факту</h2>
                  <div className="csub">кампания завершена — прогноз не требуется</div>
                </div>
              </div>
              <div className="fact-line">
                Вложено {fmtMoney(campaign.totalCost, campaign.currency)}, получено {fmtMoney(campaign.totalRevenue, campaign.currency)}
                {' — коэффициент '}{fmtCoef(campaign.totalCost > 0 ? campaign.totalRevenue / campaign.totalCost : null)}
              </div>
            </div>
          ) : (
            <ForecastCard
              campaign={campaign}
              lastForecast={lastForecast}
              onCompleted={(forecast) => setLastForecast(forecast)}
            />
          )}
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

      <FinishCampaignModal
        open={finishOpen}
        campaign={campaign}
        onClose={() => setFinishOpen(false)}
        onFinished={() => {
          setFinishOpen(false)
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
  const isPlanned = campaign.status === 'PLANNED'
  const hasHistory = campaign.daysCount > 0
  const defaultDuration = hasHistory ? campaign.daysCount + 30 : 90
  const [duration, setDuration] = useState(String(defaultDuration))
  const [startMonth, setStartMonth] = useState(String(new Date().getMonth() + 1))
  const [scope, setScope] = useState<ModelScope>('BLEND')
  const [busy, setBusy] = useState(false)
  const [error, setError] = useState('')
  const [result, setResult] = useState<ForecastResult | null>(lastForecast)
  const [exportError, setExportError] = useState('')

  useEffect(() => setResult(lastForecast), [lastForecast])
  useEffect(() => setDuration(String(defaultDuration)), [defaultDuration])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) {
      return
    }
    setError('')
    const durationValue = Number(duration)
    if (!Number.isInteger(durationValue) || durationValue < 1 || durationValue > 365) {
      setError('длительность кампании — целое число от 1 до 365 дней')
      return
    }
    if (hasHistory && durationValue <= campaign.daysCount) {
      setError(`кампания уже длится ${campaign.daysCount} ${plural(campaign.daysCount, ['день', 'дня', 'дней'])} — укажите полную длительность больше`)
      return
    }
    setBusy(true)
    try {
      const forecast = await api.runForecast(campaign.id, {
        durationDays: durationValue,
        startMonth: isPlanned ? Number(startMonth) : null,
        modelScope: scope,
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

  const tone = verdictTone(result?.verdict ?? null)
  const ratio = result?.paybackRatio ?? null
  const currency = campaign.currency
  const expectedReturn = result && ratio !== null ? campaign.budget * ratio : null
  const intervalLower = result?.intervalLower ?? 0
  const intervalUpper = result?.intervalUpper ?? intervalLower
  const returnFrom = campaign.budget * intervalLower
  const returnTo = campaign.budget * intervalUpper
  const profit = result?.expectedProfit ?? null
  const isWarning = result !== null
    && (result.verdict === 'OUT_OF_RANGE' || result.verdict === 'BELOW_MINIMUM'
      || result.verdict === 'NO_CHANNEL_DATA')

  const scaleMax = Math.max(intervalUpper * 1.15, 1.25)
  const lowerPct = Math.min(100, (intervalLower / scaleMax) * 100)
  const widthPct = Math.min(100 - lowerPct, ((intervalUpper - intervalLower) / scaleMax) * 100)
  const breakEvenPct = Math.min(100, (1 / scaleMax) * 100)

  let advice = ''
  if (result) {
    const min = result.budgetMin
    const max = result.budgetMax
    const range = min !== null && max !== null
      ? `от ${fmtMoney(min, currency)} до ${fmtMoney(max, currency)}`
      : min !== null
        ? `от ${fmtMoney(min, currency)} и выше`
        : max !== null
          ? `до ${fmtMoney(max, currency)}`
          : null
    if (range !== null) {
      advice = result.verdict === 'UNPROFITABLE'
        ? `Окно окупаемости по бюджету — ${range}: текущий бюджет ${fmtMoney(campaign.budget, currency)} вне него.`
        : `Окупаемость сохраняется при бюджете ${range}.`
    } else if (result.budgetAny) {
      advice = 'Окупается при любом разумном бюджете — канал и месяц работают уверенно.'
    } else {
      advice = 'Окна окупаемости по бюджету нет при этом канале, месяце и длительности — сравните варианты в разделе «Прогнозы».'
    }
  }

  return (
    <div className="card">
      <div className="card-head">
        <div>
          <h2>Прогноз окупаемости</h2>
          <div className="csub">
            {isPlanned
              ? 'какой будет возврат средств, если запустить кампанию'
              : 'каким будет итог кампании, если текущие темпы сохранятся'}
          </div>
        </div>
      </div>
      <form onSubmit={submit}>
        <div className="form-row">
          <div className="field">
            <label>{isPlanned ? 'Длительность кампании, дней' : 'Всего продлится кампания, дней'}</label>
            <input inputMode="numeric" value={duration} onChange={(event) => setDuration(event.target.value)} />
            {hasHistory && (
              <div className="hint">
                уже есть данные за {campaign.daysCount} {plural(campaign.daysCount, ['день', 'дня', 'дней'])}
              </div>
            )}
          </div>
          {isPlanned && (
            <div className="field">
              <label>Месяц старта</label>
              <Select
                value={startMonth}
                options={MONTHS.map((name, index) => ({ value: String(index + 1), label: name }))}
                onChange={setStartMonth}
              />
            </div>
          )}
          <div className="field">
            <label>На чём основан прогноз</label>
            <Select
              value={scope}
              options={[
                { value: 'BLEND', label: 'Своя + общая (рекомендуется)' },
                { value: 'OWN', label: 'Только свои данные' },
                { value: 'GENERAL', label: 'Только общая модель' },
              ]}
              onChange={(next) => setScope(next as ModelScope)}
            />
          </div>
          <div className="field" style={{ flex: '0 0 auto', display: 'flex', alignItems: 'flex-end' }}>
            <button className="cta" style={{ padding: '11px 22px' }} type="submit" disabled={busy}>
              {busy ? 'Считаем…' : 'Рассчитать'}
            </button>
          </div>
        </div>
      </form>
      {error && <div className="form-error" style={{ marginTop: 12 }}>{error}</div>}
      {result && !isPlanned && (result.historyDays ?? 0) !== campaign.daysCount && (
        <div className="stale-hint">
          С момента расчёта появились новые данные: сейчас {campaign.daysCount}{' '}
          {plural(campaign.daysCount, ['день', 'дня', 'дней'])} — пересчитайте прогноз.
        </div>
      )}
      {result && String(result.durationDays) !== duration && (
        <div className="stale-hint">
          В форме указана длительность {duration} {plural(Number(duration), ['день', 'дня', 'дней'])}, а показанный
          результат рассчитан на {result.durationDays} {plural(Number(result.durationDays ?? 0), ['день', 'дня', 'дней'])} —
          нажмите «Рассчитать», чтобы пересчитать.
        </div>
      )}

      {result && tone && (
        <div className={`result ${tone}`}>
          {isWarning ? (
            <>
              <div className="verdict">
                {result.verdict === 'BELOW_MINIMUM' && '⚠ Бюджет ниже минимума площадки'}
                {result.verdict === 'NO_CHANNEL_DATA' && '⚠ По этому каналу нет данных для прогноза'}
                {result.verdict === 'OUT_OF_RANGE' && '⚠ По такому бюджету прогноз недостоверен'}
              </div>
              <div className="money-line">{result.basis}</div>
              {result.dayBudgetMin !== null && result.dayBudgetMax !== null && (
                <div className="basis">
                  Модель знает дневные бюджеты от {fmtMoney(result.dayBudgetMin, currency)} до{' '}
                  {fmtMoney(result.dayBudgetMax, currency)} в день — при таком бюджете оценивать нечего.
                </div>
              )}
              {result.dataNote && <div className="basis">Источник и пояснение: {result.dataNote}</div>}
              <div className="advice">
                {result.verdict === 'NO_CHANNEL_DATA'
                  ? 'Общая модель этот канал не знает. Когда по нему появятся ваши завершённые кампании, модель организации обучится на них — до тех пор прогноз по каналу не выдаётся.'
                  : 'Проверьте бюджет и длительность — вероятно, в бюджете опечатка. После исправления нажмите «Рассчитать».'}
              </div>
            </>
          ) : (
            <>
              <div className="verdict">
                {tone === 'ok' && '✓'}{tone === 'mid' && '⚠'}{tone === 'bad' && '✕'}
                {' '}{verdictLabel(result.verdict)}
              </div>
              <div className="money-line">
                Вложите {fmtMoney(campaign.budget, currency)} — вернётся ≈ {fmtMoney(expectedReturn, currency)}
                <span className="range"> (от {fmtMoney(returnFrom)} до {fmtMoney(returnTo)})</span>.
                {' '}{profit !== null && profit >= 0 ? 'Прибыль ≈ ' : 'Потери ≈ '}
                <b>{fmtSigned(profit)} {currency}</b>
              </div>
              <div className="interval-wrap">
                <div className="interval-track">
                  <div className="interval-fill" style={{ left: `${lowerPct}%`, width: `${widthPct}%` }} />
                  <div className="interval-target" style={{ left: `${breakEvenPct}%` }} />
                </div>
                <div className="interval-labels">
                  <span>интервал 80 %: {fmtCoef(intervalLower)}–{fmtCoef(intervalUpper)}</span>
                  <span className="target">точка окупаемости ×1,0</span>
                </div>
              </div>
              <div className="nums">
                <div className="n">
                  <div className="v">{fmtCoef(ratio)}</div>
                  <div className="k">вернётся на каждый вложенный {currency}</div>
                </div>
                <div className="n">
                  <div className="v">
                    {result.agreement === null ? '—' : fmtPercent(result.agreement)}
                  </div>
                  <div className="k">
                    {result.agreement === null ? 'оценка по фактам, ансамбля нет' : 'согласованность моделей'}
                  </div>
                </div>
                <div className="n">
                  <div className="v">
                    {result.paybackWithinDuration
                      ? (result.paybackDays === null ? '—' : fmtDays(result.paybackDays))
                      : 'вне срока'}
                  </div>
                  <div className="k">
                    {result.paybackWithinDuration
                      ? 'срок возврата средств'
                      : result.paybackDays === null
                        ? 'не окупится за время кампании'
                        : `возврат после окончания кампании (≈${fmtDays(result.paybackDays)} с хвостом дохода)`}
                  </div>
                </div>
                {(result.historyDays ?? 0) > 0 && (
                  <div className="n">
                    <div className="v">{result.historyDays}</div>
                    <div className="k">дней данных кампании учтено</div>
                  </div>
                )}
              </div>
              {advice && <div className="advice">{advice}</div>}
            </>
          )}
          {result.dataNote && <div className="data-note">⚠ {result.dataNote}</div>}
          {result.basis && <div className="basis">Основание: {result.basis}</div>}
          <div className="foot">
            <span className="model">
              расчёт на {result.durationDays} {plural(Number(result.durationDays ?? 0), ['день', 'дня', 'дней'])}
              {' · бюджет '}{fmtMoney(campaign.budget, currency)}
              {result.durationDays ? ` (${fmtMoney(campaign.budget / result.durationDays, currency)} в день)` : ''}
              {' · область: '}{scopeLabel(result.modelScope)}
              {' · своя '}{result.ownModelVersion ?? '—'} · {fmtPercent(result.ownWeight)}
              {' · общая '}{result.generalModelVersion ?? '—'} · MAPE {result.mape === null ? '—' : `${fmtDecimal(result.mape)} %`}
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

function FinishCampaignModal({ open, campaign, onClose, onFinished }: {
  open: boolean
  campaign: CampaignDetail
  onClose: () => void
  onFinished: () => void
}) {
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (open) {
      setError('')
    }
  }, [open])

  const finish = async () => {
    if (busy) {
      return
    }
    setError('')
    setBusy(true)
    try {
      await api.updateCampaign(campaign.id, {
        campaignName: campaign.campaignName,
        budget: campaign.budget,
        currency: campaign.currency,
        status: 'FINISHED',
      })
      onFinished()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось завершить кампанию')
    } finally {
      setBusy(false)
    }
  }

  const ratio = campaign.totalCost > 0 ? campaign.totalRevenue / campaign.totalCost : null

  return (
    <Modal open={open} onClose={onClose} title="Завершить кампанию" subtitle={campaign.campaignName}>
      {error && <div className="form-error">{error}</div>}
      <p style={{ marginTop: 0, lineHeight: 1.7 }}>
        Кампания станет обучающим примером для модели вашей организации — она сама переобучится на новых
        данных, — и появится в разделе «план против факта».
      </p>
      <table style={{ marginBottom: 4 }}>
        <tbody>
          <tr><td style={{ color: 'var(--muted)' }}>Израсходовано</td><td><b>{fmtMoney(campaign.totalCost, campaign.currency)}</b></td></tr>
          <tr><td style={{ color: 'var(--muted)' }}>Получено</td><td><b>{fmtMoney(campaign.totalRevenue, campaign.currency)}</b></td></tr>
          <tr>
            <td style={{ color: 'var(--muted)' }}>Фактическая окупаемость</td>
            <td>
              <b style={{ color: ratio !== null && ratio >= 1 ? 'var(--ok)' : 'var(--bad)' }}>{fmtCoef(ratio)}</b>
              {' '}на каждый вложенный {campaign.currency}
            </td>
          </tr>
        </tbody>
      </table>
      <div className="csub" style={{ marginBottom: 4 }}>
        После завершения прогноз по кампании больше не рассчитывается — вместо него показывается факт.
      </div>
      <div className="foot">
        <button type="button" className="ghost-btn" onClick={onClose}>Отмена</button>
        <button type="button" className="cta" disabled={busy} onClick={finish}>
          {busy ? 'Завершаем…' : 'Завершить кампанию'}
        </button>
      </div>
    </Modal>
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
          <div className="field" style={{ flex: '0 0 130px' }}>
            <label>Валюта</label>
            <Select
              value={currency}
              options={['BYN', 'USD', 'EUR', 'RUB'].map((code) => ({ value: code, label: code }))}
              onChange={setCurrency}
            />
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
