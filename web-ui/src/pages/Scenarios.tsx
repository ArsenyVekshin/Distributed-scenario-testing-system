import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { Badge } from '../components/Badge'
import type { ScenarioStatus, ScenarioSummary } from '../types'

const EXAMPLE_YAML = `name: "My Test Suite"
description: "Описание сценария"

blocks:
  - id: prepare
    name: "Preparation"
    tests:
      - id: init-db
        name: "Initialize DB"
        command: "python scripts/init_db.py"

  - id: unit
    name: "Unit Tests"
    dependsOn: [prepare]
    parallelism: 4
    tests:
      - id: test-core
        name: "Core tests"
        command: "pytest tests/unit/"
        timeoutSeconds: 120

  - id: e2e
    name: "E2E Tests"
    dependsOn: [unit]
    tests:
      - id: test-e2e
        name: "End-to-end"
        command: "pytest tests/e2e/"
        timeoutSeconds: 300`

const FILTER_OPTIONS: { value: ScenarioStatus | 'ALL'; label: string }[] = [
  { value: 'ALL',       label: 'Все' },
  { value: 'RUNNING',   label: 'Выполняется' },
  { value: 'COMPLETED', label: 'Завершён' },
  { value: 'FAILED',    label: 'Ошибка' },
  { value: 'DRAFT',     label: 'Черновик' },
  { value: 'STOPPED',   label: 'Остановлен' },
]

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('ru', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

export function Scenarios() {
  const [scenarios, setScenarios] = useState<ScenarioSummary[]>([])
  const [loading,   setLoading]   = useState(true)
  const [filter,    setFilter]    = useState<ScenarioStatus | 'ALL'>('ALL')
  const [showModal, setShowModal] = useState(false)

  useEffect(() => {
    api.scenarios.list().then(data => { setScenarios(data); setLoading(false) })
  }, [])

  const filtered = filter === 'ALL' ? scenarios : scenarios.filter(s => s.status === filter)

  async function handleDelete(id: string) {
    if (!confirm('Удалить сценарий?')) return
    await api.scenarios.delete(id)
    setScenarios(prev => prev.filter(s => s.id !== id))
  }

  if (loading) return <div className="page-loading">Загрузка…</div>

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Сценарии</h1>
          <span className="page-subtitle">{scenarios.length} сценариев в системе</span>
        </div>
        <button className="btn btn-primary" onClick={() => setShowModal(true)}>
          + Новый сценарий
        </button>
      </div>

      {/* Filter bar */}
      <div className="filter-bar">
        {FILTER_OPTIONS.map(opt => (
          <button
            key={opt.value}
            className={`filter-btn ${filter === opt.value ? 'active' : ''}`}
            onClick={() => setFilter(opt.value)}
          >
            {opt.label}
            {opt.value !== 'ALL' && (
              <span className="filter-count">
                {scenarios.filter(s => s.status === opt.value).length}
              </span>
            )}
          </button>
        ))}
      </div>

      {/* Table */}
      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>Сценарий</th>
              <th>Блоков</th>
              <th>Статус</th>
              <th>Создан</th>
              <th></th>
            </tr>
          </thead>
          <tbody>
            {filtered.length === 0 && (
              <tr><td colSpan={5} className="table-empty">Нет сценариев</td></tr>
            )}
            {filtered.map(s => (
              <tr key={s.id}>
                <td>
                  <Link to={`/scenarios/${s.id}`} className="table-link">{s.name}</Link>
                  {s.description && <div className="text-muted text-sm">{s.description}</div>}
                </td>
                <td>{s.blockCount}</td>
                <td><Badge status={s.status} /></td>
                <td className="text-muted text-sm">{fmtDate(s.createdAt)}</td>
                <td className="row-actions">
                  <Link to={`/scenarios/${s.id}`} className="btn btn-ghost btn-sm">Открыть</Link>
                  {s.status === 'DRAFT' && (
                    <button className="btn btn-danger btn-sm" onClick={() => handleDelete(s.id)}>
                      Удалить
                    </button>
                  )}
                </td>
              </tr>
            ))}
          </tbody>
        </table>
      </div>

      {/* Create modal */}
      {showModal && (
        <CreateModal
          onClose={() => setShowModal(false)}
          onCreate={s => { setScenarios(prev => [s, ...prev]); setShowModal(false) }}
        />
      )}
    </div>
  )
}

// ─── Create modal ─────────────────────────────────────────────────────────────

function CreateModal({ onClose, onCreate }: {
  onClose: () => void
  onCreate: (s: ScenarioSummary) => void
}) {
  const [yaml,    setYaml]    = useState(EXAMPLE_YAML)
  const [saving,  setSaving]  = useState(false)
  const [error,   setError]   = useState<string | null>(null)

  async function handleSubmit(e: React.FormEvent) {
    e.preventDefault()
    if (!yaml.trim()) { setError('YAML не может быть пустым'); return }
    setSaving(true)
    setError(null)
    try {
      const created = await api.scenarios.create(yaml)
      onCreate(created)
    } catch (err) {
      setError(String(err))
      setSaving(false)
    }
  }

  return (
    <div className="modal-overlay" onClick={onClose}>
      <div className="modal" onClick={e => e.stopPropagation()}>
        <div className="modal-header">
          <h2>Новый сценарий</h2>
          <button className="modal-close" onClick={onClose}>✕</button>
        </div>
        <form onSubmit={handleSubmit}>
          <div className="modal-body">
            <label className="field-label">YAML-конфигурация</label>
            <textarea
              className="code-editor"
              value={yaml}
              onChange={e => setYaml(e.target.value)}
              rows={22}
              spellCheck={false}
            />
            {error && <p className="error-text">{error}</p>}
          </div>
          <div className="modal-footer">
            <button type="button" className="btn btn-ghost" onClick={onClose}>Отмена</button>
            <button type="submit" className="btn btn-primary" disabled={saving}>
              {saving ? 'Создаём…' : 'Создать'}
            </button>
          </div>
        </form>
      </div>
    </div>
  )
}
