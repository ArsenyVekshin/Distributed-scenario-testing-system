import { useEffect, useState } from 'react'
import { api } from '../api/client'
import { Badge } from '../components/Badge'
import type { NodeInfo } from '../types'

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('ru', {
    day: '2-digit',
    month: '2-digit',
    hour: '2-digit',
    minute: '2-digit',
  })
}

function fmtOptionalDate(iso?: string) {
  return iso ? fmtDate(iso) : '—'
}

export function Nodes() {
  const [nodes, setNodes] = useState<NodeInfo[]>([])
  const [loading, setLoading] = useState(true)
  const [error, setError] = useState<string | null>(null)

  const loadNodes = () => {
    setError(null)
    return api.nodes.list()
      .then(setNodes)
      .catch(e => setError(e instanceof Error ? e.message : String(e)))
      .finally(() => setLoading(false))
  }

  useEffect(() => {
    loadNodes()
  }, [])

  if (loading) return <div className="page-loading">Загрузка...</div>

  const online = nodes.filter(n => n.status !== 'OFFLINE').length
  const busy = nodes.filter(n => n.status === 'BUSY').length
  const idle = nodes.filter(n => n.status === 'IDLE').length

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <h1 className="page-title">Вычислительные узлы</h1>
          <span className="page-subtitle">Temporal workers, обрабатывающие build и test activities</span>
        </div>
        <button
          className="btn btn-ghost"
          onClick={loadNodes}
        >
          ↻ Обновить
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="cards-grid">
        <div className="card">
          <div className="card-label">Всего</div>
          <div className="card-value">{nodes.length}</div>
        </div>
        <div className="card">
          <div className="card-label">Онлайн</div>
          <div className="card-value card-value--green">{online}</div>
        </div>
        <div className="card">
          <div className="card-label">Занято</div>
          <div className="card-value card-value--blue">{busy}</div>
        </div>
        <div className="card">
          <div className="card-label">Свободно</div>
          <div className="card-value">{idle}</div>
        </div>
      </div>

      <div className="table-wrap">
        <table>
          <thead>
            <tr>
              <th>ID агента</th>
              <th>Адрес</th>
              <th>Статус</th>
              <th>Текущая работа</th>
              <th>Heartbeat</th>
              <th>Зарегистрирован</th>
            </tr>
          </thead>
          <tbody>
            {nodes.length === 0 ? (
              <tr>
                <td colSpan={6} className="table-empty">
                  Нет зарегистрированных агентов
                </td>
              </tr>
            ) : (
              nodes.map(n => (
                <tr key={n.id} className={n.status === 'OFFLINE' ? 'row-muted' : ''}>
                  <td><code>{n.id}</code></td>
                  <td className="text-sm text-muted">{n.address}</td>
                  <td><Badge status={n.status} /></td>
                  <td className="text-sm">{n.currentBlockId ?? <span className="text-muted">—</span>}</td>
                  <td className="text-sm text-muted">{fmtOptionalDate(n.lastHeartbeatAt)}</td>
                  <td className="text-sm text-muted">{fmtDate(n.registeredAt)}</td>
                </tr>
              ))
            )}
          </tbody>
        </table>
      </div>

      <div className="info-box">
        <strong>Масштабирование агентов:</strong>
        <span> Запустите несколько агентов командой </span>
        <code>docker compose up --scale agent=3</code>
        <span> — каждый агент будет отправлять heartbeat в coordinator, а Temporal распределит задачи между workers.</span>
      </div>
    </div>
  )
}
