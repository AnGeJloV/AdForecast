import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type {
  CampaignSummary,
  CashflowGranularity,
  CashflowPoint,
  DashboardData,
  PlanFactRow,
} from '../api/types'
import { Layout } from '../components/Layout'
import { CashflowBars, CHART_COLORS, Donut } from '../components/Charts'
import { SortTh, useSortTable } from '../components/SortableTable'
import type { SortColumn } from '../components/SortableTable'
import { EmptyState, ErrorBox, PageLoader, Pager, StatusPill } from '../components/Ui'
import {
  fmtCoef,
  fmtDecimal,
  fmtMoney,
  fmtNum,
  fmtRatio,
  paybackLabel,
  paybackTone,
  rangeLabel,
  ratioValue,
  verdictLabel,
  verdictTone,
} from '../utils/format'

const PLAN_FACT_PAGE_SIZE = 6

type FlowPeriod = 'DAY' | 'WEEK' | 'MONTH' | 'ALL'

interface FlowPeriodOption {
  label: string
  granularity: CashflowGranularity
  limit: number | null
  caption: string
}

/** Варианты блока «Денежный поток»: шаг корзины и глубина истории. */
const FLOW_PERIODS: Record<FlowPeriod, FlowPeriodOption> = {
  DAY: { label: 'День', granularity: 'DAY', limit: 14, caption: 'по дням · последние 14 дней' },
  WEEK: { label: 'Недели', granularity: 'WEEK', limit: 12, caption: 'по неделям · последние 12 недель' },
  MONTH: { label: 'Месяцы', granularity: 'MONTH', limit: 12, caption: 'по месяцам · последние 12 месяцев' },
  ALL: { label: 'За всё время', granularity: 'QUARTER', limit: null, caption: 'по кварталам · вся история' },
}
const FLOW_PERIOD_ORDER: FlowPeriod[] = ['DAY', 'WEEK', 'MONTH', 'ALL']

const PLAN_FACT_COLUMNS: SortColumn<PlanFactRow>[] = [
  { id: 'name', title: 'Кампания', value: (row) => row.campaignName },
  { id: 'plan', title: 'Прогноз', value: (row) => row.forecastRoi },
  { id: 'fact', title: 'Факт', value: (row) => row.actualRoi },
  { id: 'deviation', title: 'Отклонение', value: (row) => (row.comparable ? row.deviation : null) },
]

export function DashboardPage() {
  const navigate = useNavigate()
  const [data, setData] = useState<DashboardData | null>(null)
  const [campaigns, setCampaigns] = useState<CampaignSummary[]>([])
  const [cashflow, setCashflow] = useState<CashflowPoint[]>([])
  const [flowPeriod, setFlowPeriod] = useState<FlowPeriod>('WEEK')
  const [error, setError] = useState('')

  const flowView = FLOW_PERIODS[flowPeriod]

  useEffect(() => {
    Promise.all([api.dashboard(), api.campaigns()])
      .then(([dashboard, campaignList]) => {
        setData(dashboard)
        setCampaigns(campaignList)
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить дашборд'))
  }, [])

  useEffect(() => {
    api.cashflow(flowView.granularity, flowView.limit)
      .then(setCashflow)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить денежный поток'))
  }, [flowView.granularity, flowView.limit])

  const currency = data?.baseCurrency ?? 'BYN'

  const structure = useMemo(() => {
    const buckets = { ok: 0, mid: 0, bad: 0 }
    for (const campaign of campaigns) {
      const tone = paybackTone(ratioValue(campaign.actualRoi))
      if (tone) {
        buckets[tone] += 1
      }
    }
    return buckets
  }, [campaigns])

  const flowTotals = useMemo(() => {
    const cost = cashflow.reduce((sum, point) => sum + point.cost, 0)
    const revenue = cashflow.reduce((sum, point) => sum + point.revenue, 0)
    return { cost, revenue, profit: revenue - cost }
  }, [cashflow])

  const planFactTable = useSortTable(data?.planVsFact ?? [], PLAN_FACT_COLUMNS)
  const [factPage, setFactPage] = useState(1)

  const factPages = Math.max(1, Math.ceil(planFactTable.rows.length / PLAN_FACT_PAGE_SIZE))
  const factCurrentPage = Math.min(factPage, factPages)
  const factRows = planFactTable.rows.slice((factCurrentPage - 1) * PLAN_FACT_PAGE_SIZE, factCurrentPage * PLAN_FACT_PAGE_SIZE)

  if (!data) {
    return <Layout>{error ? <ErrorBox message={error} /> : <PageLoader />}</Layout>
  }

  const comparableCount = data.planVsFact.filter((row) => row.comparable).length
  const withStructure = structure.ok + structure.mid + structure.bad

  return (
    <Layout>
      <div className="page-head">
        <div>
          <h1>Обзор кампаний</h1>
          <div className="sub">
            Что происходит с вашими кампаниями · на {new Date().toLocaleDateString('ru-RU', { day: 'numeric', month: 'long', year: 'numeric' })}
          </div>
        </div>
        <div className="head-metrics">
          <div className="hm">
            <span className="ic">📈</span>
            <div>
              <div className="v">{fmtRatio(data.actualRoi)}</div>
              <div className="l">средний ROI</div>
            </div>
          </div>
          <div className="hm">
            <span className="ic">✈</span>
            <div>
              <div className="v">{fmtNum(data.campaignsTotal)}</div>
              <div className="l">кампаний всего</div>
            </div>
          </div>
          <div className="hm">
            <span className="ic">🎯</span>
            <div>
              <div className="v">
                {fmtNum(data.forecastsCompleted)}
                <small>из {fmtNum(data.forecastsTotal)}</small>
              </div>
              <div className="l">успешных прогнозов</div>
            </div>
          </div>
        </div>
      </div>

      <div className="grid row-kpi">
        <div className="grid kpi-square">
          <div className="card stat stat3">
            <div className="label">Кампании</div>
            <div className="camp-grid">
              <div className="cell vsep"><div className="v">{fmtNum(data.campaignsActive)}</div><div className="k">активные</div></div>
              <div className="cell"><div className="v">{fmtNum(data.campaignsPlanned)}</div><div className="k">планируемые</div></div>
              <div className="cell full"><div className="v">{fmtNum(data.campaignsFinished)}</div><div className="k">завершённые</div></div>
            </div>
          </div>
          <div className="card stat">
            <div className="label">Вложено, {currency}</div>
            <div className="num">{fmtNum(data.totalCost)}</div>
            <div className="delta muted">бюджет всего: {fmtMoney(data.totalBudget, currency)}</div>
          </div>
          <div className="card stat">
            <div className="label">Получено дохода, {currency}</div>
            <div className="num">{fmtNum(data.totalRevenue)}</div>
            <div className={data.totalRevenue - data.totalCost >= 0 ? 'delta' : 'delta neg'}>
              прибыль: {fmtMoney(data.totalRevenue - data.totalCost, currency)}
            </div>
          </div>
          <div className="card stat">
            <div className="label">Прибыльность закрытых кампаний</div>
            <div className="num sm">{fmtNum(data.profitableCampaigns)} из {fmtNum(data.profitableCampaigns + data.unprofitableCampaigns)}</div>
            <div className="delta muted">с положительным фактическим ROI</div>
          </div>
        </div>

        <div className="stack-even">
          <div className="card">
            <div className="card-head"><h2>Структура кампаний</h2></div>
            <Donut
              centerValue={String(withStructure)}
              centerLabel="кампаний"
              segments={[
                { label: 'окупаемые', value: structure.ok, color: CHART_COLORS.ok },
                { label: 'на границе', value: structure.mid, color: CHART_COLORS.mid },
                { label: 'убыточные', value: structure.bad, color: CHART_COLORS.bad },
              ]}
            />
          </div>
          <div className="accent-card">
            <div>
              <h3>Ваши прогнозы</h3>
              <p>Сравнение вариантов бюджета и история расчётов с отчётами в XLSX и PDF.</p>
            </div>
            <div className="row">
              <span style={{ fontSize: 13, fontWeight: 600 }}>{fmtNum(data.forecastsTotal)} расчётов</span>
              <button className="round-btn solid" title="К прогнозам" onClick={() => navigate('/forecasts')}>↗</button>
            </div>
          </div>
        </div>
      </div>

      <div className="grid row-2">
        <div className="card">
          <div className="card-head">
            <div>
              <h2>Денежный поток</h2>
              <div className="csub">{flowView.caption} · все ваши кампании, {currency}</div>
            </div>
          </div>
          <CashflowBars
            points={cashflow}
            granularity={flowView.granularity}
            periods={FLOW_PERIOD_ORDER.map((value) => ({ value, label: FLOW_PERIODS[value].label }))}
            period={flowPeriod}
            onPeriodChange={(value) => setFlowPeriod(value as FlowPeriod)}
          />
          <div className="chart-foot">
            <div className="foot-pill"><span className="lbl">Вложено</span><b>{fmtNum(flowTotals.cost)}</b></div>
            <div className="foot-pill"><span className="lbl">Вернулось</span><b>{fmtNum(flowTotals.revenue)}</b></div>
            <div className="foot-pill accent"><span className="lbl">Прибыль</span><b>{fmtNum(flowTotals.profit)}</b></div>
          </div>
        </div>

        <div className="card">
          <div className="card-head">
            <div>
              <h2>Прогноз против факта</h2>
              <div className="csub">закрытые кампании — совпал ли прогноз</div>
            </div>
          </div>
          {data.planVsFact.length === 0
            ? <EmptyState>пока нет закрытых кампаний с прогнозами</EmptyState>
            : (
              <table>
                <thead>
                  <tr>
                    {PLAN_FACT_COLUMNS.map((column) => <SortTh key={column.id} table={planFactTable} column={column} />)}
                  </tr>
                </thead>
                <tbody>
                  {factRows.map((row) => (
                    <tr key={row.campaignId} className="clickable" onClick={() => navigate(`/campaigns/${row.campaignId}`)}>
                      <td>{row.campaignName}</td>
                      <td className="mono">{fmtRatio(row.forecastRoi)}</td>
                      <td className="mono">{fmtRatio(row.actualRoi)}</td>
                      <td>
                        <DeviationPill deviation={row.deviation} forecastRoi={row.forecastRoi} comparable={row.comparable} />
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              )}
          {data.planVsFact.length > 0 && (
            <div className="table-foot">
              <span className="rows-range">
                {rangeLabel(factCurrentPage, PLAN_FACT_PAGE_SIZE, factRows.length, planFactTable.rows.length, ['кампания', 'кампании', 'кампаний'])}
              </span>
              <Pager page={factCurrentPage} pageCount={factPages} onChange={setFactPage} />
            </div>
          )}
          {data.planVsFactAvgError !== null && (
            <div className="fact-note">
              Средняя ошибка прогноза по {comparableCount} {comparableCount === 1 ? 'сопоставимой кампании' : 'сопоставимым кампаниям'} —
              {' '}<b>{fmtDecimal(data.planVsFactAvgError / 100)}</b> на каждый вложенный
              {data.planVsFactAvgErrorPercent !== null && <> ({fmtDecimal(data.planVsFactAvgErrorPercent)} %)</>}.
            </div>
          )}
        </div>
      </div>

      {data.channels.length > 0 && (
        <div className="card table-card" style={{ marginTop: 18 }}>
          <div className="card-head">
            <div>
              <h2>Что окупается по факту</h2>
              <div className="csub">ваш опыт по завершённым кампаниям — куда деньги возвращаются, а куда нет</div>
            </div>
          </div>
          <table>
            <thead>
              <tr>
                <th>Канал</th>
                <th>Завершённых</th>
                <th>Окупаемых</th>
                <th>Средний ROI</th>
                <th>Вложено</th>
                <th>Вернулось</th>
              </tr>
            </thead>
            <tbody>
              {data.channels.map((channel) => (
                <tr key={channel.platform}>
                  <td>{channel.platform}</td>
                  <td className="mono">{fmtNum(channel.finishedCampaigns)}</td>
                  <td className="mono">
                    {fmtNum(channel.profitable)}
                    <span className="ch-share">
                      {' '}{Math.round(channel.finishedCampaigns > 0 ? (channel.profitable / channel.finishedCampaigns) * 100 : 0)} %
                    </span>
                  </td>
                  <td className="mono" style={{ fontWeight: 700, color: channel.avgRoi > 0 ? 'var(--ok)' : 'var(--bad)' }}>
                    {fmtRatio(channel.avgRoi)}
                  </td>
                  <td className="mono">{fmtMoney(channel.totalCost, currency)}</td>
                  <td className="mono">{fmtMoney(channel.totalRevenue, currency)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}

      <div className="card table-card">
        <div className="card-head">
          <div>
            <h2>Последние кампании</h2>
            <div className="csub">последние добавленные — нажмите на строку, чтобы открыть</div>
          </div>
          <button className="link" onClick={() => navigate('/campaigns')}>Все кампании →</button>
        </div>
        {campaigns.length === 0
          ? <EmptyState>пока нет кампаний</EmptyState>
          : (
            <table>
              <thead>
                <tr>
                  <th>Кампания</th>
                  <th>Платформа</th>
                  <th>Бюджет</th>
                  <th>Прогноз</th>
                  <th>Коэффициент</th>
                  <th>Статус</th>
                </tr>
              </thead>
              <tbody>
                {campaigns.slice(0, 6).map((campaign) => {
                  const tone = campaign.status === 'PLANNED'
                    ? null
                    : campaign.status === 'FINISHED'
                      ? paybackTone(ratioValue(campaign.actualRoi))
                      : campaign.latestVerdict
                        ? verdictTone(campaign.latestVerdict)
                        : paybackTone(ratioValue(campaign.actualRoi))
                  return (
                    <tr key={campaign.id} className="clickable" onClick={() => navigate(`/campaigns/${campaign.id}`)}>
                      <td>{campaign.campaignName}</td>
                      <td>{campaign.platform}</td>
                      <td className="mono">{fmtMoney(campaign.budget, campaign.currency)}</td>
                      <td>
                        {campaign.latestVerdict
                          ? <StatusPill tone={tone} label={verdictLabel(campaign.latestVerdict).toLowerCase()} />
                          : '—'}
                      </td>
                      <td className="mono" style={{ fontWeight: 700, color: tone ? `var(--${tone === 'ok' ? 'ok' : tone === 'mid' ? 'warn' : 'bad'})` : undefined }}>
                        {fmtCoef(campaign.status === 'FINISHED' ? ratioValue(campaign.actualRoi) : campaign.latestPaybackRatio)}
                      </td>
                      <td>
                        {campaign.status === 'PLANNED'
                          ? <StatusPill tone="plan" label="планируется" />
                          : <StatusPill tone={tone} label={paybackLabel(tone)} />}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            )}
      </div>
    </Layout>
  )
}

function DeviationPill({ deviation, forecastRoi, comparable }: {
  deviation: number | null
  forecastRoi: number
  comparable: boolean
}) {
  if (!comparable || deviation === null) {
    return <span className="pill plan" title="период данных сильно отличается от длительности прогноза">—</span>
  }
  // Отклонение в ROI-пунктах переводим в единицы коэффициента колонок.
  const diff = deviation / 100
  const abs = Math.abs(diff)
  // цвет — по относительной ошибке: 0,43 на коэффициенте 3,67 и на 0,76 — совершенно разная точность
  const relative = Math.abs(deviation) / Math.max(100 + forecastRoi, 1) * 100
  const tone = relative <= 5 ? 'ok' : relative <= 15 ? 'mid' : 'bad'
  const sign = diff > 0 ? '+' : '−'
  return (
    <span
      className={`pill ${tone}`}
      title={`${sign}${fmtDecimal(abs)} на каждый вложенный · ${fmtDecimal(relative)} % относительно прогноза`}
    >
      {sign}{fmtDecimal(abs)}
    </span>
  )
}
