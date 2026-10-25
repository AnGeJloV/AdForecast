export interface LoginResponse {
  accessToken: string
  login: string
  fullName: string
  role: string
  organization: string
}

export interface CurrentUser {
  login: string
  fullName: string
  role: string
  organization: string
  permissions: string[]
}

export interface RegisterRequest {
  login: string
  email: string
  fullName: string
  password: string
}

export type CampaignStatus = 'PLANNED' | 'ACTIVE' | 'FINISHED'

export type ForecastVerdict =
  | 'PROFITABLE'
  | 'BORDERLINE'
  | 'UNPROFITABLE'
  | 'OUT_OF_RANGE'
  | 'BELOW_MINIMUM'
  | 'NO_CHANNEL_DATA'

export type ModelScope = 'BLEND' | 'OWN' | 'GENERAL'

export interface CampaignSummary {
  id: number
  campaignName: string
  status: CampaignStatus
  budget: number
  currency: string
  platform: string
  uploadCount: number
  daysCount: number
  totalCost: number
  totalRevenue: number
  actualRoi: number | null
  totalImpressions: number
  totalClicks: number
  latestForecastRoi: number | null
  latestPaybackRatio: number | null
  latestVerdict: ForecastVerdict | null
}

export interface CampaignDetail extends CampaignSummary {
  accountId: number
  accountName: string
  forecastCount: number
  lastForecastAt: string | null
}

export interface CampaignCreateRequest {
  accountId: number
  campaignName: string
  budget: number
  currency: string
}

export interface CampaignUpdateRequest {
  campaignName: string
  budget: number
  currency: string
  status: CampaignStatus
}

export interface AdAccount {
  id: number
  accountName: string
  platform: string
}

export interface PlatformInfo {
  name: string
  cpc: number
  minDayBudget: number
  note: string
}

export interface AdminCampaignInsight {
  campaign: CampaignDetail
  metrics: DailyMetricPoint[]
  forecasts: ForecastHistoryItem[]
}

export interface ForecastHistoryItem {
  id: number
  status: string
  startedAt: string
  verdict: ForecastVerdict | null
  paybackRatio: number | null
  forecastRoi: number | null
  intervalLower: number | null
  intervalUpper: number | null
  agreement: number | null
  paybackDays: number | null
  paybackMonths: number | null
  paybackWithinDuration: boolean | null
  expectedProfit: number | null
  budgetMin: number | null
  budgetMax: number | null
  budgetAny: boolean
  modelScope: ModelScope | null
  dayBudgetMin: number | null
  dayBudgetMax: number | null
  dataNote: string | null
  durationDays: number | null
  startMonth: number | null
  historyDays: number | null
  historyCost: number | null
  historyRevenue: number | null
  budget: number | null
  currency: string | null
  ownModelVersion: string | null
  generalModelVersion: string | null
  ownWeight: number | null
  ownCampaigns: number | null
  basis: string | null
  mape: number | null
}

export interface DailyMetricPoint {
  date: string
  cost: number
  revenue: number
  impressions: number
  clicks: number
}

export type CashflowGranularity = 'DAY' | 'WEEK' | 'MONTH' | 'QUARTER'

export interface CashflowPoint {
  periodStart: string
  cost: number
  revenue: number
}

export interface DashboardChannel {
  platform: string
  finishedCampaigns: number
  totalCost: number
  totalRevenue: number
  avgRoi: number
  profitable: number
  unprofitable: number
}

export interface PlanFactRow {
  campaignId: number
  campaignName: string
  currency: string
  platform: string
  actualRoi: number | null
  forecastRoi: number
  deviation: number | null
  days: number
  durationDays: number
  comparable: boolean
}

export interface DashboardData {
  campaignsTotal: number
  campaignsPlanned: number
  campaignsActive: number
  campaignsFinished: number
  /** Все денежные суммы сводки приведены к этой валюте. */
  baseCurrency: string
  totalBudget: number
  totalCost: number
  totalRevenue: number
  actualRoi: number | null
  profitableCampaigns: number
  unprofitableCampaigns: number
  forecastsTotal: number
  forecastsCompleted: number
  channels: DashboardChannel[]
  planVsFact: PlanFactRow[]
  /** средняя абсолютная ошибка по сопоставимым кампаниям, п.п. ROI */
  planVsFactAvgError: number | null
  /** среднее знаковое отклонение: плюс — прогноз занижает окупаемость, п.п. ROI */
  planVsFactBias: number | null
  /** средняя относительная ошибка к прогнозу, % */
  planVsFactAvgErrorPercent: number | null
}

export interface UploadItem {
  id: number
  fileName: string
  fileFormat: string
  periodStart: string
  periodEnd: string
  uploadedAt: string
  rowsCount: number
}

export interface UploadListItem extends UploadItem {
  campaignId: number
  campaignName: string
}

export interface ColumnDef {
  index: number
  sourceName: string | null
  field: string | null
  method: string | null
}

export interface AnalyzeResponse {
  format: string
  delimiter: string | null
  charset: string
  dateFormat: string | null
  columns: ColumnDef[]
  previewRows: string[][]
  rowCount: number
  periodStart: string | null
  periodEnd: string | null
  warnings: string[]
  errors: string[]
}

export type UploadField = 'date' | 'impressions' | 'clicks' | 'cost' | 'revenue'

export interface ForecastRequest {
  durationDays: number | null
  startMonth: number | null
  modelScope?: ModelScope
}

export interface ForecastResult extends ForecastHistoryItem {}

export interface ForecastListItem {
  id: number
  campaignId: number
  campaignName: string
  startedAt: string
  status: string
  verdict: ForecastVerdict | null
  paybackRatio: number | null
  forecastRoi: number | null
  intervalLower: number | null
  intervalUpper: number | null
  durationDays: number | null
  paybackMonths: number | null
  mape: number | null
}

export interface CompareVariant {
  platform: string
  currency: string
  budget: number
  durationDays: number
  startMonth: number
}

export interface CompareResult extends CompareVariant {
  verdict: ForecastVerdict
  paybackRatio: number
  forecastRoi: number
  intervalLower: number
  intervalUpper: number
  paybackMonths: number | null
  paybackWithinDuration: boolean
  profitableBudgetMin: number | null
  profitableBudgetMax: number | null
  profitableAtAnyBudget: boolean
  basis: string
}

export interface UserItem {
  id: number
  login: string
  email: string
  fullName: string
  role: string
  isActive: boolean
  lastLogin: string | null
}

export interface RoleItem {
  id: number
  name: string
}

export interface AdminSummary {
  usersTotal: number
  usersActive: number
  campaignsPlanned: number
  campaignsActive: number
  campaignsFinished: number
  forecastsTotal: number
  forecastsCompleted: number
  uploadsTotal: number
  exportsTotal: number
  modelVersions: number
  mlServiceAvailable: boolean
  activeModelVersion: string | null
  activeModelTrainedAt: string | null
  activeModelMape: number | null
}

export interface AuditEntry {
  id: number
  userId: number | null
  login: string | null
  fullName: string | null
  action: string
  objectType: string | null
  objectId: number
  details: string | null
  actionTime: string
  ip: string | null
}

export interface Page<T> {
  content: T[]
  page: number
  size: number
  totalElements: number
  totalPages: number
}

export interface ModelSnapshot {
  campaignIds: number[]
  rule: string
  hash: string
}

export interface AdminModel {
  modelId: number
  modelName: string
  organizationId: number | null
  organizationName: string | null
  scope: string
  version: string
  trainedAt: string
  mape: number
  mapeWithHistory: number | null
  mapePlanned: number | null
  isActive: boolean
  available: boolean
  snapshot: ModelSnapshot | null
}

export interface TrainingPreviewRow {
  id: number
  name: string
  platform: string
  budget: string
  days: number
  ratio: string
  periodStart: string
  periodEnd: string
}

export interface TrainingPreview {
  campaignCount: number
  recordCount: number
  byPlatform: { platform: string; campaigns: number; records: number }[]
  periodStart: string
  periodEnd: string
  included: TrainingPreviewRow[]
  excluded: { id: number; name: string; platform: string; inTraining: boolean; isTest: boolean; days: number; periodStart: string; periodEnd: string }[]
}

export interface TrainingTaskModel {
  version: string
  trainedAt: string
  mape: number
  r2: number
  mae: number
  mapeWithHistory: number | null
  r2WithHistory: number | null
  mapePlanned: number | null
  r2Planned: number | null
  holdoutSize: number
  ensembleSize: number
  campaignCount: number
  campaignIds: number[]
  selectionRule: string
}

export interface TrainingTask {
  taskId: string
  status: string
  model: TrainingTaskModel | null
  error: string | null
}

export interface AdminModelsResponse {
  models: AdminModel[]
  organizationsTotal: number
  organizationsWithOwnModel: number
}

export interface AdminActivityGroup {
  category: string
  label: string
  count: number
}

export interface AdminActivity {
  total: number
  groups: AdminActivityGroup[]
}

export type ActivityUnit = 'hour' | 'day' | 'week' | 'month'

export interface ActivityPeriod {
  from: string
  to: string
  count: number
}

export interface AdminActivityPeriods {
  periods: ActivityPeriod[]
}
