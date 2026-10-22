import { useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { AdAccount, CampaignSummary } from '../api/types'
import { Layout } from '../components/Layout'
import { Modal } from '../components/Modal'
import { SortTh, useSortTable } from '../components/SortableTable'
import type { SortColumn } from '../components/SortableTable'
import { Select } from '../components/Select'
import { EmptyState, ErrorBox, PageLoader, Pager, StatusPill } from '../components/Ui'
import { fmtMoney, fmtNum, fmtRatio, paybackLabel, paybackTone, plural, rangeLabel, ratioValue } from '../utils/format'

const PAGE_SIZE = 10
type StatusFilter = 'all' | 'ACTIVE' | 'FINISHED' | 'PLANNED'

/** Подпись состояния так же, как она выглядит в таблице — по ней же и фильтруем. */
function stateLabel(campaign: CampaignSummary): string {
  if (campaign.status === 'PLANNED') {
    return 'планируется'
  }
  return paybackLabel(paybackTone(ratioValue(campaign.latestForecastRoi ?? campaign.actualRoi)))
}

const CAMPAIGN_COLUMNS: SortColumn<CampaignSummary>[] = [
  { id: 'name', title: 'Кампания', value: (row) => row.campaignName },
  { id: 'platform', title: 'Платформа', value: (row) => row.platform },
  { id: 'days', title: 'Данные', value: (row) => row.daysCount },
  { id: 'spent', title: 'Израсходовано', value: (row) => row.totalCost },
  { id: 'roi', title: 'ROI', value: (row) => row.latestForecastRoi ?? row.actualRoi },
  { id: 'state', title: 'Статус', value: stateLabel },
]

export function CampaignsPage() {
  const navigate = useNavigate()
  const [campaigns, setCampaigns] = useState<CampaignSummary[] | null>(null)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<StatusFilter>('all')
  const [platform, setPlatform] = useState('all')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [createOpen, setCreateOpen] = useState(false)

  const load = () => {
    api.campaigns()
      .then(setCampaigns)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить кампании'))
  }

  useEffect(load, [])

  const platforms = useMemo(
    () => Array.from(new Set((campaigns ?? []).map((campaign) => campaign.platform))).sort(),
    [campaigns],
  )

  const counts = useMemo(() => {
    const list = campaigns ?? []
    return {
      all: list.length,
      ACTIVE: list.filter((campaign) => campaign.status === 'ACTIVE').length,
      FINISHED: list.filter((campaign) => campaign.status === 'FINISHED').length,
      PLANNED: list.filter((campaign) => campaign.status === 'PLANNED').length,
    }
  }, [campaigns])

  const table = useSortTable(campaigns ?? [], CAMPAIGN_COLUMNS)

  const rows = useMemo(() => {
    const query = search.trim().toLowerCase()
    return table.rows.filter((campaign) => {
      if (filter !== 'all' && campaign.status !== filter) {
        return false
      }
      if (platform !== 'all' && campaign.platform !== platform) {
        return false
      }
      if (query && !campaign.campaignName.toLowerCase().includes(query)) {
        return false
      }
      return true
    })
  }, [table.rows, filter, platform, search])

  if (!campaigns) {
    return <Layout>{error ? <ErrorBox message={error} /> : <PageLoader />}</Layout>
  }

  const pageCount = Math.max(1, Math.ceil(rows.length / PAGE_SIZE))
  const currentPage = Math.min(page, pageCount)
  const pageRows = rows.slice((currentPage - 1) * PAGE_SIZE, currentPage * PAGE_SIZE)

  return (
    <Layout>
      <div className="page-head">
        <div>
          <h1>Кампании</h1>
          <div className="sub">Все рекламные кампании, их бюджеты и прогнозы окупаемости</div>
        </div>
        <button className="cta" onClick={() => setCreateOpen(true)}>＋ Новая кампания</button>
      </div>

      <div className="toolbar">
        <div className="fchips">
          {([['all', 'Все'], ['ACTIVE', 'Активные'], ['FINISHED', 'Завершённые'], ['PLANNED', 'Планируемые']] as [StatusFilter, string][]).map(([value, label]) => (
            <button
              key={value}
              className={`fchip${filter === value ? ' active' : ''}`}
              onClick={() => { setFilter(value); setPage(1) }}
            >
              {label} · {counts[value]}
            </button>
          ))}
        </div>
        <div className="spacer" />
        <Select
          variant="pill"
          value={platform}
          options={[{ value: 'all', label: 'Все платформы' }, ...platforms.map((name) => ({ value: name, label: name }))]}
          onChange={(next) => { setPlatform(next); setPage(1) }}
        />
        <input
          className="search"
          placeholder="Поиск по названию…"
          value={search}
          onChange={(event) => { setSearch(event.target.value); setPage(1) }}
        />
      </div>

      <div className="card" style={{ padding: '10px 24px 18px' }}>
        {pageRows.length === 0
          ? <EmptyState>кампании не найдены — измените фильтры или создайте новую</EmptyState>
          : (
            <table>
              <thead>
                <tr>
                  {CAMPAIGN_COLUMNS.map((column) => <SortTh key={column.id} table={table} column={column} />)}
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {pageRows.map((campaign) => {
                  const spent = campaign.budget > 0 ? Math.min((campaign.totalCost / campaign.budget) * 100, 100) : 0
                  const roi = campaign.latestForecastRoi ?? campaign.actualRoi
                  const tone = campaign.status === 'PLANNED' ? null : paybackTone(ratioValue(roi))
                  return (
                    <tr key={campaign.id} className="clickable" onClick={() => navigate(`/campaigns/${campaign.id}`)}>
                      <td>
                        <div className="cname">
                          {campaign.campaignName}
                          <div className="meta">бюджет {fmtMoney(campaign.budget, campaign.currency)}</div>
                        </div>
                      </td>
                      <td>{campaign.platform}</td>
                      <td className="mono">
                        {campaign.daysCount > 0
                          ? `${fmtNum(campaign.daysCount)} ${plural(Number(campaign.daysCount), ['день', 'дня', 'дней'])} · ${fmtNum(campaign.uploadCount)} выгр.`
                          : 'нет данных'}
                      </td>
                      <td>
                        <div className="spent">
                          <div className="t">{fmtMoney(campaign.totalCost, campaign.currency)} · {Math.round(spent)} %</div>
                          <div className="track"><div className="fill" style={{ width: `${spent}%` }} /></div>
                        </div>
                      </td>
                      <td className="mono" style={{ fontWeight: 700, color: tone ? `var(--${tone === 'ok' ? 'ok' : tone === 'mid' ? 'warn' : 'bad'})` : undefined }}>
                        {fmtRatio(roi)}
                      </td>
                      <td>
                        <StatusPill tone={campaign.status === 'PLANNED' ? 'plan' : tone} label={stateLabel(campaign)} />
                      </td>
                      <td className="arrow">→</td>
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

      <CreateCampaignModal
        open={createOpen}
        onClose={() => setCreateOpen(false)}
        onCreated={(id) => {
          setCreateOpen(false)
          navigate(`/campaigns/${id}`)
        }}
      />
    </Layout>
  )
}

function CreateCampaignModal({ open, onClose, onCreated }: {
  open: boolean
  onClose: () => void
  onCreated: (id: number) => void
}) {
  const [accounts, setAccounts] = useState<AdAccount[]>([])
  const [accountId, setAccountId] = useState<number | null>(null)
  const [name, setName] = useState('')
  const [budget, setBudget] = useState('')
  const [currency, setCurrency] = useState('BYN')
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) {
      return
    }
    setName('')
    setBudget('')
    setCurrency('BYN')
    setError('')
    api.adAccounts()
      .then((list) => {
        setAccounts(list)
        setAccountId(list[0]?.id ?? null)
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить кабинеты'))
  }, [open])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) {
      return
    }
    setError('')
    const budgetValue = Number(budget.replace(/\s/g, '').replace(',', '.'))
    if (!accountId) {
      setError('выберите рекламный кабинет')
      return
    }
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
      const created = await api.createCampaign({
        accountId,
        campaignName: name.trim(),
        budget: budgetValue,
        currency: currency.trim() || 'BYN',
      })
      onCreated(created.id)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось создать кампанию')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal open={open} onClose={onClose} title="Новая кампания" subtitle="кампания создаётся в статусе «планируется»">
      <form className="form" onSubmit={submit}>
        {error && <div className="form-error">{error}</div>}
        <div className="field">
          <label>Рекламный кабинет</label>
          <Select
            value={accountId === null ? '' : String(accountId)}
            options={accounts.map((account) => ({
              value: String(account.id),
              label: `${account.accountName} · ${account.platform}`,
            }))}
            onChange={(next) => setAccountId(Number(next))}
            placeholder="выберите кабинет"
          />
        </div>
        <div className="field">
          <label>Название</label>
          <input placeholder="Чёрная пятница 2026" value={name} onChange={(event) => setName(event.target.value)} />
        </div>
        <div className="form-row">
          <div className="field">
            <label>Бюджет</label>
            <input inputMode="decimal" placeholder="30 000" value={budget} onChange={(event) => setBudget(event.target.value)} />
          </div>
          <div className="field" style={{ flex: '0 0 110px' }}>
            <label>Валюта</label>
            <input maxLength={10} value={currency} onChange={(event) => setCurrency(event.target.value)} />
          </div>
        </div>
        <div className="foot">
          <button type="button" className="ghost-btn" onClick={onClose}>Отмена</button>
          <button type="submit" className="cta" disabled={busy}>{busy ? 'Создаём…' : 'Создать'}</button>
        </div>
      </form>
    </Modal>
  )
}
