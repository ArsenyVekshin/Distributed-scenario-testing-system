import { useEffect, useState } from 'react'
import { Link } from 'react-router-dom'
import { api } from '../api/client'
import { Badge } from '../components/Badge'
import type { ScenarioSummary, NodeInfo, Project, RunSummary } from '../types'

function fmtDate(iso: string) {
  return new Date(iso).toLocaleString('ru', { day: '2-digit', month: '2-digit', hour: '2-digit', minute: '2-digit' })
}

export function Dashboard() {
  const [scenarios, setScenarios] = useState<ScenarioSummary[]>([])
  const [nodes,     setNodes]     = useState<NodeInfo[]>([])
  const [projects,  setProjects]  = useState<Project[]>([])
  const [runs,      setRuns]      = useState<RunSummary[]>([])
  const [loading,   setLoading]   = useState(true)

  useEffect(() => {
    Promise.all([api.scenarios.list(), api.nodes.list(), api.projects.list()]).then(async ([s, n, p]) => {
      const projectRuns = await Promise.all(p.map(project => api.projects.runs(project.id)))
      setScenarios(s)
      setNodes(n)
      setProjects(p)
      setRuns(projectRuns.flat())
      setLoading(false)
    })
  }, [])

  if (loading) return <div className="page-loading">Загрузка…</div>

  const running = runs.filter(r => r.status === 'PENDING' || r.status === 'BUILDING' || r.status === 'RUNNING').length
  const failed = runs.filter(r => r.status === 'FAILED' || r.status === 'TIMEOUT' || r.status === 'CANCELED').length
  const completed = runs.filter(r => r.status === 'SUCCESS').length
  const nodesOnline = nodes.filter(n => n.status !== 'OFFLINE').length

  return (
    <div className="page">
      <div className="page-header">
        <h1 className="page-title">Дашборд</h1>
        <span className="page-subtitle">Обзор системы в реальном времени</span>
      </div>

      {/* Summary cards */}
      <div className="cards-grid">
        <div className="card">
          <div className="card-label">Проектов</div>
          <div className="card-value">{projects.length}</div>
        </div>
        <div className="card">
          <div className="card-label">Прогонов</div>
          <div className="card-value">{runs.length}</div>
        </div>
        <div className="card">
          <div className="card-label">В работе</div>
          <div className="card-value card-value--blue">{running}</div>
        </div>
        <div className="card">
          <div className="card-label">Успешно</div>
          <div className="card-value card-value--green">{completed}</div>
        </div>
        <div className="card">
          <div className="card-label">С ошибками</div>
          <div className="card-value card-value--red">{failed}</div>
        </div>
        <div className="card">
          <div className="card-label">Активных узлов</div>
          <div className="card-value card-value--green">{nodesOnline} / {nodes.length}</div>
        </div>
      </div>

      {/* Recent scenarios */}
      <section className="section">
        <div className="section-header">
          <h2 className="section-title">Последние сценарии</h2>
          <Link to="/scenarios" className="section-link">Все сценарии →</Link>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>Сценарий</th>
                <th>Блоков</th>
                <th>Статус</th>
                <th>Создан</th>
              </tr>
            </thead>
            <tbody>
              {scenarios.slice(0, 5).map(s => (
                <tr key={s.id}>
                  <td>
                    <Link to={`/scenarios/${s.id}`} className="table-link">{s.name}</Link>
                    {s.description && <div className="text-muted text-sm">{s.description}</div>}
                  </td>
                  <td>{s.blockCount}</td>
                  <td><Badge status={s.status} /></td>
                  <td className="text-muted text-sm">{fmtDate(s.createdAt)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>

      {/* Nodes */}
      <section className="section">
        <div className="section-header">
          <h2 className="section-title">Вычислительные узлы</h2>
          <Link to="/nodes" className="section-link">Все узлы →</Link>
        </div>
        <div className="table-wrap">
          <table>
            <thead>
              <tr>
                <th>ID</th>
                <th>Адрес</th>
                <th>Статус</th>
                <th>Текущий блок</th>
              </tr>
            </thead>
            <tbody>
              {nodes.map(n => (
                <tr key={n.id}>
                  <td><code>{n.id}</code></td>
                  <td className="text-muted text-sm">{n.address}</td>
                  <td><Badge status={n.status} /></td>
                  <td className="text-muted text-sm">{n.currentBlockId ?? '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      </section>
    </div>
  )
}
