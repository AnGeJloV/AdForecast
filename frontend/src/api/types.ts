export interface LoginResponse {
  accessToken: string
  login: string
  fullName: string
  role: string
}

export interface CurrentUser {
  login: string
  fullName: string
  role: string
  permissions: string[]
}

export interface RegisterRequest {
  login: string
  email: string
  fullName: string
  password: string
}

export type CampaignStatus = 'PLANNED' | 'ACTIVE' | 'FINISHED'

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
  latestPaybackMonths: number | null
  latestPaybackProbability: number | null
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

export interface AdminCampaignInsight {
  campaign: CampaignDetail
  metrics: DailyMetricPoint[]
  forecasts: ForecastHistoryItem[]
}

export interface ForecastHistoryItem {
  id: number
  status: string
  startedAt: string
  modelVersion: string
  horizonDays: number | null
  confidenceLevel: number | null
  useSeasonality: boolean | null
  forecastRoi: number | null
  paybackMonths: number | null
  paybackDays: number | null
  paybackWithinHorizon: boolean | null
  paybackProbability: number | null
  mape: number | null
}

export interface DailyMetricPoint {
  date: string
  cost: number
  revenue: number
  impressions: number
  clicks: number
}

export interface CashflowPoint {
  weekStart: string
  cost: number
  revenue: number
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
  horizon: number
  comparable: boolean
}

export interface DashboardData {
  campaignsTotal: number
  campaignsPlanned: number
  campaignsActive: number
  campaignsFinished: number
  totalBudget: number
  totalCost: number
  totalRevenue: number
  actualRoi: number | null
  profitableCampaigns: number
  unprofitableCampaigns: number
  forecastsTotal: number
  forecastsCompleted: number
  planVsFact: PlanFactRow[]
  planVsFactAvgDeviation: number | null
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
  horizonDays: number
  confidenceLevel: number
  useSeasonality: boolean
  startMonth: number | null
}

export interface ForecastResult {
  id: number
  status: string
  startedAt: string
  modelVersion: string | null
  horizonDays: number | null
  confidenceLevel: number | null
  useSeasonality: boolean | null
  forecastRoi: number | null
  paybackMonths: number | null
  paybackDays: number | null
  paybackWithinHorizon: boolean | null
  paybackProbability: number | null
  mape: number | null
}

export interface ForecastListItem {
  id: number
  campaignId: number
  campaignName: string
  startedAt: string
  status: string
  modelVersion: string
  horizonDays: number | null
  forecastRoi: number | null
  paybackMonths: number | null
  paybackProbability: number | null
  mape: number | null
}

export interface CompareVariant {
  platform: string
  budget: number
  durationDays: number
  startMonth: number
}

export interface CompareResult extends CompareVariant {
  forecastRoi: number
  paybackMonths: number | null
  paybackProbability: number
  paybackWithinHorizon: boolean
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
  objectId: number | null
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
  version: string
  trainedAt: string
  mape: number
  isActive: boolean
  available: boolean
  snapshot: ModelSnapshot | null
}

export interface BackupEntry {
  name: string
  size: number
  lastModified: string
}

export interface TrainingPreviewRow {
  id: number
  name: string
  platform: string
  budget: string
  days: number
  ratio: string
}

export interface TrainingPreview {
  campaignCount: number
  recordCount: number
  byPlatform: { platform: string; campaigns: number; records: number }[]
  periodStart: string
  periodEnd: string
  included: TrainingPreviewRow[]
  excluded: { id: number; name: string; platform: string; inTraining: boolean; isTest: boolean }[]
}

export interface TrainingTaskModel {
  version: string
  trainedAt: string
  mape: number
  r2: number
  mae: number
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
