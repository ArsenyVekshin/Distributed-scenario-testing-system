import { useEffect, useState } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import { Badge } from '../components/Badge'
import type { ScenarioDetail as TScenarioDetail, BlockDetail } from '../types'

function fmtMs(ms?: number) {
  if (ms == null) return '—'
  if (ms < 1000) return `${ms}ms`
  return `${(ms / 1000).toFixed(1)}s`
}

// ─── DAG: compute execution levels via Kahn's algorithm ──────────────────────
function computeLevels(blocks: BlockDetail[]): BlockDetail[][] {
  const idMap = Object.fromEntries(blocks.map(b => [b.id, b]))
  const inDeg = Object.fromEntries(blocks.map(b => [b.id, b.dependsOn.length]))
  const levels: BlockDetail[][] = []
  let queue = blocks.filter(b => inDeg[b.id] === 0)
  while (queue.length) {
    levels.push(queue)
    const next: BlockDetail[] = []
    for (const b of queue) {
      for (const other of blocks) {
        if (other.dependsOn.includes(b.id)) {
          inDeg[other.id]--
          if (inDeg[other.id] === 0) next.push(idMap[other.id])
        }
      }
    }
    queue = next
  }
  return levels
}

// ─── Block card in the DAG ────────────────────────────────────────────────────
function BlockCard({ block, selected, onClick }: {
  block: BlockDetail
  selected: boolean
  onClick: () => void
}) {
  const pct = block.totalTests > 0
    ? Math.round((block.passedTests / block.totalTests) * 100)
    : 0

  return (
    <button className={`block-card ${block.status.toLowerCase()} ${selected ? 'selected' : ''}`} onClick={onClick}>
      <div className="block-card-name">{block.name}</div>
      <Badge status={block.status} small />
      <div className="block-card-meta">
        <span>{block.totalTests} тестов</span>
        {block.durationMs != null && <span>{fmtMs(block.durationMs)}</span>}
      </div>
      {block.totalTests > 0 && (
        <div className="progress-bar">
          <div className="progress-fill" style={{ width: `${pct}%`, background: block.status === 'FAILED' ? 'var(--danger)' : 'var(--success)' }} />
        </div>
      )}
      {block.nodeId && <div className="block-card-node">{block.nodeId}</div>}
    </button>
  )
}

// ─── Main page ────────────────────────────────────────────────────────────────
export function ScenarioDetail() {
  const { id }                              = useParams<{ id: string }>()
  const navigate                            = useNavigate()
  const [scenario, setScenario]             = useState<TScenarioDetail | null>(null)
  const [loading,  setLoading]              = useState(true)
  const [selected, setSelected]             = useState<string | null>(null)
  const [actionBusy, setActionBusy]         = useState(false)

  useEffect(() => {
    let cancelled = false
    api.scenarios
      .get(id!)
      .then(data => {
        if (!cancelled) {
          setScenario(data)
          setLoading(false)
        }
      })
      .catch(() => {
        if (!cancelled) {
          setScenario(null)
          setLoading(false)
        }
      })
    return () => {
      cancelled = true
    }
  }, [id])

  if (loading) return <div className="page-loading">Загрузка…</div>
  if (!scenario) return (
    <div className="page">
      <p className="error-text">Сценарий не найден.</p>
      <Link to="/scenarios" className="btn btn-ghost" style={{ marginTop: 16 }}>← Назад</Link>
    </div>
  )

  const levels   = computeLevels(scenario.blocks)
  const selBlock = scenario.blocks.find(b => b.id === selected)

  async function handleStart() {
    setActionBusy(true)
    try {
      await api.scenarios.start(scenario!.id)
      const fresh = await api.scenarios.get(scenario!.id)
      setScenario(fresh)
    } finally {
      setActionBusy(false)
    }
  }

  async function handleStop() {
    setActionBusy(true)
    try {
      await api.scenarios.stop(scenario!.id)
      const fresh = await api.scenarios.get(scenario!.id)
      setScenario(fresh)
    } finally {
      setActionBusy(false)
    }
  }

  const progress = scenario.totalBlocks > 0
    ? Math.round((scenario.completedBlocks / scenario.totalBlocks) * 100)
    : 0

  return (
    <div className="page page-wide">
      {/* Header */}
      <div className="page-header">
        <div>
          <div className="breadcrumb">
            <button className="btn-link" onClick={() => navigate('/scenarios')}>Сценарии</button>
            <span className="breadcrumb-sep">/</span>
            <span>{scenario.name}</span>
          </div>
          <h1 className="page-title">{scenario.name}</h1>
          {scenario.description && <p className="page-subtitle">{scenario.description}</p>}
        </div>
        <div className="header-actions">
          <Badge status={scenario.status} />
          {(scenario.status === 'DRAFT' || scenario.status === 'STOPPED' || scenario.status === 'COMPLETED' || scenario.status === 'FAILED') && (
            <button className="btn btn-primary" onClick={handleStart} disabled={actionBusy}>
              ▶ Запустить
            </button>
          )}
          {scenario.status === 'RUNNING' && (
            <button className="btn btn-danger" onClick={handleStop} disabled={actionBusy}>
              ■ Остановить
            </button>
          )}
        </div>
      </div>

      {/* Info row */}
      <div className="info-row">
        {scenario.workflowRunId && (
          <div className="info-cell">
            <span className="info-label">Workflow ID</span>
            <code className="info-value">{scenario.workflowRunId.slice(0, 18)}…</code>
          </div>
        )}
        <div className="info-cell">
          <span className="info-label">Блоков</span>
          <span className="info-value">{scenario.completedBlocks} / {scenario.totalBlocks}</span>
        </div>
        <div className="info-cell">
          <span className="info-label">Прогресс</span>
          <span className="info-value">{progress}%</span>
        </div>
      </div>

      {/* DAG */}
      <section className="section">
        <h2 className="section-title">Граф выполнения</h2>
        <p className="section-hint">Нажмите на блок для просмотра тестов</p>
        <div className="dag">
          {levels.map((level, li) => (
            <div key={li} className="dag-col-group">
              <div className="dag-col">
                {level.map(block => (
                  <BlockCard
                    key={block.id}
                    block={block}
                    selected={selected === block.id}
                    onClick={() => setSelected(selected === block.id ? null : block.id)}
                  />
                ))}
              </div>
              {li < levels.length - 1 && (
                <div className="dag-arrow">→</div>
              )}
            </div>
          ))}
        </div>
      </section>

      {/* Selected block detail */}
      {selBlock && (
        <section className="section">
          <div className="section-header">
            <h2 className="section-title">
              Блок: {selBlock.name}
              <Badge status={selBlock.status} small />
            </h2>
            <div className="text-muted text-sm">
              Параллелизм: {selBlock.parallelism} · {fmtMs(selBlock.durationMs)}
              {selBlock.nodeId && ` · ${selBlock.nodeId}`}
            </div>
          </div>
          <div className="table-wrap">
            <table>
              <thead>
                <tr><th>Тест</th><th>Статус</th><th>Время</th><th>Вывод / Ошибка</th></tr>
              </thead>
              <tbody>
                {selBlock.tests.map(t => (
                  <tr key={t.id}>
                    <td>{t.name}<div className="text-muted text-sm mono">{t.id}</div></td>
                    <td><Badge status={t.status} small /></td>
                    <td className="text-sm">{fmtMs(t.durationMs)}</td>
                    <td className="text-sm">
                      {t.error  && <span className="error-text">{t.error}</span>}
                      {t.output && !t.error && <code className="output-text">{t.output}</code>}
                    </td>
                  </tr>
                ))}
              </tbody>
            </table>
          </div>
        </section>
      )}

      {/* Config YAML */}
      {scenario.configYaml && (
        <section className="section">
          <h2 className="section-title">Конфигурация (YAML)</h2>
          <pre className="yaml-block">{scenario.configYaml}</pre>
        </section>
      )}
    </div>
  )
}
