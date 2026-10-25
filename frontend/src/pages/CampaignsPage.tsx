import { useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { useNavigate } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { AdAccount, CampaignSummary, PlatformInfo } from '../api/types'
import { Layout } from '../components/Layout'
import { Modal } from '../components/Modal'
import { SortTh, useSortTable } from '../components/SortableTable'
import type { SortColumn } from '../components/SortableTable'
import { Select } from '../components/Select'
import { EmptyState, ErrorBox, PageLoader, Pager, StatusPill } from '../components/Ui'
import { fmtCoef, fmtMoney, fmtNum, paybackLabel, paybackTone, plural, rangeLabel, ratioValue, verdictLabel, verdictTone } from '../utils/format'

const PAGE_SIZE = 10
type StatusFilter = 'all' | 'ACTIVE' | 'FINISHED' | 'PLANNED'

function displayRatio(campaign: CampaignSummary): number | null {
  // для завершённой кампании показываем фактическую окупаемость (как в карточке)
  if (campaign.status === 'FINISHED') {
    return ratioValue(campaign.actualRoi)
  }
  if (campaign.latestPaybackRatio !== null) {
    return campaign.latestPaybackRatio
  }
  return ratioValue(campaign.actualRoi)
}

function stateLabel(campaign: CampaignSummary): string {
  if (campaign.status === 'PLANNED') {
    return 'планируется'
  }
  // завершённая — фактический итог (как в карточке кампании)
  if (campaign.status === 'FINISHED') {
    return paybackLabel(paybackTone(ratioValue(campaign.actualRoi)))
  }
  if (campaign.latestVerdict) {
    return verdictLabel(campaign.latestVerdict).toLowerCase()
  }
  return paybackLabel(paybackTone(ratioValue(campaign.actualRoi)))
}

const CAMPAIGN_COLUMNS: SortColumn<CampaignSummary>[] = [
  { id: 'name', title: 'Кампания', value: (row) => row.campaignName },
  { id: 'platform', title: 'Платформа', value: (row) => row.platform },
  { id: 'days', title: 'Данные', value: (row) => row.daysCount },
  { id: 'spent', title: 'Израсходовано', value: (row) => row.totalCost },
  { id: 'coef', title: 'Коэффициент', value: (row) => displayRatio(row) },
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
                  const coef = displayRatio(campaign)
                  const tone = campaign.status === 'PLANNED'
                    ? null
                    : campaign.status === 'FINISHED'
                      ? paybackTone(ratioValue(campaign.actualRoi))
                      : campaign.latestVerdict
                        ? verdictTone(campaign.latestVerdict)
                        : paybackTone(ratioValue(campaign.actualRoi))
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
                          ? `${fmtNum(campaign.daysCount)} ${plural(Number(campaign.daysCount), ['день', 'дня', 'дней'])} · ${fmtNum(campaign.uploadCount)} ${plural(campaign.uploadCount, ['выгрузка', 'выгрузки', 'выгрузок'])}`
                          : 'нет данных'}
                      </td>
                      <td>
                        <div className="spent">
                          <div className="t">{fmtMoney(campaign.totalCost, campaign.currency)} · {Math.round(spent)} %</div>
                          <div className="track"><div className="fill" style={{ width: `${spent}%` }} /></div>
                        </div>
                      </td>
                      <td className="mono" style={{ fontWeight: 700, color: tone ? `var(--${tone === 'ok' ? 'ok' : tone === 'mid' ? 'warn' : 'bad'})` : undefined }}>
                        {fmtCoef(coef)}
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
  const [addOpen, setAddOpen] = useState(false)
  const [platforms, setPlatforms] = useState<PlatformInfo[]>([])
  const [newPlatform, setNewPlatform] = useState('')
  const [customPlatform, setCustomPlatform] = useState('')
  const [newAccountName, setNewAccountName] = useState('')
  const [addError, setAddError] = useState('')
  const [addBusy, setAddBusy] = useState(false)

  useEffect(() => {
    if (!open) {
      return
    }
    setName('')
    setBudget('')
    setCurrency('BYN')
    setError('')
    setAddOpen(false)
    setNewAccountName('')
    setAddError('')
    api.adAccounts()
      .then((list) => {
        setAccounts(list)
        setAccountId(list[0]?.id ?? null)
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить кабинеты'))
    api.adPlatforms()
      .then((list) => {
        setPlatforms(list)
        setNewPlatform(list[0]?.name ?? '')
      })
      .catch(() => setPlatforms([]))
  }, [open])

  const addAccount = async () => {
    if (addBusy) {
      return
    }
    setAddError('')
    if (!newAccountName.trim()) {
      setAddError('укажите название кабинета')
      return
    }
    setAddBusy(true)
    try {
      const platform = newPlatform === '__custom' ? customPlatform.trim() : newPlatform
      if (!platform) {
        setAddError('укажите название площадки')
        return
      }
      const created = await api.addAdAccount({ accountName: newAccountName.trim(), platform })
      const list = await api.adAccounts()
      setAccounts(list)
      setAccountId(created.id)
      setNewAccountName('')
      setAddOpen(false)
    } catch (cause) {
      setAddError(cause instanceof ApiError ? cause.message : 'не удалось добавить кабинет')
    } finally {
      setAddBusy(false)
    }
  }

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
              label: account.accountName,
            }))}
            onChange={(next) => setAccountId(Number(next))}
            placeholder="выберите кабинет"
          />
          <button type="button" className="link-btn" onClick={() => setAddOpen(!addOpen)}>
            {addOpen ? '− Свернуть' : '＋ Добавить свой кабинет'}
          </button>
          {addOpen && (
            <div className="inline-form">
              <div className="form-row">
                <div className="field">
                  <label>Название кабинета</label>
                  <input
                    placeholder="Мой аккаунт Google"
                    value={newAccountName}
                    onChange={(event) => setNewAccountName(event.target.value)}
                  />
                </div>
                <div className="field">
                  <label>Площадка</label>
                  <Select
                    value={newPlatform}
                    options={[
                      ...platforms.map((platform) => ({ value: platform.name, label: platform.name })),
                      { value: '__custom', label: 'Другая площадка…' },
                    ]}
                    onChange={setNewPlatform}
                  />
                </div>
              </div>
              {newPlatform === '__custom' && (
                <div className="field">
                  <label>Название новой площадки</label>
                  <input
                    placeholder="Например, Avito или Яндекс Маркет"
                    value={customPlatform}
                    onChange={(event) => setCustomPlatform(event.target.value)}
                  />
                </div>
              )}
              {newPlatform !== '__custom' && platforms.find((p) => p.name === newPlatform) && (
                <div className="csub">
                  Минимальный бюджет: {platforms.find((p) => p.name === newPlatform)?.minDayBudget} BYN в день.
                  {' '}{platforms.find((p) => p.name === newPlatform)?.note}
                </div>
              )}
              {addError && <div className="form-error">{addError}</div>}
              <div className="csub">
                {newPlatform === '__custom'
                  ? 'Прогноз по новой площадке появится, когда по ней накопятся ваши завершённые кампании: общая модель её ещё не знает, и система честно скажет об этом вместо выдуманных цифр.'
                  : 'Бюджеты и минимальные ставки площадок — из открытых источников (click.ru, ECOM Digital, справки площадок), октябрь 2026.'}
              </div>
              <div className="foot">
                <button type="button" className="cta" onClick={addAccount} disabled={addBusy}>
                  {addBusy ? 'Добавляем…' : 'Добавить кабинет'}
                </button>
              </div>
            </div>
          )}
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
          <div className="field" style={{ flex: '0 0 130px' }}>
            <label>Валюта</label>
            <Select
              value={currency}
              options={['BYN', 'USD', 'EUR', 'RUB'].map((code) => ({ value: code, label: code }))}
              onChange={setCurrency}
            />
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
