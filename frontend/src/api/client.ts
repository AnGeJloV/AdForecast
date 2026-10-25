import type {
  AdAccount,
  AdminActivity,
  AdminActivityPeriods,
  AdminCampaignInsight,
  AdminSummary,
  ActivityUnit,
  AnalyzeResponse,
  AuditEntry,
  CampaignCreateRequest,
  CampaignDetail,
  CampaignSummary,
  CampaignUpdateRequest,
  CashflowGranularity,
  CashflowPoint,
  CompareResult,
  CompareVariant,
  CurrentUser,
  DailyMetricPoint,
  DashboardData,
  ForecastListItem,
  ForecastRequest,
  ForecastResult,
  AdminModelsResponse,
  LoginResponse,
  Page,
  PlatformInfo,
  RegisterRequest,
  RoleItem,
  TrainingPreview,
  TrainingTask,
  UploadItem,
  UploadListItem,
  UserItem,
} from './types'

const TOKEN_KEY = 'adforecast.token'

/** Времена сервер хранит в UTC; этот часовой пояс нужен и для отображения, и для поиска по ним. */
const CLIENT_TIME_ZONE = Intl.DateTimeFormat().resolvedOptions().timeZone || 'UTC'

export class ApiError extends Error {
  status: number
  errors: string[]

  constructor(status: number, message: string, errors: string[] = []) {
    super(message)
    this.status = status
    this.errors = errors
  }
}

let unauthorizedHandler: (() => void) | null = null

export function setUnauthorizedHandler(handler: () => void) {
  unauthorizedHandler = handler
}

export function getToken(): string | null {
  return localStorage.getItem(TOKEN_KEY)
}

export function setToken(token: string | null) {
  if (token) {
    localStorage.setItem(TOKEN_KEY, token)
  } else {
    localStorage.removeItem(TOKEN_KEY)
  }
}

async function request<T>(path: string, init: RequestInit = {}): Promise<T> {
  const headers = new Headers(init.headers)
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  if (init.body && !(init.body instanceof FormData)) {
    headers.set('Content-Type', 'application/json')
  }
  let response: Response
  try {
    response = await fetch(path, { ...init, headers })
  } catch {
    throw new ApiError(0, 'сервер недоступен — проверьте подключение')
  }
  if (response.status === 401 && token) {
    unauthorizedHandler?.()
    throw new ApiError(401, 'сессия истекла — войдите снова')
  }
  if (!response.ok) {
    let message = `ошибка сервера (${response.status})`
    let errors: string[] = []
    try {
      const body = await response.json()
      if (typeof body?.message === 'string' && body.message) {
        message = body.message
      }
      if (Array.isArray(body?.errors)) {
        errors = body.errors.filter((item: unknown) => typeof item === 'string')
      }
    } catch {
      if (response.status === 403) {
        message = 'нет доступа к операции'
      }
    }
    throw new ApiError(response.status, message, errors)
  }
  if (response.status === 204) {
    return undefined as T
  }
  // void-эндпоинты бэкенда отвечают 200 с пустым телом — трактуем так же, как 204
  const text = await response.text()
  if (!text) {
    return undefined as T
  }
  return JSON.parse(text) as T
}

export async function downloadFile(path: string, fallbackName: string) {
  const headers = new Headers()
  const token = getToken()
  if (token) {
    headers.set('Authorization', `Bearer ${token}`)
  }
  const response = await fetch(path, { headers })
  if (!response.ok) {
    let message = `ошибка сервера (${response.status})`
    try {
      const body = await response.json()
      if (typeof body?.message === 'string' && body.message) {
        message = body.message
      }
    } catch {
      // тело не JSON — оставляем сообщение по коду
    }
    throw new ApiError(response.status, message)
  }
  const blob = await response.blob()
  const disposition = response.headers.get('Content-Disposition') ?? ''
  const match = /filename="?([^";]+)"?/.exec(disposition)
  const url = URL.createObjectURL(blob)
  const link = document.createElement('a')
  link.href = url
  link.download = match?.[1]?.trim() ?? fallbackName
  link.click()
  URL.revokeObjectURL(url)
}

function multipart(file: File, mapping: Record<string, number> | null): FormData {
  const data = new FormData()
  data.append('file', file)
  if (mapping) {
    data.append('mapping', JSON.stringify(mapping))
  }
  return data
}

export const api = {
  login: (login: string, password: string) =>
    request<LoginResponse>('/api/auth/login', { method: 'POST', body: JSON.stringify({ login, password }) }),
  register: (data: RegisterRequest) =>
    request<LoginResponse>('/api/auth/register', { method: 'POST', body: JSON.stringify(data) }),
  me: () => request<CurrentUser>('/api/auth/me'),

  campaigns: () => request<CampaignSummary[]>('/api/campaigns'),
  campaign: (id: number) => request<CampaignDetail>(`/api/campaigns/${id}`),
  createCampaign: (data: CampaignCreateRequest) =>
    request<CampaignSummary>('/api/campaigns', { method: 'POST', body: JSON.stringify(data) }),
  updateCampaign: (id: number, data: CampaignUpdateRequest) =>
    request<CampaignSummary>(`/api/campaigns/${id}`, { method: 'PUT', body: JSON.stringify(data) }),
  campaignMetrics: (id: number) => request<DailyMetricPoint[]>(`/api/campaigns/${id}/metrics`),
  adAccounts: () => request<AdAccount[]>('/api/ad-accounts'),
  adPlatforms: () => request<PlatformInfo[]>('/api/ad-accounts/platforms'),
  addAdAccount: (data: { accountName: string; platform: string }) =>
    request<AdAccount>('/api/ad-accounts', { method: 'POST', body: JSON.stringify(data) }),

  dashboard: () => request<DashboardData>('/api/dashboard'),
  // limit === null — вся история (режим «за всё время»)
  cashflow: (granularity: CashflowGranularity, limit: number | null) =>
    request<CashflowPoint[]>(
      `/api/dashboard/cashflow?granularity=${granularity}${limit === null ? '' : `&limit=${limit}`}`,
    ),

  uploads: () => request<UploadListItem[]>('/api/uploads'),
  campaignUploads: (campaignId: number) => request<UploadItem[]>(`/api/campaigns/${campaignId}/uploads`),
  analyzeUpload: (campaignId: number, file: File) =>
    request<AnalyzeResponse>(`/api/campaigns/${campaignId}/uploads/analyze`, {
      method: 'POST',
      body: multipart(file, null),
    }),
  commitUpload: (campaignId: number, file: File, mapping: Record<string, number> | null) =>
    request<UploadItem>(`/api/campaigns/${campaignId}/uploads`, {
      method: 'POST',
      body: multipart(file, mapping),
    }),

  runForecast: (campaignId: number, data: ForecastRequest) =>
    request<ForecastResult>(`/api/campaigns/${campaignId}/forecasts`, {
      method: 'POST',
      body: JSON.stringify(data),
    }),
  campaignForecasts: (campaignId: number) => request<ForecastResult[]>(`/api/campaigns/${campaignId}/forecasts`),
  forecasts: () => request<ForecastListItem[]>('/api/forecasts'),
  compare: (variants: CompareVariant[]) =>
    request<CompareResult[]>('/api/forecasts/compare', { method: 'POST', body: JSON.stringify({ variants }) }),

  users: () => request<UserItem[]>('/api/users'),
  roles: () => request<RoleItem[]>('/api/users/roles'),
  createUser: (data: { login: string; email: string; fullName: string; password: string; roleId: number }) =>
    request<UserItem>('/api/users', { method: 'POST', body: JSON.stringify(data) }),
  updateUser: (id: number, data: { email: string; fullName: string; roleId: number; isActive: boolean }) =>
    request<UserItem>(`/api/users/${id}`, { method: 'PUT', body: JSON.stringify(data) }),

  adminSummary: () => request<AdminSummary>('/api/admin/summary'),
  adminActivity: (from: string | null, to: string | null) => {
    const query = new URLSearchParams()
    if (from) query.set('from', from)
    if (to) query.set('to', to)
    const suffix = query.toString()
    return request<AdminActivity>(`/api/admin/activity${suffix ? `?${suffix}` : ''}`)
  },
  adminActivityPeriods: (unit: ActivityUnit, timeZone: string) =>
    request<AdminActivityPeriods>(
      `/api/admin/activity/periods?unit=${unit}&tz=${encodeURIComponent(timeZone)}`,
    ),
  adminCampaign: (id: number) => request<AdminCampaignInsight>(`/api/admin/campaigns/${id}`),
  audit: (page: number, size: number, category = 'all', search = '', sort = '', direction = '') =>
    request<Page<AuditEntry>>(
      `/api/admin/audit?page=${page}&size=${size}&category=${encodeURIComponent(category)}`
      + `&search=${encodeURIComponent(search)}&sort=${encodeURIComponent(sort)}&direction=${direction}`
      + `&tz=${encodeURIComponent(CLIENT_TIME_ZONE)}`,
    ),
  logout: () => request<void>('/api/auth/logout', { method: 'POST' }),
  adminModels: () => request<AdminModelsResponse>('/api/admin/models'),
  activateModel: (modelId: number) =>
    request<void>(`/api/admin/models/${modelId}/activate`, { method: 'POST' }),
  deleteModel: (modelId: number) =>
    request<void>(`/api/admin/models/${modelId}`, { method: 'DELETE' }),
  trainingPreview: () => request<TrainingPreview>('/api/admin/training/preview'),
  updateTrainingFlags: (id: number, data: { inTraining: boolean; isTest: boolean; reason: string }) =>
    request<void>(`/api/admin/training/campaigns/${id}/flags`, { method: 'PUT', body: JSON.stringify(data) }),
  startTraining: (data: { ensembleSize?: number; holdoutFraction?: number }) =>
    request<{ taskId: string }>('/api/admin/training/start', { method: 'POST', body: JSON.stringify(data) }),
  trainingTask: (taskId: string) => request<TrainingTask>(`/api/admin/training/tasks/${taskId}`),
}
