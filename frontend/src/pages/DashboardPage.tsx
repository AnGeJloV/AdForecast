import { useEffect, useMemo, useState } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { CampaignSummary, CashflowPoint, DashboardData, PlanFactRow } from '../api/types'
import { Layout } from '../components/Layout'
import { Donut, WeeklyBars } from '../components/Charts'
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

const PAGE_SIZE = 8
const PLAN_FACT_PAGE_SIZE = 8
type StatusFilter = 'all' | 'ACTIVE' | 'FINISHED' | 'PLANNED'

function campaignState(campaign: CampaignSummary): string {
  if (campaign.status === 'PLANNED') {
    return 'планируется'
  }
  if (campaign.latestVerdict) {
    return verdictLabel(campaign.latestVerdict).toLowerCase()
  }
  return paybackLabel(paybackTone(ratioValue(campaign.actualRoi)))
}

const CAMPAIGN_COLUMNS: SortColumn<CampaignSummary>[] = [
  { id: 'name', title: 'Кампания', value: (row) => row.campaignName },
  { id: 'platform', title: 'Платформа', value: (row) => row.platform },
  { id: 'budget', title: 'Бюджет', value: (row) => row.budget },
  { id: 'verdict', title: 'Прогноз', value: (row) => (row.latestVerdict === null ? null : verdictLabel(row.latestVerdict)) },
  { id: 'coef', title: 'Коэффициент', value: (row) => row.latestPaybackRatio },
  { id: 'state', title: 'Статус', value: campaignState },
]

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
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<StatusFilter>('all')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)

  useEffect(() => {
    Promise.all([api.dashboard(), api.campaigns(), api.cashflow(12)])
      .then(([dashboard, campaignList, flow]) => {
        setData(dashboard)
        setCampaigns(campaignList)
        setCashflow(flow)
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить дашборд'))
  }, [])

  const currency = campaigns[0]?.currency ?? 'BYN'

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

  const rows = useMemo(() => {
    const query = search.trim().toLowerCase()
    return campaigns.filter((campaign) => {
      if (filter !== 'all' && campaign.status !== filter) {
        return false
      }
      if (query && !campaign.campaignName.toLowerCase().includes(query)) {
        return false
      }
      return true
    })
  }, [campaigns, filter, search])

  const campaignTable = useSortTable(rows, CAMPAIGN_COLUMNS)
  const planFactTable = useSortTable(data?.planVsFact ?? [], PLAN_FACT_COLUMNS)
  const [factPage, setFactPage] = useState(1)

  const pageCount = Math.max(1, Math.ceil(campaignTable.rows.length / PAGE_SIZE))
  const currentPage = Math.min(page, pageCount)
  const pageRows = campaignTable.rows.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE)

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
          <div className="card stat">
            <div className="label">Активные кампании</div>
            <div className="num">{fmtNum(data.campaignsActive)}</div>
            <div className="delta muted">планируемых: {fmtNum(data.campaignsPlanned)} · завершённых: {fmtNum(data.campaignsFinished)}</div>
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
                { label: 'окупаемые', value: structure.ok, color: '#34D399' },
                { label: 'на границе', value: structure.mid, color: '#FBBF24' },
                { label: 'убыточные', value: structure.bad, color: '#F87171' },
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
              <div className="csub">по неделям по всем вашим кампаниям, {currency}</div>
            </div>
          </div>
          <WeeklyBars points={cashflow} />
          <div className="chart-foot">
            <div className="foot-pill"><span className="lbl">Вложено</span><b>{fmtNum(flowTotals.cost)}</b></div>
            <div className="foot-pill"><span className="lbl">Вернулось</span><b>{fmtNum(flowTotals.revenue)}</b></div>
            <div className="foot-pill accent"><span className="lbl">Прибыль</span><b>{fmtNum(flowTotals.profit)}</b></div>
          </div>
        </div>

        <div className="card">
          <div className="card-head">
            <div>
              <h2>План против факта</h2>
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
                      <td><DeviationPill deviation={row.deviation} comparable={row.comparable} /></td>
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
          {data.planVsFactAvgDeviation !== null && (
            <div className="fact-note">
              Средняя ошибка прогноза по {comparableCount} {comparableCount === 1 ? 'сопоставимой кампании' : 'сопоставимым кампаниям'} — <b>{fmtDecimal(data.planVsFactAvgDeviation)} п.п.</b>
              {' '}Прогнозам можно доверять при планировании бюджета, но по коротким кампаниям ошибка выше.
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
            <h2>Кампании</h2>
            <div className="csub">нажмите на строку, чтобы открыть кампанию</div>
          </div>
          <div className="table-tools">
            <div className="fchips">
              {([['all', 'Все'], ['ACTIVE', 'Активные'], ['FINISHED', 'Завершённые'], ['PLANNED', 'Планируемые']] as [StatusFilter, string][]).map(([value, label]) => (
                <button
                  key={value}
                  className={`fchip${filter === value ? ' active' : ''}`}
                  onClick={() => { setFilter(value); setPage(1) }}
                >
                  {label}
                </button>
              ))}
            </div>
            <input
              className="search"
              placeholder="Поиск…"
              value={search}
              onChange={(event) => { setSearch(event.target.value); setPage(1) }}
            />
          </div>
        </div>
        {pageRows.length === 0
          ? <EmptyState>кампании не найдены</EmptyState>
          : (
            <table>
              <thead>
                <tr>
                  {CAMPAIGN_COLUMNS.map((column) => <SortTh key={column.id} table={campaignTable} column={column} />)}
                </tr>
              </thead>
              <tbody>
                {pageRows.map((campaign) => {
                  const tone = campaign.latestVerdict
                    ? verdictTone(campaign.latestVerdict)
                    : campaign.status === 'PLANNED'
                      ? null
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
                        {fmtCoef(campaign.latestPaybackRatio)}
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
        <div className="table-foot">
          <span className="rows-range">{rangeLabel(currentPage, PAGE_SIZE, pageRows.length, rows.length, ['кампания', 'кампании', 'кампаний'])}</span>
          <Pager page={currentPage} pageCount={pageCount} onChange={setPage} />
        </div>
      </div>
    </Layout>
  )
}

function DeviationPill({ deviation, comparable }: { deviation: number | null; comparable: boolean }) {
  if (!comparable || deviation === null) {
    return <span className="pill plan" title="период данных сильно отличается от длительности прогноза">—</span>
  }
  const abs = Math.abs(deviation)
  const tone = abs <= 5 ? 'ok' : abs <= 10 ? 'mid' : 'bad'
  const sign = deviation > 0 ? '+' : '−'
  return <span className={`pill ${tone}`}>{sign}{fmtDecimal(abs)} п.п.</span>
}
