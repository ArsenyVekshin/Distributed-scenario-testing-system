import { useState, useEffect } from 'react'
import { useParams, Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Project, RunSummary } from '../types'
import { Badge } from '../components/Badge'

export function ProjectDetail() {
  const { id } = useParams<{ id: string }>()
  const navigate = useNavigate()

  const [project, setProject]   = useState<Project | null>(null)
  const [runs, setRuns]         = useState<RunSummary[]>([])
  const [loading, setLoading]   = useState(true)
  const [starting, setStarting] = useState(false)
  const [error, setError]       = useState<string | null>(null)

  useEffect(() => {
    if (!id) return
    load()
  }, [id])

  async function load() {
    setLoading(true)
    try {
      const [p, r] = await Promise.all([
        api.projects.get(id!),
        api.projects.runs(id!),
      ])
      setProject(p)
      setRuns(r)
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function handleStartRun() {
    setStarting(true)
    setError(null)
    try {
      const run = await api.projects.startRun(id!)
      navigate(`/runs/${run.id}`)
    } catch (e: any) {
      setError(e.message)
      setStarting(false)
    }
  }

  if (loading) return <div className="page"><p className="muted">Загрузка…</p></div>
  if (!project) return <div className="page"><p className="muted">Проект не найден</p></div>

  return (
    <div className="page">
      <div className="page-header">
        <div>
          <Link to="/projects" className="breadcrumb">← Проекты</Link>
          <h1 className="page-title">{project.name}</h1>
          <p className="muted">{project.gitlabUrl} · ветка <strong>{project.branch}</strong></p>
        </div>
        <button className="btn btn-primary" onClick={handleStartRun} disabled={starting}>
          {starting ? 'Запуск…' : '▶ Запустить прогон'}
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      <section className="section">
        <h2 className="section-title">История прогонов</h2>
        {runs.length === 0 ? (
          <div className="empty-state">
            <p>Нет прогонов. Запустите первый прогон, нажав кнопку выше.</p>
          </div>
        ) : (
          <table className="data-table">
            <thead>
              <tr>
                <th>ID</th>
                <th>Статус</th>
                <th>Ветка</th>
                <th>Commit</th>
                <th>Блоки</th>
                <th>Тесты</th>
                <th>Начало</th>
                <th>Длит.</th>
              </tr>
            </thead>
            <tbody>
              {runs.map(r => (
                <tr key={r.id}>
                  <td><Link to={`/runs/${r.id}`} className="link-mono">{r.id.slice(0, 8)}</Link></td>
                  <td><Badge status={r.status} /></td>
                  <td>{r.branch}</td>
                  <td className="mono">{r.commitSha?.slice(0, 7) ?? '—'}</td>
                  <td>{r.blocksSuccess}/{r.blocksTotal}</td>
                  <td>{r.testsPassed}/{r.testsTotal}</td>
                  <td className="muted">{r.startedAt ? new Date(r.startedAt).toLocaleString('ru') : '—'}</td>
                  <td className="muted">{r.durationMs != null ? `${(r.durationMs / 1000).toFixed(1)}s` : '—'}</td>
                </tr>
              ))}
            </tbody>
          </table>
        )}
      </section>
    </div>
  )
}
