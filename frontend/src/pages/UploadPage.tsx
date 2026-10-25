import { useCallback, useEffect, useRef, useState } from 'react'
import type { ChangeEvent, DragEvent } from 'react'
import { useSearchParams } from 'react-router-dom'
import { api, ApiError } from '../api/client'
import type { AnalyzeResponse, CampaignSummary, UploadField, UploadItem, UploadListItem } from '../api/types'
import { Layout } from '../components/Layout'
import { Select } from '../components/Select'
import { EmptyState, ErrorBox, PageLoader, Pager } from '../components/Ui'
import { fmtDate, fmtNum, plural, rangeLabel } from '../utils/format'

const HISTORY_PAGE_SIZE = 6

const FIELDS: { key: UploadField; label: string }[] = [
  { key: 'date', label: 'дата' },
  { key: 'impressions', label: 'показы' },
  { key: 'clicks', label: 'клики' },
  { key: 'cost', label: 'расход' },
  { key: 'revenue', label: 'доход' },
]

const MAX_FILE_SIZE = 10 * 1024 * 1024

export function UploadPage() {
  const [searchParams] = useSearchParams()
  const [campaigns, setCampaigns] = useState<CampaignSummary[] | null>(null)
  const [campaignId, setCampaignId] = useState<number | null>(null)
  const [history, setHistory] = useState<UploadListItem[]>([])
  const [historyPage, setHistoryPage] = useState(1)
  const [error, setError] = useState('')

  const [file, setFile] = useState<File | null>(null)
  const [analysis, setAnalysis] = useState<AnalyzeResponse | null>(null)
  const [mapping, setMapping] = useState<Record<string, number | null>>({})
  const [analyzing, setAnalyzing] = useState(false)
  const [committing, setCommitting] = useState(false)
  const [wizardError, setWizardError] = useState<{ message: string; errors: string[] } | null>(null)
  const [success, setSuccess] = useState<UploadItem | null>(null)
  const [drag, setDrag] = useState(false)
  const inputRef = useRef<HTMLInputElement>(null)

  const loadHistory = useCallback(() => {
    setHistoryPage(1)
    api.uploads().then(setHistory).catch(() => setHistory([]))
  }, [])

  useEffect(() => {
    api.campaigns()
      .then((list) => {
        setCampaigns(list)
        const preselect = Number(searchParams.get('campaign'))
        const valid = list.find((campaign) => campaign.id === preselect)
        setCampaignId(valid ? valid.id : (list[0]?.id ?? null))
      })
      .catch((cause) => setError(cause instanceof ApiError ? cause.message : 'не удалось загрузить кампании'))
    loadHistory()
  }, [searchParams, loadHistory])

  const resetWizard = () => {
    setFile(null)
    setAnalysis(null)
    setMapping({})
    setWizardError(null)
    setSuccess(null)
    if (inputRef.current) {
      inputRef.current.value = ''
    }
  }

  const startAnalyze = async (chosen: File) => {
    if (!campaignId) {
      setWizardError({ message: 'сначала выберите кампанию', errors: [] })
      return
    }
    if (chosen.size > MAX_FILE_SIZE) {
      setWizardError({ message: 'файл превышает допустимый размер 10 МБ', errors: [] })
      return
    }
    setFile(chosen)
    setAnalysis(null)
    setSuccess(null)
    setWizardError(null)
    setAnalyzing(true)
    try {
      const result = await api.analyzeUpload(campaignId, chosen)
      setAnalysis(result)
      const initial: Record<string, number | null> = {}
      for (const { key } of FIELDS) {
        initial[key] = result.columns.find((column) => column.field === key)?.index ?? null
      }
      setMapping(initial)
    } catch (cause) {
      setFile(null)
      setWizardError(cause instanceof ApiError
        ? { message: cause.message, errors: cause.errors }
        : { message: 'не удалось проанализировать файл', errors: [] })
    } finally {
      setAnalyzing(false)
    }
  }

  const onInputChange = (event: ChangeEvent<HTMLInputElement>) => {
    const chosen = event.target.files?.[0]
    if (chosen) {
      void startAnalyze(chosen)
    }
  }

  const onDrop = (event: DragEvent) => {
    event.preventDefault()
    setDrag(false)
    const chosen = event.dataTransfer.files?.[0]
    if (chosen) {
      void startAnalyze(chosen)
    }
  }

  const commit = async () => {
    if (!file || !campaignId || committing) {
      return
    }
    setCommitting(true)
    setWizardError(null)
    try {
      const fullMapping: Record<string, number> = {}
      for (const { key } of FIELDS) {
        const value = mapping[key]
        if (value !== null && value !== undefined) {
          fullMapping[key] = value
        }
      }
      const hasAll = FIELDS.every(({ key }) => fullMapping[key] !== undefined)
      const uploaded = await api.commitUpload(campaignId, file, hasAll ? fullMapping : null)
      setSuccess(uploaded)
      setFile(null)
      setAnalysis(null)
      if (inputRef.current) {
        inputRef.current.value = ''
      }
      loadHistory()
    } catch (cause) {
      setWizardError(cause instanceof ApiError
        ? { message: cause.message, errors: cause.errors }
        : { message: 'не удалось загрузить файл', errors: [] })
    } finally {
      setCommitting(false)
    }
  }

  if (!campaigns) {
    return <Layout>{error ? <ErrorBox message={error} /> : <PageLoader />}</Layout>
  }

  const historyPages = Math.max(1, Math.ceil(history.length / HISTORY_PAGE_SIZE))
  const historyCurrent = Math.min(historyPage, historyPages)
  const historyRows = history.slice((historyCurrent - 1) * HISTORY_PAGE_SIZE, historyCurrent * HISTORY_PAGE_SIZE)

  const selectedCampaign = campaigns.find((campaign) => campaign.id === campaignId)
  const mappingComplete = FIELDS.every(({ key }) => mapping[key] !== null && mapping[key] !== undefined)
  const analyzeBlocking = (analysis?.errors.length ?? 0) > 0

  return (
    <Layout>
      <div className="page-head">
        <div>
          <h1>Загрузка данных</h1>
          <div className="sub">Выгрузки из рекламных кабинетов — на их основе строятся денежные потоки кампаний</div>
        </div>
      </div>

      <div className="grid" style={{ gridTemplateColumns: '1.15fr 1fr', alignItems: 'start' }}>
        <div className="card">
          <div className="card-head"><h2>Новая выгрузка</h2></div>
          <div className="field">
            <label>Кампания</label>
            <Select
              value={campaignId === null ? '' : String(campaignId)}
              options={campaigns.map((campaign) => ({ value: String(campaign.id), label: campaign.campaignName }))}
              placeholder="выберите кампанию"
              onChange={(next) => {
                setCampaignId(Number(next))
                resetWizard()
              }}
            />
          </div>

          <div
            className={`dropzone${drag ? ' drag' : ''}`}
            onClick={() => inputRef.current?.click()}
            onDragOver={(event) => { event.preventDefault(); setDrag(true) }}
            onDragLeave={() => setDrag(false)}
            onDrop={onDrop}
          >
            <div className="ic">⬆</div>
            <div className="t">{analyzing ? 'Анализируем файл…' : 'Перетащите файл сюда'}</div>
            <div className="s">
              или <span className="browse">выберите на компьютере</span>
              <br />
              CSV или XLSX, до 10 МБ{file ? ` · выбран: ${file.name}` : ''}
            </div>
          </div>
          <input
            ref={inputRef}
            type="file"
            accept=".csv,.xlsx"
            style={{ display: 'none' }}
            onChange={onInputChange}
          />

          {success && (
            <div className="success-box" style={{ marginTop: 16 }}>
              Выгрузка «{success.fileName}» принята: {fmtNum(success.rowsCount)}{' '}
              {plural(Number(success.rowsCount), ['строка', 'строки', 'строк'])}, период{' '}
              {fmtDate(success.periodStart)} – {fmtDate(success.periodEnd)}.
            </div>
          )}

          {wizardError && (
            <div className="error-box" style={{ marginTop: 16 }}>
              <div className="t">{wizardError.message}</div>
              {wizardError.errors.length > 0 && (
                <ul>
                  {wizardError.errors.map((item, index) => <li key={index}>{item}</li>)}
                </ul>
              )}
            </div>
          )}

          {analysis && (
            <div className="analysis">
              <h3>Распознавание файла</h3>
              <div className="detect">
                <span className="detect-chip">формат <b>{analysis.format}</b></span>
                {analysis.delimiter && <span className="detect-chip">разделитель <b>{analysis.delimiter}</b></span>}
                <span className="detect-chip">кодировка <b>{analysis.charset}</b></span>
                {analysis.dateFormat && <span className="detect-chip">даты <b>{analysis.dateFormat}</b></span>}
                <span className="detect-chip">строк <b>{fmtNum(analysis.rowCount)}</b></span>
                {analysis.periodStart && analysis.periodEnd && (
                  <span className="detect-chip">период <b>{fmtDate(analysis.periodStart)} – {fmtDate(analysis.periodEnd)}</b></span>
                )}
              </div>

              <div className="mapping">
                {FIELDS.map(({ key, label }) => {
                  const detected = analysis.columns.find((column) => column.field === key)
                  return (
                    <div className="mrow" key={key}>
                      <span className="fname">
                        {label}
                        {detected?.method ? ` · ${detected.method}` : ''}
                      </span>
                      <Select
                        variant="compact"
                        value={mapping[key] === null || mapping[key] === undefined ? '' : String(mapping[key])}
                        placeholder="— не выбрано —"
                        options={analysis.columns.map((column) => ({
                          value: String(column.index),
                          label: column.sourceName?.trim() ? column.sourceName : `колонка ${column.index + 1}`,
                        }))}
                        onChange={(next) => setMapping({
                          ...mapping,
                          [key]: next === '' ? null : Number(next),
                        })}
                      />
                    </div>
                  )
                })}
              </div>

              {analysis.previewRows.length > 0 && (
                <div className="preview-table">
                  <table>
                    <thead>
                      <tr>
                        {analysis.columns.map((column) => (
                          <th key={column.index}>{column.sourceName?.trim() ? column.sourceName : `колонка ${column.index + 1}`}</th>
                        ))}
                      </tr>
                    </thead>
                    <tbody>
                      {analysis.previewRows.map((row, rowIndex) => (
                        <tr key={rowIndex}>
                          {analysis.columns.map((column) => (
                            <td key={column.index}>{row[column.index] ?? ''}</td>
                          ))}
                        </tr>
                      ))}
                    </tbody>
                  </table>
                </div>
              )}

              {analysis.warnings.length > 0 && (
                <div className="warns">
                  {analysis.warnings.map((warning, index) => <div className="w" key={index}>⚠ {warning}</div>)}
                </div>
              )}
              {analyzeBlocking && (
                <div className="errors" style={{ borderTop: 'none', paddingTop: 0, marginTop: 0, marginBottom: 12 }}>
                  {analysis.errors.map((item, index) => (
                    <div className="e" key={index}><span>!</span><span>{item}</span></div>
                  ))}
                </div>
              )}

              <div style={{ display: 'flex', gap: 10 }}>
                <button className="ghost-btn" onClick={resetWizard} disabled={committing}>Отмена</button>
                <button
                  className="cta"
                  onClick={commit}
                  disabled={committing || !mappingComplete}
                  title={mappingComplete ? '' : 'сопоставьте все колонки'}
                >
                  {committing ? 'Загружаем…' : 'Загрузить'}
                </button>
              </div>
            </div>
          )}

          <div className="format">
            <div className="t">Формат файла</div>
            <code>
              <b>date, impressions, clicks, cost, revenue</b>
              <br />
              2026-08-03, 7301, 298, 239.10, 266.56
              <br />
              2026-08-04, 6996, 298, 239.32, 297.26
            </code>
            <div className="hint">
              Шаблон не обязателен: названия колонок, разделитель, кодировка и формат даты распознаются автоматически,
              при необходимости сопоставьте колонки вручную. Одна строка — один день. Даты внутри кампании не должны
              повторяться, периоды выгрузок не должны пересекаться.
            </div>
          </div>
        </div>

        <div className="card">
          <div className="card-head">
            <div>
              <h2>История загрузок</h2>
              <div className="csub">по всем кампаниям</div>
            </div>
          </div>
          {history.length === 0
            ? <EmptyState>загрузок пока не было</EmptyState>
            : historyRows.map((upload) => (
              <div className="upl-card" key={upload.id}>
                <div className="top">
                  <div className="fic">{upload.fileFormat === 'XLSX' ? 'XLS' : 'CSV'}</div>
                  <div className="main">
                    <div className="t">{upload.fileName}</div>
                    <div className="upl-meta">
                      <span className="mi"><span className="mk">Кампания</span><b>{upload.campaignName}</b></span>
                      <span className="mi"><span className="mk">Загружено</span><b>{fmtDate(upload.uploadedAt)}</b></span>
                      <span className="mi"><span className="mk">Строк</span><b>{fmtNum(upload.rowsCount)}</b></span>
                    </div>
                  </div>
                  <span className="st-pill ok">принят</span>
                </div>
                {upload.periodStart && upload.periodEnd && (
                  <div className="upl-period">Данные за период: {fmtDate(upload.periodStart)} — {fmtDate(upload.periodEnd)}</div>
                )}
              </div>
            ))}
          {history.length > 0 && (
            <div className="table-foot">
              <span className="rows-range">
                {selectedCampaign
                  ? `${rangeLabel(historyCurrent, HISTORY_PAGE_SIZE, historyRows.length, history.length, ['выгрузка', 'выгрузки', 'выгрузок'])} — отклонённые файлы не сохраняются`
                  : rangeLabel(historyCurrent, HISTORY_PAGE_SIZE, historyRows.length, history.length, ['выгрузка', 'выгрузки', 'выгрузок'])}
              </span>
              <Pager page={historyCurrent} pageCount={historyPages} onChange={setHistoryPage} />
            </div>
          )}
        </div>
      </div>
    </Layout>
  )
}
