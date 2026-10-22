import { useCallback, useEffect, useMemo, useState } from 'react'
import type { FormEvent } from 'react'
import { api, ApiError, downloadFile } from '../api/client'
import { useAuth } from '../auth/AuthContext'
import type {
  AdminCampaignInsight,
  AdminModel,
  AdminSummary,
  AuditEntry,
  BackupEntry,
  DailyMetricPoint,
  Page,
  RoleItem,
  TrainingPreview,
  TrainingPreviewRow,
  TrainingTask,
  UserItem,
} from '../api/types'
import { Layout } from '../components/Layout'
import { Modal } from '../components/Modal'
import { Donut } from '../components/Charts'
import { SortTh, useSortTable } from '../components/SortableTable'
import type { SortColumn, SortTable } from '../components/SortableTable'
import { Select } from '../components/Select'
import { EmptyState, ErrorBox, PageLoader, Pager, StatusPill } from '../components/Ui'
import { fmtDate, fmtDateTime, fmtDays, fmtDecimal, fmtMoney, fmtNum, fmtRatio, initials, plural, rangeLabel } from '../utils/format'

type Tab = 'summary' | 'users' | 'audit' | 'models' | 'backup'

const TABS: { id: Tab; label: string; title: string; sub: string }[] = [
  {
    id: 'summary',
    label: 'Сводка',
    title: 'Сводка по системе',
    sub: 'состав программного средства, объёмы данных и состояние модели',
  },
  {
    id: 'users',
    label: 'Пользователи',
    title: 'Пользователи системы',
    sub: 'учётные записи, роли и доступ к разделам',
  },
  {
    id: 'audit',
    label: 'Журнал действий',
    title: 'Журнал действий пользователей',
    sub: 'кто, что и когда делал: вход, загрузки, прогнозы, обучение, копии',
  },
  {
    id: 'models',
    label: 'Модели и обучение',
    title: 'Модели прогнозирования',
    sub: 'версии модели, обучение по выборке и активная версия',
  },
  {
    id: 'backup',
    label: 'Резервные копии',
    title: 'Резервные копии базы',
    sub: 'создание копий с хоста и выгрузка архивов',
  },
]

export function AdminPage() {
  const [tab, setTab] = useState<Tab>('summary')
  const [insightId, setInsightId] = useState<number | null>(null)
  const current = TABS.find((item) => item.id === tab) ?? TABS[0]

  return (
    <Layout>
      <div className="page-head">
        <div>
          <h1>{current.title}</h1>
          <div className="sub">{current.sub}</div>
        </div>
        <span className="meta">
          обновлено {new Date().toLocaleDateString('ru-RU', { day: 'numeric', month: 'long' })} в{' '}
          {new Date().toLocaleTimeString('ru-RU', { hour: '2-digit', minute: '2-digit' })}
        </span>
      </div>

      <div className="tabs">
        {TABS.map((item) => (
          <button key={item.id} className={tab === item.id ? 'active' : ''} onClick={() => setTab(item.id)}>
            {item.label}
          </button>
        ))}
      </div>

      {tab === 'summary' && <SummaryTab goAudit={() => setTab('audit')} onOpenCampaign={setInsightId} />}
      {tab === 'users' && <UsersTab />}
      {tab === 'audit' && <AuditTab onOpenCampaign={setInsightId} />}
      {tab === 'models' && <ModelsTab onOpenCampaign={setInsightId} />}
      {tab === 'backup' && <BackupsTab />}

      <CampaignInsightModal campaignId={insightId} onClose={() => setInsightId(null)} />
    </Layout>
  )
}

/* ===================== СВОДКА ===================== */

function auditCategory(action: string): string {
  if (action.includes('загрузка')) return 'данные'
  if (action.includes('пользователя') || action.includes('регистрация')) return 'пользователи'
  if (action.includes('обучения') || action.includes('обучение') || action.includes('модели')) return 'модели'
  if (action.includes('резервной')) return 'бэкапы'
  return 'прочее'
}

const CATEGORY_LABELS: Record<string, string> = {
  'данные': 'загрузки данных',
  'пользователи': 'пользователи',
  'модели': 'модели и обучение',
  'бэкапы': 'резервные копии',
  'прочее': 'прочее',
}

const CATEGORY_COLORS: Record<string, string> = {
  'данные': '#F59E0B',
  'пользователи': '#34D399',
  'модели': '#60A5FA',
  'бэкапы': '#A78BFA',
  'прочее': '#6B7280',
}

function SummaryTab({ goAudit, onOpenCampaign }: { goAudit: () => void; onOpenCampaign: (id: number) => void }) {
  const [summary, setSummary] = useState<AdminSummary | null>(null)
  const [entries, setEntries] = useState<AuditEntry[]>([])
  const [error, setError] = useState('')

  useEffect(() => {
    Promise.all([api.adminSummary(), api.audit(0, 100)])
      .then(([data, audit]) => {
        setSummary(data)
        setEntries(audit.content)
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить сводку'))
  }, [])

  const summaryTable = useSortTable(entries, AUDIT_COLUMNS)

  const activity = useMemo(() => {
    const counts: Record<string, number> = {}
    for (const entry of entries) {
      const category = auditCategory(entry.action)
      counts[category] = (counts[category] ?? 0) + 1
    }
    return Object.entries(counts)
      .sort((first, second) => second[1] - first[1])
      .map(([category, value]) => ({
        label: CATEGORY_LABELS[category] ?? category,
        value,
        color: CATEGORY_COLORS[category] ?? '#6B7280',
      }))
  }, [entries])

  if (!summary) {
    return error ? <ErrorBox message={error} /> : <PageLoader />
  }

  const campaignsTotal = summary.campaignsPlanned + summary.campaignsActive + summary.campaignsFinished

  return (
    <>
      <div className="grid kpis">
        <div className="card kpi">
          <div className="label">Пользователи</div>
          <div className="num">{fmtNum(summary.usersTotal)}</div>
          <div className="delta">{fmtNum(summary.usersActive)} активных · {fmtNum(summary.usersTotal - summary.usersActive)} заблокировано</div>
        </div>
        <div className="card kpi">
          <div className="label">Кампании в системе</div>
          <div className="num">{fmtNum(campaignsTotal)}</div>
          <div className="delta muted">
            {fmtNum(summary.campaignsActive)} активных · {fmtNum(summary.campaignsPlanned)} планируемых · {fmtNum(summary.campaignsFinished)} завершённых
          </div>
        </div>
        <div className="card kpi">
          <div className="label">Прогнозы</div>
          <div className="num">{fmtNum(summary.forecastsTotal)}</div>
          <div className="delta warn">
            {fmtNum(summary.forecastsCompleted)} успешных · {fmtNum(summary.forecastsTotal - summary.forecastsCompleted)} с ошибкой
          </div>
        </div>
        <div className="card kpi">
          <div className="label">Данные и отчёты</div>
          <div className="num">{fmtNum(summary.uploadsTotal)}</div>
          <div className="delta muted">выгрузок принято · отчётов выгружено: {fmtNum(summary.exportsTotal)}</div>
        </div>
      </div>

      <div className="grid row-2">
        <div className="card">
          <div className="card-head">
            <div>
              <h2>Активность пользователей</h2>
              <div className="csub">распределение последних {entries.length} действий по типам</div>
            </div>
          </div>
          {entries.length === 0
            ? <EmptyState>действий пока нет</EmptyState>
            : (
              <Donut
                size={170}
                centerValue={String(entries.length)}
                centerLabel="действий"
                segments={activity}
              />
              )}
        </div>
        <div className="card">
          <div className="card-head">
            <div>
              <h2>Активная модель</h2>
              <div className="csub">используется для всех новых прогнозов</div>
            </div>
            {summary.activeModelVersion && <span className="pill cur">{summary.activeModelVersion}</span>}
          </div>
          <div className="kv"><span className="k">Алгоритм</span><span className="v">CatBoost, ансамбль из 10</span></div>
          <div className="kv">
            <span className="k">MAPE</span>
            <span className="v">
              {summary.activeModelMape === null ? '—' : `${fmtDecimal(summary.activeModelMape)} %`}{' '}
              <span style={{ color: 'var(--ok)', fontSize: 12 }}>(норма ≤ 15 %)</span>
            </span>
          </div>
          <div className="kv"><span className="k">Дата обучения</span><span className="v">{fmtDateTime(summary.activeModelTrainedAt)}</span></div>
          <div className="kv"><span className="k">Версий в реестре</span><span className="v">{fmtNum(summary.modelVersions)}</span></div>
          <div className="kv">
            <span className="k">ML-сервис</span>
            <span className="v" style={{ color: summary.mlServiceAvailable ? 'var(--ok)' : 'var(--bad)' }}>
              {summary.mlServiceAvailable ? 'доступен' : 'недоступен'}
            </span>
          </div>
        </div>
      </div>

      <div className="card" style={{ marginTop: 18 }}>
        <div className="card-head">
          <div>
            <h2>Последние действия</h2>
            <div className="csub">неизменяемый журнал аудита</div>
          </div>
          <button className="link" onClick={goAudit}>Весь журнал →</button>
        </div>
        <AuditTable entries={summaryTable.rows.slice(0, 6)} table={summaryTable} onOpenCampaign={onOpenCampaign} />
      </div>
    </>
  )
}

/* ===================== ПОЛЬЗОВАТЕЛИ ===================== */

type UserFilter = 'all' | 'маркетолог' | 'администратор' | 'blocked'

const USER_COLUMNS: SortColumn<UserItem>[] = [
  { id: 'name', title: 'Пользователь', value: (row) => row.fullName },
  { id: 'email', title: 'E-mail', value: (row) => row.email },
  { id: 'role', title: 'Роль', value: (row) => row.role },
  { id: 'lastLogin', title: 'Последний вход', value: (row) => row.lastLogin },
  { id: 'status', title: 'Статус', value: (row) => (row.isActive ? 'активен' : 'заблокирована') },
]

function UsersTab() {
  const { user: currentUser } = useAuth()
  const [users, setUsers] = useState<UserItem[] | null>(null)
  const [roles, setRoles] = useState<RoleItem[]>([])
  const [error, setError] = useState('')
  const [filter, setFilter] = useState<UserFilter>('all')
  const [search, setSearch] = useState('')
  const [createOpen, setCreateOpen] = useState(false)
  const [editing, setEditing] = useState<UserItem | null>(null)
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = useCallback(() => {
    Promise.all([api.users(), api.roles()])
      .then(([userList, roleList]) => {
        setUsers(userList)
        setRoles(roleList)
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить пользователей'))
  }, [])

  useEffect(load, [load])

  const toggleActive = async (user: UserItem) => {
    if (busyId) {
      return
    }
    const role = roles.find((item) => item.name === user.role)
    if (!role) {
      setError('не удалось определить роль пользователя')
      return
    }
    setBusyId(user.id)
    setError('')
    try {
      await api.updateUser(user.id, {
        email: user.email,
        fullName: user.fullName,
        roleId: role.id,
        isActive: !user.isActive,
      })
      load()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось изменить пользователя')
    } finally {
      setBusyId(null)
    }
  }

  const rows = useMemo(() => {
    const query = search.trim().toLowerCase()
    return (users ?? []).filter((user) => {
      if (filter === 'blocked' && user.isActive) return false
      if ((filter === 'маркетолог' || filter === 'администратор') && user.role !== filter) return false
      if (query && !`${user.fullName} ${user.login}`.toLowerCase().includes(query)) return false
      return true
    })
  }, [users, filter, search])

  const table = useSortTable(rows, USER_COLUMNS)

  if (!users) {
    return error ? <ErrorBox message={error} /> : <PageLoader />
  }

  const counts: Record<UserFilter, number> = {
    all: users.length,
    'маркетолог': users.filter((user) => user.role === 'маркетолог').length,
    'администратор': users.filter((user) => user.role === 'администратор').length,
    blocked: users.filter((user) => !user.isActive).length,
  }

  return (
    <>
      <div className="toolbar">
        <div className="fchips">
          {([['all', 'Все'], ['маркетолог', 'Маркетологи'], ['администратор', 'Администраторы'], ['blocked', 'Заблокированные']] as [UserFilter, string][]).map(([value, label]) => (
            <button key={value} className={`fchip${filter === value ? ' active' : ''}`} onClick={() => setFilter(value)}>
              {label} · {counts[value]}
            </button>
          ))}
        </div>
        <div className="spacer" />
        <input className="search" placeholder="Поиск по имени или логину…" value={search} onChange={(event) => setSearch(event.target.value)} />
        <button className="cta" onClick={() => setCreateOpen(true)}>＋ Пользователь</button>
      </div>
      {error && <ErrorBox message={error} />}
      <div className="card" style={{ padding: '10px 24px 18px', marginTop: error ? 12 : 0 }}>
        {rows.length === 0
          ? <EmptyState>пользователи не найдены</EmptyState>
          : (
            <table>
              <thead>
                <tr>
                  {USER_COLUMNS.map((column) => <SortTh key={column.id} table={table} column={column} />)}
                  <th></th>
                </tr>
              </thead>
              <tbody>
                {table.rows.map((user) => {
                  const isSelf = user.login === currentUser?.login
                  return (
                    <tr key={user.id}>
                      <td>
                        <div className="u">
                          <div className="avatar">{initials(user.fullName)}</div>
                          <div>
                            <div className="n">{user.fullName}</div>
                            <div className="l">{user.login}</div>
                          </div>
                        </div>
                      </td>
                      <td>{user.email}</td>
                      <td><StatusPill tone={user.role === 'администратор' ? 'admin' : 'mark'} label={user.role} /></td>
                      <td className="mono">{fmtDateTime(user.lastLogin)}</td>
                      <td><StatusPill tone={user.isActive ? 'ok' : 'bad'} label={user.isActive ? 'активен' : 'заблокирована'} /></td>
                      <td>
                        {isSelf
                          ? <span className="self-row">это ваша учётная запись</span>
                          : (
                            <div className="row-actions">
                              <button onClick={() => setEditing(user)}>Изменить</button>
                              <button
                                className={user.isActive ? 'danger' : ''}
                                disabled={busyId === user.id}
                                onClick={() => toggleActive(user)}
                              >
                                {user.isActive ? 'Заблокировать' : 'Разблокировать'}
                              </button>
                            </div>
                          )}
                      </td>
                    </tr>
                  )
                })}
              </tbody>
            </table>
            )}
      </div>

      <UserFormModal
        open={createOpen}
        roles={roles}
        onClose={() => setCreateOpen(false)}
        onSaved={() => { setCreateOpen(false); load() }}
      />
      <UserFormModal
        open={editing !== null}
        roles={roles}
        user={editing ?? undefined}
        onClose={() => setEditing(null)}
        onSaved={() => { setEditing(null); load() }}
      />
    </>
  )
}

function UserFormModal({ open, roles, user, onClose, onSaved }: {
  open: boolean
  roles: RoleItem[]
  user?: UserItem
  onClose: () => void
  onSaved: () => void
}) {
  const [fullName, setFullName] = useState('')
  const [login, setLogin] = useState('')
  const [email, setEmail] = useState('')
  const [password, setPassword] = useState('')
  const [roleId, setRoleId] = useState<number | null>(null)
  const [error, setError] = useState('')
  const [busy, setBusy] = useState(false)

  useEffect(() => {
    if (!open) {
      return
    }
    setFullName(user?.fullName ?? '')
    setLogin(user?.login ?? '')
    setEmail(user?.email ?? '')
    setPassword('')
    setError('')
    const role = roles.find((item) => item.name === (user?.role ?? 'маркетолог'))
    setRoleId(role?.id ?? roles[0]?.id ?? null)
  }, [open, user, roles])

  const submit = async (event: FormEvent) => {
    event.preventDefault()
    if (busy) {
      return
    }
    setError('')
    if (fullName.trim().length < 2) {
      setError('укажите имя и фамилию')
      return
    }
    if (!/^\S+@\S+\.\S+$/.test(email.trim())) {
      setError('укажите корректный e-mail')
      return
    }
    if (!roleId) {
      setError('выберите роль')
      return
    }
    setBusy(true)
    try {
      if (user) {
        await api.updateUser(user.id, { email: email.trim(), fullName: fullName.trim(), roleId, isActive: user.isActive })
      } else {
        if (!/^[a-zA-Z0-9._-]{3,50}$/.test(login.trim())) {
          setError('логин — от 3 до 50 символов: латиница, цифры, точка, дефис, подчёркивание')
          setBusy(false)
          return
        }
        if (password.length < 6) {
          setError('пароль — не менее 6 символов')
          setBusy(false)
          return
        }
        await api.createUser({ login: login.trim(), email: email.trim(), fullName: fullName.trim(), password, roleId })
      }
      onSaved()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось сохранить пользователя')
    } finally {
      setBusy(false)
    }
  }

  return (
    <Modal
      open={open}
      onClose={onClose}
      title={user ? 'Изменить пользователя' : 'Новый пользователь'}
      subtitle={user ? user.login : 'пароль выдаётся один раз при создании'}
    >
      <form className="form" onSubmit={submit}>
        {error && <div className="form-error">{error}</div>}
        <div className="field">
          <label>Имя и фамилия</label>
          <input placeholder="Иван Иванов" value={fullName} onChange={(event) => setFullName(event.target.value)} />
        </div>
        {!user && (
          <div className="field">
            <label>Логин</label>
            <input placeholder="iivanov" value={login} onChange={(event) => setLogin(event.target.value)} />
          </div>
        )}
        <div className="field">
          <label>E-mail</label>
          <input placeholder="ivanov@adforecast.by" value={email} onChange={(event) => setEmail(event.target.value)} />
        </div>
        {!user && (
          <div className="field">
            <label>Пароль</label>
            <input type="password" placeholder="не менее 6 символов" value={password} onChange={(event) => setPassword(event.target.value)} />
          </div>
        )}
        <div className="field">
          <label>Роль</label>
          <Select
            value={roleId === null ? '' : String(roleId)}
            options={roles.map((role) => ({ value: String(role.id), label: role.name }))}
            onChange={(next) => setRoleId(Number(next))}
            placeholder="выберите роль"
          />
        </div>
        <div className="foot">
          <button type="button" className="ghost-btn" onClick={onClose}>Отмена</button>
          <button type="submit" className="cta" disabled={busy}>{busy ? 'Сохраняем…' : user ? 'Сохранить' : 'Создать'}</button>
        </div>
      </form>
    </Modal>
  )
}

/* ===================== ЖУРНАЛ ДЕЙСТВИЙ ===================== */

const AUDIT_FILTERS: { id: string; label: string }[] = [
  { id: 'all', label: 'Все' },
  { id: 'users', label: 'Пользователи' },
  { id: 'data', label: 'Данные' },
  { id: 'models', label: 'Модели' },
  { id: 'backups', label: 'Бэкапы' },
]

const TRAINING_PAGE_SIZE = 20

const TRAINING_COLUMNS: SortColumn<TrainingPreviewRow>[] = [
  { id: 'name', title: 'Кампания', value: (row) => row.name },
  { id: 'platform', title: 'Платформа', value: (row) => row.platform },
  { id: 'days', title: 'Дней данных', value: (row) => row.days },
  { id: 'ratio', title: 'ROI (факт)', value: (row) => Number(row.ratio.replace(',', '.')) },
]

const AUDIT_COLUMNS: SortColumn<AuditEntry>[] = [
  { id: 'time', title: 'Время', value: (row) => row.actionTime },
  { id: 'user', title: 'Пользователь', value: (row) => row.fullName ?? row.login },
  { id: 'action', title: 'Действие', value: (row) => row.action },
  { id: 'object', title: 'Объект', value: (row) => row.objectType },
  { id: 'details', title: 'Детали', value: (row) => row.details },
  { id: 'ip', title: 'IP', value: (row) => row.ip },
]

const AUDIT_PAGE_SIZE = 50
const AUDIT_SEARCH_DELAY = 200

function AuditTab({ onOpenCampaign }: { onOpenCampaign: (id: number) => void }) {
  const [page, setPage] = useState<Page<AuditEntry> | null>(null)
  const [pageIndex, setPageIndex] = useState(0)
  const [error, setError] = useState('')
  const [filter, setFilter] = useState('all')
  const [search, setSearch] = useState('')
  const [query, setQuery] = useState('')
  const sortState = useSortTable<AuditEntry>([], AUDIT_COLUMNS)
  const sort = sortState.sort
  const sortApi: SortTable<AuditEntry> = {
    ...sortState,
    toggle: (id) => { sortState.toggle(id); setPageIndex(0) },
  }

  useEffect(() => {
    const timer = setTimeout(() => {
      setQuery(search.trim())
      setPageIndex(0)
    }, AUDIT_SEARCH_DELAY)
    return () => clearTimeout(timer)
  }, [search])

  useEffect(() => {
    api.audit(pageIndex, AUDIT_PAGE_SIZE, filter, query, sort?.id ?? '', sort?.direction ?? '')
      .then(setPage)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить журнал'))
  }, [pageIndex, filter, query, sort?.id, sort?.direction])

  if (!page) {
    return error ? <ErrorBox message={error} /> : <PageLoader />
  }

  const rows = page.content

  return (
    <>
      <div className="toolbar">
        <div className="fchips">
          {AUDIT_FILTERS.map((item) => (
            <button
              key={item.id}
              className={`fchip${filter === item.id ? ' active' : ''}`}
              onClick={() => { setFilter(item.id); setPageIndex(0) }}
            >
              {item.label}
            </button>
          ))}
        </div>
        <div className="spacer" />
        <input
          className="search"
          placeholder="Поиск"
          value={search}
          onChange={(event) => setSearch(event.target.value)}
        />
      </div>
      <div className="card" style={{ padding: '10px 24px 18px' }}>
        {rows.length === 0
          ? <EmptyState>записей нет</EmptyState>
          : <AuditTable entries={rows} table={sortApi} onOpenCampaign={onOpenCampaign} />}
        <div className="table-foot">
          <span>
            Страница {page.page + 1} из {Math.max(page.totalPages, 1)} · всего {fmtNum(page.totalElements)}{' '}
            {plural(Number(page.totalElements), ['запись', 'записи', 'записей'])}
          </span>
          <Pager
            page={pageIndex + 1}
            pageCount={Math.max(page.totalPages, 1)}
            onChange={(number) => setPageIndex(number - 1)}
          />
        </div>
      </div>
    </>
  )
}

/** Название кампании в админских таблицах: открывает просмотр её данных (только чтение). */
function CampaignLink({ id, name, onOpen }: { id: number; name: string; onOpen: (id: number) => void }) {
  return (
    <button type="button" className="camp-link" onClick={() => onOpen(id)} title="Посмотреть данные кампании">
      {name}
      <span className="camp-link-arrow">↗</span>
    </button>
  )
}

function MiniTrend({ metrics }: { metrics: DailyMetricPoint[] }) {
  const points = metrics.slice(-30)
  if (points.length < 2) {
    return null
  }
  const max = Math.max(...points.map((point) => point.revenue), 1)
  const step = 100 / (points.length - 1)
  const line = points.map((point, index) => `${index === 0 ? 'M' : 'L'} ${(index * step).toFixed(2)} ${(38 - (point.revenue / max) * 34).toFixed(2)}`).join(' ')
  const area = `${line} L 100 40 L 0 40 Z`
  return (
    <div className="mini-trend">
      <svg viewBox="0 0 100 40" preserveAspectRatio="none" aria-hidden="true">
        <path d={area} fill="rgba(245, 158, 11, 0.12)" />
        <path d={line} fill="none" stroke="var(--accent)" strokeWidth="0.8" vectorEffect="non-scaling-stroke" />
      </svg>
      <div className="mini-trend-note">
        доход по дням за последние {points.length} {plural(points.length, ['день', 'дня', 'дней'])} · максимум за день {fmtNum(max)}
      </div>
    </div>
  )
}

/** Просмотр кампании из админ-области: только чтение, без перехода на бизнес-экраны. */
function CampaignInsightModal({ campaignId, onClose }: { campaignId: number | null; onClose: () => void }) {
  const [insight, setInsight] = useState<AdminCampaignInsight | null>(null)
  const [error, setError] = useState('')

  useEffect(() => {
    if (campaignId === null) {
      setInsight(null)
      setError('')
      return
    }
    setInsight(null)
    setError('')
    api.adminCampaign(campaignId)
      .then(setInsight)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить кампанию'))
  }, [campaignId])

  if (campaignId === null) {
    return null
  }

  const campaign = insight?.campaign
  const forecasts = (insight?.forecasts ?? []).slice(0, 5)

  return (
    <Modal
      open
      onClose={onClose}
      title={campaign?.campaignName ?? 'Кампания'}
      subtitle="просмотр данных кампании — ведёт кампанию маркетолог, здесь только чтение"
      wide
    >
      {!insight && !error && <PageLoader />}
      {error && <ErrorBox message={error} />}
      {insight && campaign && (
        <>
          <div className="summary">
            <div className="sum-item">
              <div className="k">Платформа и кабинет</div>
              <div className="v" style={{ fontSize: 15 }}>{campaign.platform}</div>
              <div className="csub">{campaign.accountName}</div>
            </div>
            <div className="sum-item">
              <div className="k">Бюджет</div>
              <div className="v" style={{ fontSize: 17 }}>{fmtMoney(campaign.budget, campaign.currency)}</div>
            </div>
            <div className="sum-item">
              <div className="k">Израсходовано / доход</div>
              <div className="v" style={{ fontSize: 17 }}>
                {fmtNum(campaign.totalCost)} / <span style={{ color: 'var(--ok)' }}>{fmtNum(campaign.totalRevenue)}</span>
              </div>
            </div>
            <div className="sum-item">
              <div className="k">ROI (факт)</div>
              <div className="v" style={{ fontSize: 17 }}>{fmtRatio(campaign.actualRoi)}</div>
            </div>
            <div className="sum-item">
              <div className="k">Данных и выгрузок</div>
              <div className="v" style={{ fontSize: 17 }}>
                {fmtNum(campaign.daysCount)} дн. / {fmtNum(campaign.uploadCount)} выгр.
              </div>
            </div>
            <div className="sum-item">
              <div className="k">Прогнозов</div>
              <div className="v" style={{ fontSize: 17 }}>{fmtNum(campaign.forecastCount)}</div>
            </div>
          </div>

          <MiniTrend metrics={insight.metrics} />

          <h3 className="mini-head">Последние прогнозы</h3>
          {forecasts.length === 0
            ? <EmptyState>прогнозов по кампании не было</EmptyState>
            : (
              <table>
                <thead>
                  <tr><th>Дата</th><th>Горизонт</th><th>ROI</th><th>Вероятность</th><th>Срок</th><th>Статус</th></tr>
                </thead>
                <tbody>
                  {forecasts.map((forecast) => (
                    <tr key={forecast.id}>
                      <td className="mono">{fmtDateTime(forecast.startedAt)}</td>
                      <td className="mono">{forecast.horizonDays === null ? '—' : `${fmtNum(forecast.horizonDays)} дн.`}</td>
                      <td className="mono">{forecast.forecastRoi === null ? '—' : fmtRatio(forecast.forecastRoi)}</td>
                      <td className="mono">{forecast.paybackProbability === null ? '—' : `${fmtNum(forecast.paybackProbability * 100)} %`}</td>
                      <td>{forecast.paybackMonths === null ? '—' : fmtDays(forecast.paybackMonths * 30.44)}</td>
                      <td>
                        {forecast.status === 'COMPLETED' && <StatusPill tone="ok" label="готов" />}
                        {forecast.status === 'FAILED' && <StatusPill tone="bad" label="ошибка" />}
                        {forecast.status !== 'COMPLETED' && forecast.status !== 'FAILED' && <StatusPill tone="run" label="в работе" />}
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
            )}

          <div className="foot">
            <button type="button" className="ghost-btn" onClick={onClose}>Закрыть</button>
          </div>
        </>
      )}
    </Modal>
  )
}

function AuditTable({ entries, table, onOpenCampaign }: {
  entries: AuditEntry[]
  table: SortTable<AuditEntry>
  onOpenCampaign: (id: number) => void
}) {
  if (entries.length === 0) {
    return <EmptyState>записей нет</EmptyState>
  }
  return (
    <table>
      <thead>
        <tr>
          {AUDIT_COLUMNS.map((column) => <SortTh key={column.id} table={table} column={column} />)}
        </tr>
      </thead>
      <tbody>
        {entries.map((entry) => (
          <tr key={entry.id}>
            <td className="mono">{fmtDateTime(entry.actionTime)}</td>
            <td>{entry.fullName ?? entry.login ?? 'система'}</td>
            <td>{entry.action}</td>
            <td>
              {entry.objectType === 'кампания' && entry.objectId
                ? <CampaignLink id={entry.objectId} name={`кампания #${entry.objectId}`} onOpen={onOpenCampaign} />
                : entry.objectType ? `${entry.objectType}${entry.objectId ? ` #${entry.objectId}` : ''}` : '—'}
            </td>
            <td style={{ maxWidth: 320, overflow: 'hidden', textOverflow: 'ellipsis', whiteSpace: 'nowrap' }} title={entry.details ?? ''}>
              {entry.details ?? '—'}
            </td>
            <td className="mono">{entry.ip ?? '—'}</td>
          </tr>
        ))}
      </tbody>
    </table>
  )
}

/* ===================== МОДЕЛИ И ОБУЧЕНИЕ ===================== */

function ModelsTab({ onOpenCampaign }: { onOpenCampaign: (id: number) => void }) {
  const [models, setModels] = useState<AdminModel[] | null>(null)
  const [error, setError] = useState('')
  const [trainOpen, setTrainOpen] = useState(false)
  const [busyId, setBusyId] = useState<number | null>(null)

  const load = useCallback(() => {
    api.adminModels()
      .then(setModels)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить модели'))
  }, [])

  useEffect(load, [load])

  const activate = async (model: AdminModel) => {
    if (busyId) {
      return
    }
    setBusyId(model.modelId)
    setError('')
    try {
      await api.activateModel(model.modelId)
      load()
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось активировать версию')
    } finally {
      setBusyId(null)
    }
  }

  if (!models) {
    return error ? <ErrorBox message={error} /> : <PageLoader />
  }

  const active = models.find((model) => model.isActive)

  return (
    <>
      {error && <div style={{ marginBottom: 12 }}><ErrorBox message={error} /></div>}
      <div className="grid" style={{ gridTemplateColumns: '1fr 1.5fr', alignItems: 'start' }}>
        <div className="card">
          <div className="card-head">
            <div>
              <h2>Текущая модель</h2>
              <div className="csub">активна для всех прогнозов</div>
            </div>
            {active && <span className="pill cur">{active.version}</span>}
          </div>
          {active
            ? (
              <table style={{ fontSize: 13.5 }}>
                <tbody>
                  <tr><td style={{ color: 'var(--muted)' }}>Алгоритм</td><td><b>CatBoost, ансамбль из 10</b></td></tr>
                  <tr>
                    <td style={{ color: 'var(--muted)' }}>MAPE</td>
                    <td><b>{fmtDecimal(active.mape)} %</b> <span style={{ color: 'var(--ok)', fontSize: 12 }}>(норма ≤ 15 %)</span></td>
                  </tr>
                  <tr><td style={{ color: 'var(--muted)' }}>Обучена на</td><td><b>{active.snapshot ? `${fmtNum(active.snapshot.campaignIds.length)} кампаниях` : '—'}</b></td></tr>
                  <tr><td style={{ color: 'var(--muted)' }}>Дата обучения</td><td><b>{fmtDateTime(active.trainedAt)}</b></td></tr>
                  {active.snapshot && (
                    <tr><td style={{ color: 'var(--muted)' }}>Снимок выборки</td><td><b title={active.snapshot.hash}>SHA-256 · {active.snapshot.hash.slice(0, 12)}…</b></td></tr>
                  )}
                </tbody>
              </table>
              )
            : <EmptyState>активной версии нет</EmptyState>}
          <button className="cta" style={{ width: '100%', marginTop: 16 }} onClick={() => setTrainOpen(true)}>
            ⟳ Обучить заново
          </button>
          <div className="csub" style={{ textAlign: 'center', marginTop: 9 }}>шаг 1 из 2 — открыть окно проверки состава выборки</div>
        </div>

        <div className="card">
          <div className="card-head">
            <div>
              <h2>Версии модели</h2>
              <div className="csub">хранятся все, можно вернуться назад</div>
            </div>
          </div>
          {models.length === 0
            ? <EmptyState>версий пока нет</EmptyState>
            : (
              <table>
                <thead>
                  <tr><th>Версия</th><th>MAPE</th><th>Кампаний</th><th>Дата</th><th>Статус</th><th></th></tr>
                </thead>
                <tbody>
                  {models.map((model) => (
                    <tr key={model.modelId}>
                      <td className="mono"><b>{model.version}</b></td>
                      <td className="mono">{fmtDecimal(model.mape)} %</td>
                      <td className="mono">{model.snapshot ? fmtNum(model.snapshot.campaignIds.length) : '—'}</td>
                      <td className="mono">{fmtDate(model.trainedAt)}</td>
                      <td>
                        {model.isActive && <StatusPill tone="cur" label="активна" />}
                        {!model.isActive && model.available && <StatusPill tone="mark" label="в архиве" />}
                        {!model.available && <StatusPill tone="bad" label="недоступна" />}
                      </td>
                      <td>
                        <div className="row-actions">
                          {!model.isActive && (
                            <button disabled={!model.available || busyId === model.modelId} onClick={() => activate(model)}>
                              Откатить
                            </button>
                          )}
                        </div>
                      </td>
                    </tr>
                  ))}
                </tbody>
              </table>
              )}
          <div className="note-card">
            ⚠️
            <span>Откат версии — опасная операция: все новые прогнозы сразу начнут считаться старой моделью.</span>
          </div>
        </div>
      </div>

      <TrainingModal
        open={trainOpen}
        onClose={() => setTrainOpen(false)}
        onTrained={load}
        onOpenCampaign={onOpenCampaign}
      />
    </>
  )
}

interface FlagChange {
  inTraining: boolean
  isTest: boolean
}

function TrainingModal({ open, onClose, onTrained, onOpenCampaign }: {
  open: boolean
  onClose: () => void
  onTrained: () => void
  onOpenCampaign: (id: number) => void
}) {
  const [preview, setPreview] = useState<TrainingPreview | null>(null)
  const [error, setError] = useState('')
  const [changes, setChanges] = useState<Map<number, FlagChange>>(new Map())
  const [reason, setReason] = useState('')
  const [search, setSearch] = useState('')
  const [page, setPage] = useState(1)
  const [phase, setPhase] = useState<'edit' | 'running' | 'done'>('edit')
  const [task, setTask] = useState<TrainingTask | null>(null)

  const found = useMemo(() => {
    const query = search.trim().toLowerCase()
    return (preview?.included ?? []).filter((row) => !query || row.name.toLowerCase().includes(query))
  }, [preview, search])
  const table = useSortTable(found, TRAINING_COLUMNS)

  useEffect(() => {
    if (!open) {
      return
    }
    setPreview(null)
    setChanges(new Map())
    setReason('')
    setSearch('')
    setPage(1)
    setPhase('edit')
    setTask(null)
    setError('')
    api.trainingPreview()
      .then(setPreview)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить состав выборки'))
  }, [open])

  const pages = Math.max(1, Math.ceil(table.rows.length / TRAINING_PAGE_SIZE))
  const currentPage = Math.min(page, pages)
  const includedRows = table.rows.slice((currentPage - 1) * TRAINING_PAGE_SIZE, currentPage * TRAINING_PAGE_SIZE)

  const excludedRows = preview?.excluded ?? []

  const toggle = (id: number, current: FlagChange) => {
    const next = new Map(changes)
    if (next.has(id)) {
      next.delete(id)
    } else {
      next.set(id, current)
    }
    setChanges(next)
  }

  const pollTask = useCallback(async (taskId: string) => {
    for (;;) {
      await new Promise((resolve) => setTimeout(resolve, 2000))
      try {
        const status = await api.trainingTask(taskId)
        setTask(status)
        if (status.status === 'COMPLETED' || status.status === 'FAILED') {
          setPhase('done')
          if (status.status === 'COMPLETED') {
            onTrained()
          }
          return
        }
      } catch (cause) {
        setError(cause instanceof ApiError ? cause.message : 'ошибка получения статуса обучения')
        setPhase('done')
        return
      }
    }
  }, [onTrained])

  const start = async () => {
    if (phase === 'running') {
      return
    }
    setError('')
    if (changes.size > 0 && !reason.trim()) {
      setError('укажите причину изменения состава выборки — она запишется в журнал')
      return
    }
    setPhase('running')
    try {
      for (const [id, change] of changes) {
        await api.updateTrainingFlags(id, { ...change, reason: reason.trim() })
      }
      const accepted = await api.startTraining({})
      setTask({ taskId: accepted.taskId, status: 'RUNNING', model: null, error: null })
      void pollTask(accepted.taskId)
    } catch (cause) {
      setError(cause instanceof ApiError ? cause.message : 'не удалось запустить обучение')
      setPhase('edit')
    }
  }

  const resultModel = task?.status === 'COMPLETED' ? task.model : null

  return (
    <Modal
      open={open}
      onClose={phase === 'running' ? () => {} : onClose}
      title="Состав обучающей выборки"
      subtitle="шаг 2 из 2 — кто попадёт в новую версию модели; кампанию можно исключить галочкой, а её название открыть карточку в новой вкладке"
      wide
    >
      {!preview && !error && <PageLoader />}
      {error && <div style={{ marginBottom: 12 }}><ErrorBox message={error} /></div>}
      {preview && (
        <>
          <div className="summary">
            <div className="sum-item">
              <div className="k">В выборке</div>
              <div className="v">{fmtNum(preview.campaignCount)}</div>
            </div>
            <div className="sum-item">
              <div className="k">Записей датасета</div>
              <div className="v">{fmtNum(preview.recordCount)}</div>
            </div>
            <div className="sum-item">
              <div className="k">Исключено</div>
              <div className="v">{fmtNum(preview.excluded.length + changes.size)} <span>с учётом ваших правок</span></div>
            </div>
            <div className="sum-item">
              <div className="k">Период данных</div>
              <div className="v" style={{ fontSize: 15 }}>{fmtDate(preview.periodStart)} – {fmtDate(preview.periodEnd)}</div>
            </div>
          </div>

          <div className="toolbar">
            <div className="fchips">
              {preview.byPlatform.map((row) => (
                <span className="detect-chip" key={row.platform}>
                  {row.platform} · <b>{fmtNum(row.campaigns)} кампаний</b>
                </span>
              ))}
            </div>
            <div className="spacer" />
            <input
              className="search"
              placeholder="Поиск кампании…"
              value={search}
              onChange={(event) => { setSearch(event.target.value); setPage(1) }}
            />
          </div>

          <table>
            <thead>
              <tr>
                <th style={{ width: 38 }}></th>
                {TRAINING_COLUMNS.map((column) => <SortTh key={column.id} table={table} column={column} />)}
                <th>Состояние</th>
              </tr>
            </thead>
            <tbody>
              {includedRows.map((row) => {
                const pending = changes.has(row.id)
                return (
                  <tr key={row.id} className={pending ? 'dimmed' : ''}>
                    <td>
                      <input
                        type="checkbox"
                        className="chk"
                        checked={!pending}
                        disabled={phase !== 'edit'}
                        onChange={() => toggle(row.id, { inTraining: false, isTest: false })}
                      />
                    </td>
                    <td><CampaignLink id={row.id} name={row.name} onOpen={onOpenCampaign} /></td>
                    <td>{row.platform}</td>
                    <td className="mono">{fmtNum(row.days)}</td>
                    <td className="mono">{row.ratio}</td>
                    <td>{pending ? <StatusPill tone="bad" label="исключите из обучения" /> : <StatusPill tone="ok" label="ок" />}</td>
                  </tr>
                )
              })}
              {excludedRows.map((row) => {
                const pending = changes.has(row.id)
                return (
                  <tr key={row.id} className={pending ? '' : 'dimmed'}>
                    <td>
                      <input
                        type="checkbox"
                        className="chk"
                        checked={pending}
                        disabled={phase !== 'edit'}
                        onChange={() => toggle(row.id, { inTraining: true, isTest: false })}
                      />
                    </td>
                    <td><CampaignLink id={row.id} name={row.name} onOpen={onOpenCampaign} /></td>
                    <td>{row.platform}</td>
                    <td className="mono">—</td>
                    <td className="mono">—</td>
                    <td>
                      {pending
                        ? <StatusPill tone="ok" label="вернёте в обучение" />
                        : <StatusPill tone="bad" label={row.isTest ? 'тестовая кампания' : 'исключена из обучения'} />}
                    </td>
                  </tr>
                )
              })}
            </tbody>
          </table>
          <div className="table-foot">
            <span className="rows-range">
              {rangeLabel(currentPage, TRAINING_PAGE_SIZE, includedRows.length, table.rows.length, ['кампания', 'кампании', 'кампаний'])}
            </span>
            <Pager page={currentPage} pageCount={pages} onChange={setPage} />
          </div>

          {changes.size > 0 && phase === 'edit' && (
            <div className="field" style={{ marginTop: 14 }}>
              <label>Причина изменения состава (запишется в журнал действий)</label>
              <input
                placeholder="например: убрал кампании с аномальным доходом после импорта"
                value={reason}
                onChange={(event) => setReason(event.target.value)}
              />
            </div>
          )}

          {phase === 'running' && (
            <div className="note-card">
              <div className="spinner" />
              <span>Обучение выполняется — ансамбль из 10 моделей CatBoost, обычно это занимает меньше минуты…</span>
            </div>
          )}
          {phase === 'done' && resultModel && (
            <div className="success-box" style={{ marginTop: 14 }}>
              Обучение завершено: версия <b>{resultModel.version}</b>, MAPE {fmtDecimal(resultModel.mape)} %, R² {fmtDecimal(resultModel.r2)},
              кампаний в выборке {fmtNum(resultModel.campaignCount)}. Новая версия активна для всех прогнозов.
            </div>
          )}
          {phase === 'done' && task?.status === 'FAILED' && (
            <div className="error-box" style={{ marginTop: 14 }}>
              <div className="t">обучение завершилось с ошибкой</div>
              {task.error && <div style={{ marginTop: 6, fontSize: 12.5 }}>{task.error}</div>}
            </div>
          )}

          <div className="table-foot" style={{ marginTop: 16 }}>
            <span>
              {changes.size > 0
                ? `изменений состава: ${changes.size} — применятся перед обучением`
                : 'Исключённые кампании остаются в системе и участвуют в прогнозах — только не попадают в обучение.'}
            </span>
            <div style={{ display: 'flex', gap: 10, alignItems: 'center' }}>
              <button className="ghost-btn" style={{ padding: '9px 16px', fontSize: 12.5 }} disabled={phase !== 'edit' || changes.size === 0} onClick={() => setChanges(new Map())}>
                Вернуть всё
              </button>
              {phase === 'done'
                ? <button className="cta" style={{ padding: '10px 22px' }} onClick={onClose}>Готово</button>
                : (
                  <button className="cta" style={{ padding: '10px 22px' }} disabled={phase === 'running'} onClick={start}>
                    {phase === 'running' ? 'Обучаем…' : 'Запустить обучение'}
                  </button>
                  )}
            </div>
          </div>

          <div className="note-card">
            💡
            <span>
              Состав выборки сохраняется в метаданные версии (<b>список campaign_id, правило отбора, хэш датасета</b>) —
              потом всегда видно, на чём обучена модель. Каждое исключение пишется в журнал действий с причиной.
            </span>
          </div>
        </>
      )}
    </Modal>
  )
}

/* ===================== РЕЗЕРВНЫЕ КОПИИ ===================== */

function BackupsTab() {
  const [backups, setBackups] = useState<BackupEntry[] | null>(null)
  const [error, setError] = useState('')
  const [downloadError, setDownloadError] = useState('')

  useEffect(() => {
    api.backups()
      .then(setBackups)
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить список копий'))
  }, [])

  const download = async (name: string) => {
    setDownloadError('')
    try {
      await downloadFile(`/api/admin/backups/${encodeURIComponent(name)}/download`, name)
    } catch (cause) {
      setDownloadError(cause instanceof ApiError ? cause.message : 'не удалось скачать копию')
    }
  }

  if (!backups) {
    return error ? <ErrorBox message={error} /> : <PageLoader />
  }

  const latest = backups[0]

  return (
    <div className="grid" style={{ gridTemplateColumns: '1fr 1.5fr', alignItems: 'start' }}>
      <div className="card">
        <div className="card-head"><h2>Расписание</h2></div>
        <table style={{ fontSize: 13.5 }}>
          <tbody>
            <tr><td style={{ color: 'var(--muted)' }}>Способ</td><td><b>pg_dump, вручную или по расписанию</b></td></tr>
            <tr><td style={{ color: 'var(--muted)' }}>Хранилище</td><td><b>db/backup на сервере</b></td></tr>
            <tr><td style={{ color: 'var(--muted)' }}>Последняя</td><td><b>{latest ? `${fmtDateTime(latest.lastModified)} · ${fmtDecimal(latest.size / 1024 / 1024)} МБ` : '—'}</b></td></tr>
          </tbody>
        </table>
        <div className="note-card">
          💡
          <span>
            Создание копии выполняется на сервере скриптом <b>db/backup/backup.sh</b> — из соображений безопасности
            приложение не запускает pg_dump само. Восстановление — через psql из файла копии.
          </span>
        </div>
      </div>

      <div className="card">
        <div className="card-head">
          <div>
            <h2>История копий</h2>
            <div className="csub">скачивание фиксируется в журнале действий</div>
          </div>
        </div>
        {downloadError && <div className="form-error" style={{ marginBottom: 12 }}>{downloadError}</div>}
        {backups.length === 0
          ? <EmptyState>копий пока нет — создайте первую скриптом backup.sh</EmptyState>
          : (
            <table>
              <thead>
                <tr><th>Файл</th><th>Дата и время</th><th>Размер</th><th></th></tr>
              </thead>
              <tbody>
                {backups.map((backup) => (
                  <tr key={backup.name}>
                    <td className="mono">{backup.name}</td>
                    <td className="mono">{fmtDateTime(backup.lastModified)}</td>
                    <td className="mono">{fmtDecimal(backup.size / 1024 / 1024)} МБ</td>
                    <td>
                      <div className="row-actions">
                        <button onClick={() => download(backup.name)}>Скачать</button>
                      </div>
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
            )}
      </div>
    </div>
  )
}
