import type {
  NodeInfo, ScenarioDetail, ScenarioSummary,
  Project, CreateProjectRequest,
  RunDetail, RunSummary,
} from '../types'

/**
 * Базовый URL API. В dev с Vite-прокси оставьте пустым (запросы идут на тот же origin, `/api` → coordinator).
 * Для прямого обращения к бекенду: `VITE_API_BASE=http://localhost:8080`.
 */
const API_PREFIX = import.meta.env.VITE_API_BASE ?? ''

function url(path: string): string {
  return `${API_PREFIX}${path}`
}

async function readErrorMessage(res: Response): Promise<string> {
  const text = await res.text()
  if (!text) return res.statusText || `HTTP ${res.status}`
  try {
    const body = JSON.parse(text) as { message?: string; errors?: { path: string; message: string }[] }
    const base = typeof body.message === 'string' ? body.message : text
    if (Array.isArray(body.errors) && body.errors.length) {
      const lines = body.errors.map(e => `${e.path}: ${e.message}`)
      return `${base}: ${lines.join('; ')}`
    }
    return base
  } catch {
    return text
  }
}

async function jsonFetch<T>(path: string, init?: RequestInit): Promise<T> {
  const res = await fetch(url(path), {
    ...init,
    headers: {
      Accept: 'application/json',
      ...(init?.body ? { 'Content-Type': 'application/json' } : {}),
      ...init?.headers,
    },
  })
  if (!res.ok) throw new Error(await readErrorMessage(res))
  if (res.status === 204) return undefined as T
  return (await res.json()) as T
}

export const api = {
  scenarios: {
    list: () => jsonFetch<ScenarioSummary[]>('/api/scenarios'),

    get: (id: string) =>
      jsonFetch<ScenarioDetail>(`/api/scenarios/${encodeURIComponent(id)}/detail`),

    create: (yaml: string) =>
      jsonFetch<ScenarioSummary>('/api/scenarios', {
        method: 'POST',
        body: JSON.stringify({ configYaml: yaml }),
      }),

    start: (id: string) =>
      jsonFetch<{ scenarioId: string; workflowRunId: string; status: string; startedAt: string }>(
        `/api/scenarios/${encodeURIComponent(id)}/start`,
        { method: 'POST' }
      ),

    stop: (id: string) =>
      jsonFetch<void>(`/api/scenarios/${encodeURIComponent(id)}/stop`, { method: 'POST' }),

    delete: (id: string) =>
      jsonFetch<void>(`/api/scenarios/${encodeURIComponent(id)}`, { method: 'DELETE' }),
  },

  nodes: {
    list: () => jsonFetch<NodeInfo[]>('/api/nodes'),
  },

  projects: {
    list: () => jsonFetch<Project[]>('/api/projects'),

    get: (id: string) =>
      jsonFetch<Project>(`/api/projects/${encodeURIComponent(id)}`),

    create: (request: CreateProjectRequest) =>
      jsonFetch<Project>('/api/projects', {
        method: 'POST',
        body: JSON.stringify(request),
      }),

    update: (id: string, request: Partial<CreateProjectRequest>) =>
      jsonFetch<Project>(`/api/projects/${encodeURIComponent(id)}`, {
        method: 'PUT',
        body: JSON.stringify(request),
      }),

    delete: (id: string) =>
      jsonFetch<void>(`/api/projects/${encodeURIComponent(id)}`, { method: 'DELETE' }),

    startRun: (projectId: string) =>
      jsonFetch<{ id: string; projectId: string; status: string; branch: string; startedAt: string }>(
        `/api/projects/${encodeURIComponent(projectId)}/runs`,
        { method: 'POST' }
      ),

    runs: (projectId: string) =>
      jsonFetch<RunSummary[]>(`/api/projects/${encodeURIComponent(projectId)}/runs`),
  },

  runs: {
    get: (runId: string) =>
      jsonFetch<RunDetail>(`/api/runs/${encodeURIComponent(runId)}`),

    cancel: (runId: string) =>
      jsonFetch<void>(`/api/runs/${encodeURIComponent(runId)}/cancel`, { method: 'POST' }),
  },
}
