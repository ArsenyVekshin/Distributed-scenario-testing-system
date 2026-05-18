import { useState, useEffect } from 'react'
import { Link, useNavigate } from 'react-router-dom'
import { api } from '../api/client'
import type { Project, CreateProjectRequest } from '../types'

export function Projects() {
  const [projects, setProjects]     = useState<Project[]>([])
  const [loading, setLoading]       = useState(true)
  const [showForm, setShowForm]     = useState(false)
  const [submitting, setSubmitting] = useState(false)
  const [error, setError]           = useState<string | null>(null)
  const navigate = useNavigate()

  const [form, setForm] = useState<CreateProjectRequest>({
    name: '',
    gitlabUrl: '',
    branch: 'main',
    accessToken: '',
    scenarioConfigPath: 'scenario.dst.yaml',
    dockerfilePath: 'Dockerfile',
  })

  useEffect(() => { load() }, [])

  async function load() {
    setLoading(true)
    try {
      setProjects(await api.projects.list())
    } catch (e: any) {
      setError(e.message)
    } finally {
      setLoading(false)
    }
  }

  async function handleCreate(e: React.FormEvent) {
    e.preventDefault()
    setSubmitting(true)
    setError(null)
    try {
      const created = await api.projects.create(form)
      setShowForm(false)
      setForm({ name: '', gitlabUrl: '', branch: 'main', accessToken: '', scenarioConfigPath: 'scenario.dst.yaml', dockerfilePath: 'Dockerfile' })
      navigate(`/projects/${created.id}`)
    } catch (e: any) {
      setError(e.message)
    } finally {
      setSubmitting(false)
    }
  }

  async function handleDelete(id: string, name: string) {
    if (!confirm(`Удалить проект "${name}"?`)) return
    try {
      await api.projects.delete(id)
      setProjects(p => p.filter(x => x.id !== id))
    } catch (e: any) {
      setError(e.message)
    }
  }

  if (loading) return <div className="page"><p className="muted">Загрузка…</p></div>

  return (
    <div className="page">
      <div className="page-header">
        <h1 className="page-title">Проекты</h1>
        <button className="btn btn-primary" onClick={() => setShowForm(v => !v)}>
          {showForm ? 'Отмена' : '+ Новый проект'}
        </button>
      </div>

      {error && <div className="error-banner">{error}</div>}

      {showForm && (
        <form className="card form-card" onSubmit={handleCreate}>
          <h2 className="card-title">Новый проект</h2>
          <div className="form-grid">
            <label>Название
              <input value={form.name} onChange={e => setForm(f => ({ ...f, name: e.target.value }))} required />
            </label>
            <label>GitLab URL
              <input value={form.gitlabUrl} onChange={e => setForm(f => ({ ...f, gitlabUrl: e.target.value }))}
                placeholder="https://gitlab.com/group/project.git" required />
            </label>
            <label>Ветка
              <input value={form.branch} onChange={e => setForm(f => ({ ...f, branch: e.target.value }))} required />
            </label>
            <label>Access Token
              <input type="password" value={form.accessToken}
                onChange={e => setForm(f => ({ ...f, accessToken: e.target.value }))} required />
            </label>
            <label>Путь к scenario.dst.yaml
              <input value={form.scenarioConfigPath}
                onChange={e => setForm(f => ({ ...f, scenarioConfigPath: e.target.value }))} />
            </label>
            <label>Путь к Dockerfile
              <input value={form.dockerfilePath}
                onChange={e => setForm(f => ({ ...f, dockerfilePath: e.target.value }))} />
            </label>
          </div>
          <div className="form-actions">
            <button type="submit" className="btn btn-primary" disabled={submitting}>
              {submitting ? 'Создание…' : 'Создать'}
            </button>
          </div>
        </form>
      )}

      {projects.length === 0 ? (
        <div className="empty-state">
          <p>Нет проектов. Создайте первый проект для начала работы.</p>
        </div>
      ) : (
        <div className="card-list">
          {projects.map(p => (
            <div key={p.id} className="card project-card">
              <div className="project-card-header">
                <Link to={`/projects/${p.id}`} className="project-name">{p.name}</Link>
                <button className="btn btn-danger btn-sm" onClick={() => handleDelete(p.id, p.name)}>Удалить</button>
              </div>
              <div className="project-meta">
                <span className="meta-item">{p.gitlabUrl}</span>
                <span className="meta-separator">·</span>
                <span className="meta-item">ветка: <strong>{p.branch}</strong></span>
                <span className="meta-separator">·</span>
                <span className="meta-item muted">{p.accessTokenMasked}</span>
              </div>
            </div>
          ))}
        </div>
      )}
    </div>
  )
}
