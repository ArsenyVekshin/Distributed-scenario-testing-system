import { useState, useEffect, useRef } from 'react'
import { useParams, Link } from 'react-router-dom'
import { api } from '../api/client'
import type { RunDetail as RunDetailType, BlockRun, TestRun } from '../types'
import { Badge } from '../components/Badge'

const TERMINAL_STATUSES = ['SUCCESS', 'FAILED', 'CANCELED', 'TIMEOUT']
const POLL_INTERVAL_MS = 2000

type RunView = 'logs' | 'graph' | 'build' | 'timing'

const GRAPH_NODE_WIDTH = 210
const GRAPH_NODE_HEIGHT = 76
const GRAPH_COLUMN_GAP = 132
const GRAPH_ROW_GAP = 22
const GRAPH_PADDING = 24

export function RunDetail() {
  const { runId } = useParams<{ runId: string }>()
  const [run, setRun] = useState<RunDetailType | null>(null)
  const [loading, setLoading] = useState(true)
  const [canceling, setCanceling] = useState(false)
  const [error, setError] = useState<string | null>(null)
  const [expandedBlocks, setExpandedBlocks] = useState<Set<string>>(new Set())
  const [expandedTests, setExpandedTests] = useState<Set<string>>(new Set())
  const [activeView, setActiveView] = useState<RunView>('logs')
  const pollRef = useRef<number | null>(null)

  useEffect(() => {
    if (!runId) return
    fetchRun()
    return () => {
      if (pollRef.current) clearInterval(pollRef.current)
    }
  }, [runId])

  async function fetchRun() {
    try {
      const data = await api.runs.get(runId!)
      setRun(data)
      const details = problemDetailIds(data)
      if (details.blocks.length > 0) {
        setExpandedBlocks(current => new Set([...current, ...details.blocks]))
      }
      if (details.tests.length > 0) {
        setExpandedTests(current => new Set([...current, ...details.tests]))
      }
      setLoading(false)
      if (TERMINAL_STATUSES.includes(data.status)) {
        if (pollRef.current) clearInterval(pollRef.current)
      } else if (!pollRef.current) {
        pollRef.current = setInterval(fetchRun, POLL_INTERVAL_MS) as unknown as number
      }
    } catch (e: any) {
      setError(e.message)
      setLoading(false)
    }
  }

  async function handleCancel() {
    if (!run || !confirm('Отменить прогон?')) return
    setCanceling(true)
    try {
      await api.runs.cancel(runId!)
      fetchRun()
    } catch (e: any) {
      setError(e.message)
    } finally {
      setCanceling(false)
    }
  }

  function toggleBlock(blockId: string) {
    setExpandedBlocks(current => {
      const next = new Set(current)
      next.has(blockId) ? next.delete(blockId) : next.add(blockId)
      return next
    })
  }

  function toggleTest(testId: string) {
    setExpandedTests(current => {
      const next = new Set(current)
      next.has(testId) ? next.delete(testId) : next.add(testId)
      return next
    })
  }

  function openBlockLogs(blockId: string) {
    setActiveView('logs')
    setExpandedBlocks(current => new Set([...current, blockId]))
    window.setTimeout(() => {
      document.getElementById(`block-${blockId}`)?.scrollIntoView({ behavior: 'smooth', block: 'start' })
    }, 0)
  }

  if (loading) return <div className="page"><p className="muted">Загрузка...</p></div>
  if (!run) return <div className="page"><p className="muted">Прогон не найден</p></div>

  const isTerminal = TERMINAL_STATUSES.includes(run.status)
  const s = run.summary

  return (
    <div className="page page-wide">
      <div className="page-header">
        <div>
          <Link to={`/projects/${run.projectId}`} className="breadcrumb">← Проект</Link>
          <h1 className="page-title">
            Прогон <span className="mono">{run.id.slice(0, 8)}</span>
          </h1>
          <div className="run-meta">
            <Badge status={run.status} />
            <span className="meta-separator">·</span>
            <span className="muted">ветка: <strong>{run.branch}</strong></span>
            {run.commitSha && (
              <>
                <span className="meta-separator">·</span>
                <span className="mono muted">{run.commitSha.slice(0, 7)}</span>
              </>
            )}
            {run.durationMs != null && (
              <>
                <span className="meta-separator">·</span>
                <span className="muted">{(run.durationMs / 1000).toFixed(1)}s</span>
              </>
            )}
          </div>
        </div>
        {!isTerminal && (
          <button className="btn btn-danger" onClick={handleCancel} disabled={canceling}>
            {canceling ? 'Отмена...' : '⏹ Отменить'}
          </button>
        )}
      </div>

      {error && <div className="error-banner">{error}</div>}

      <div className="counters-row">
        <Counter label="Блоки" value={`${s.blocksSuccess}/${s.blocksTotal}`} />
        <Counter label="Тесты" value={`${s.testsPassed}/${s.testsTotal}`} />
        {s.blocksFailed > 0 && <Counter label="Упало блоков" value={String(s.blocksFailed)} accent="red" />}
        {s.blocksSkipped > 0 && <Counter label="Пропущено" value={String(s.blocksSkipped)} accent="gray" />}
        {s.testsError > 0 && <Counter label="Ошибок" value={String(s.testsError)} accent="red" />}
        {s.testsTimeout > 0 && <Counter label="Таймаутов" value={String(s.testsTimeout)} accent="orange" />}
      </div>

      {run.errorStage && (
        <div className="card error-card">
          <p className="error-stage">Ошибка: <strong>{run.errorStage}</strong></p>
          {run.errorMessage && <pre className="log-block">{run.errorMessage}</pre>}
        </div>
      )}

      <div className="run-view-tabs" role="tablist" aria-label="Run views">
        <button
          className={`run-view-tab ${activeView === 'logs' ? 'active' : ''}`}
          onClick={() => setActiveView('logs')}
          role="tab"
          aria-selected={activeView === 'logs'}
        >
          Логи блоков
        </button>
        <button
          className={`run-view-tab ${activeView === 'graph' ? 'active' : ''}`}
          onClick={() => setActiveView('graph')}
          role="tab"
          aria-selected={activeView === 'graph'}
        >
          Граф исполнения
        </button>
        <button
          className={`run-view-tab ${activeView === 'build' ? 'active' : ''}`}
          onClick={() => setActiveView('build')}
          role="tab"
          aria-selected={activeView === 'build'}
        >
          Build Logs
        </button>
        <button
          className={`run-view-tab ${activeView === 'timing' ? 'active' : ''}`}
          onClick={() => setActiveView('timing')}
          role="tab"
          aria-selected={activeView === 'timing'}
        >
          Временные метки
        </button>
      </div>

      {activeView === 'graph' && (
        <ExecutionPlanGraph blocks={run.blocks} onSelectBlock={openBlockLogs} />
      )}

      {activeView === 'build' && (
        <section className="section">
          <h2 className="section-title">Build Logs</h2>
          {run.buildStdout || run.buildStderr ? (
            <div className="build-log-panel">
              {run.buildStdout && (
                <div>
                  <p className="log-label">stdout</p>
                  <pre className="log-block">{run.buildStdout}</pre>
                </div>
              )}
              {run.buildStderr && (
                <div>
                  <p className="log-label">stderr</p>
                  <pre className="log-block">{run.buildStderr}</pre>
                </div>
              )}
            </div>
          ) : (
            <p className="muted">Build logs are not available yet.</p>
          )}
        </section>
      )}

      {activeView === 'timing' && (
        <RunTimingPanel run={run} />
      )}

      {activeView === 'logs' && run.blocks.length > 0 && (
        <section className="section">
          <h2 className="section-title">Блоки</h2>
          <div className="block-list">
            {run.blocks.map(block => (
              <BlockCard
                key={block.id}
                block={block}
                expanded={expandedBlocks.has(block.id)}
                expandedTests={expandedTests}
                onToggle={() => toggleBlock(block.id)}
                onToggleTest={toggleTest}
              />
            ))}
          </div>
        </section>
      )}
    </div>
  )
}

function RunTimingPanel({ run }: { run: RunDetailType }) {
  const timing = collectRunTiming(run)

  return (
    <section className="section">
      <h2 className="section-title">Временные метки</h2>
      <div className="timing-grid">
        <TimingMetric
          label="Подготовка до старта сценария"
          value={formatDuration(timing.preparationDurationMs)}
          hint="От создания запуска до перехода к выполнению блоков, включая сборку."
        />
        <TimingMetric
          label="Сборка и упаковка образа"
          value={formatDuration(timing.buildDurationMs)}
          hint={formatRange(timing.buildStartedAt, timing.buildFinishedAt)}
        />
        <TimingMetric
          label="Подготовка образа на узлах"
          value={formatDuration(timing.imagePrepareTotalMs)}
          hint={`sum: ${formatDuration(timing.imagePrepareTotalMs)} / max: ${formatDuration(timing.imagePrepareMaxMs)}`}
        />
        <TimingMetric
          label="Прочие накладные расходы"
          value={formatDuration(timing.otherOverheadMs)}
          hint="Подготовка после build и паузы внутри блоков вне окна выполнения тестов."
        />
      </div>

      <div className="timing-details">
        <div className="timing-row">
          <span>Запуск создан</span>
          <span className="mono muted">{formatDateTime(timing.runStartedAt)}</span>
        </div>
        <div className="timing-row">
          <span>Сценарий стартовал</span>
          <span className="mono muted">{formatDateTime(timing.executionStartedAt)}</span>
        </div>
        <div className="timing-row">
          <span>Запуск завершён</span>
          <span className="mono muted">{formatDateTime(timing.runFinishedAt)}</span>
        </div>
      </div>

      {timing.blockRows.length > 0 && (
        <div className="table-wrap timing-table">
          <table>
            <thead>
              <tr>
                <th>Блок</th>
                <th>Агент</th>
                <th>Подготовка образа</th>
                <th>Окно тестов</th>
                <th>Накладные расходы блока</th>
              </tr>
            </thead>
            <tbody>
              {timing.blockRows.map(row => (
                <tr key={row.id}>
                  <td>{row.name}</td>
                  <td className="mono muted">{row.agentId ?? 'n/a'}</td>
                  <td>{formatDuration(row.imagePrepareDurationMs)}</td>
                  <td>{formatDuration(row.testWindowMs)}</td>
                  <td>{formatDuration(row.blockOverheadMs)}</td>
                </tr>
              ))}
            </tbody>
          </table>
        </div>
      )}
    </section>
  )
}

function TimingMetric({ label, value, hint }: { label: string; value: string; hint: string }) {
  return (
    <div className="timing-card">
      <span className="timing-label">{label}</span>
      <span className="timing-value">{value}</span>
      <span className="timing-hint">{hint}</span>
    </div>
  )
}

function Counter({ label, value, accent }: { label: string; value: string; accent?: 'red' | 'orange' | 'gray' }) {
  return (
    <div className={`counter-item ${accent ? `counter-${accent}` : ''}`}>
      <span className="counter-value">{value}</span>
      <span className="counter-label">{label}</span>
    </div>
  )
}

function ExecutionPlanGraph({
  blocks,
  onSelectBlock,
}: {
  blocks: BlockRun[]
  onSelectBlock: (blockId: string) => void
}) {
  const graph = computeRunGraph(blocks)

  return (
    <section className="section">
      <h2 className="section-title">Граф исполнения</h2>
      <div className="run-plan-graph">
        <div className="run-plan-canvas" style={{ width: graph.width, height: graph.height }}>
          <svg
            className="run-plan-edges"
            width={graph.width}
            height={graph.height}
            viewBox={`0 0 ${graph.width} ${graph.height}`}
            aria-hidden="true"
          >
            <defs>
              <marker id="run-plan-arrowhead" markerWidth="8" markerHeight="8" refX="7" refY="4" orient="auto">
                <path d="M 0 0 L 8 4 L 0 8 z" />
              </marker>
            </defs>
            {graph.edges.map(edge => (
              <path
                key={`${edge.from}-${edge.to}`}
                className="run-plan-edge"
                d={edge.path}
                markerEnd="url(#run-plan-arrowhead)"
              />
            ))}
          </svg>

          {graph.nodes.map(node => (
            <button
              key={node.block.id}
              className={`run-plan-node run-plan-node-${node.block.status.toLowerCase()}`}
              style={{ left: node.x, top: node.y, width: GRAPH_NODE_WIDTH, height: GRAPH_NODE_HEIGHT }}
              onClick={() => onSelectBlock(node.block.id)}
              title="Open block logs"
            >
              <span className="run-plan-node-name">{node.block.blockName}</span>
              <span className="run-plan-node-meta">
                <Badge status={node.block.status} small />
                {node.block.agentId && <span className="mono text-muted">{node.block.agentId.slice(0, 8)}</span>}
              </span>
            </button>
          ))}
        </div>
        {false && ([] as BlockRun[][]).map((level, levelIndex) => (
          <div key={levelIndex} className="run-plan-level-group">
            <div className="run-plan-level">
              {level.map(block => (
                <button
                  key={block.id}
                  className={`run-plan-node run-plan-node-${block.status.toLowerCase()}`}
                  onClick={() => onSelectBlock(block.id)}
                  title="Открыть полные логи блока"
                >
                  <span className="run-plan-node-name">{block.blockName}</span>
                  <Badge status={block.status} small />
                </button>
              ))}
            </div>
            {levelIndex < ([] as BlockRun[][]).length - 1 && <div className="run-plan-arrow">→</div>}
          </div>
        ))}
      </div>
    </section>
  )
}

function BlockCard({
  block, expanded, expandedTests, onToggle, onToggleTest,
}: {
  block: BlockRun
  expanded: boolean
  expandedTests: Set<string>
  onToggle: () => void
  onToggleTest: (id: string) => void
}) {
  const passed = block.tests.filter(t => t.status === 'PASSED').length

  return (
    <div id={`block-${block.id}`} className={`block-card block-${block.status.toLowerCase()}`}>
      <div className="block-header" onClick={onToggle} style={{ cursor: 'pointer' }}>
        <div className="block-header-left">
          <span className="expand-icon">{expanded ? '▼' : '▶'}</span>
          <Badge status={block.status} />
          <span className="block-name">{block.blockName}</span>
          {block.agentId && <span className="agent-badge">agent: {block.agentId}</span>}
        </div>
        <div className="block-header-right">
          <span className="test-count">{passed}/{block.tests.length} тестов</span>
          {block.durationMs != null && <span className="muted">{(block.durationMs / 1000).toFixed(1)}s</span>}
        </div>
      </div>

      {expanded && (
        <div className="block-tests">
          {block.tests.map(test => (
            <TestCard
              key={`${block.id}-${test.id}`}
              test={test}
              expanded={expandedTests.has(test.id)}
              onToggle={() => onToggleTest(test.id)}
            />
          ))}
        </div>
      )}
    </div>
  )
}

function TestCard({ test, expanded, onToggle }: { test: TestRun; expanded: boolean; onToggle: () => void }) {
  const summary = testFailureSummary(test)
  const hasDetails = Boolean(test.stdout || test.stderr || test.errorMessage || test.command)

  return (
    <div className={`test-row test-${test.status.toLowerCase()}`}>
      <div className="test-header" onClick={onToggle} style={{ cursor: 'pointer' }}>
        <div className="test-header-left">
          <span className="expand-icon">{expanded ? '▼' : '▶'}</span>
          <Badge status={test.status} />
          <span className="test-name">{test.testName}</span>
          {test.attempts > 1 && <span className="muted">({test.attempts} попыток)</span>}
        </div>
        <div className="test-header-right">
          {test.exitCode != null && <span className="mono muted">exit: {test.exitCode}</span>}
          {test.durationMs != null && <span className="muted">{(test.durationMs / 1000).toFixed(1)}s</span>}
          {hasDetails && <span className="muted">details</span>}
        </div>
      </div>

      {summary && !expanded && (
        <div className="test-summary">
          <span className="test-summary-label">{summary.label}</span>
          <code>{summary.text}</code>
        </div>
      )}

      {expanded && (
        <div className="test-logs">
          {summary && (
            <div className="test-summary test-summary-expanded">
              <span className="test-summary-label">{summary.label}</span>
              <code>{summary.text}</code>
            </div>
          )}
          <p className="log-label">command</p>
          <pre className="log-block log-block-command">{test.command}</pre>
          {test.stdout && (
            <div>
              <p className="log-label">stdout</p>
              <pre className="log-block">{test.stdout}</pre>
            </div>
          )}
          {test.stderr && (
            <div>
              <p className="log-label">stderr</p>
              <pre className="log-block">{test.stderr}</pre>
            </div>
          )}
          {test.errorMessage && (
            <div>
              <p className="log-label">error</p>
              <pre className="log-block">{test.errorMessage}</pre>
            </div>
          )}
        </div>
      )}
    </div>
  )
}

interface RunGraphNode {
  block: BlockRun
  x: number
  y: number
}

interface RunGraphEdge {
  from: string
  to: string
  path: string
}

function computeRunGraph(blocks: BlockRun[]): { nodes: RunGraphNode[]; edges: RunGraphEdge[]; width: number; height: number } {
  if (blocks.length === 0) {
    return { nodes: [], edges: [], width: GRAPH_NODE_WIDTH, height: GRAPH_NODE_HEIGHT }
  }

  const byConfigId = new Map(blocks.map(block => [block.blockConfigId, block]))
  const originalIndex = new Map(blocks.map((block, index) => [block.blockConfigId, index]))
  const depsByConfigId = new Map(
    blocks.map(block => [
      block.blockConfigId,
      (block.dependsOn ?? []).filter(dep => byConfigId.has(dep)),
    ])
  )
  const levelMemo = new Map<string, number>()
  const visiting = new Set<string>()

  function levelOf(configId: string): number {
    if (levelMemo.has(configId)) return levelMemo.get(configId)!
    if (visiting.has(configId)) return 0
    visiting.add(configId)
    const deps = depsByConfigId.get(configId) ?? []
    const level = deps.length === 0 ? 0 : Math.max(...deps.map(levelOf)) + 1
    visiting.delete(configId)
    levelMemo.set(configId, level)
    return level
  }

  blocks.forEach(block => levelOf(block.blockConfigId))

  const levels: BlockRun[][] = []
  blocks.forEach(block => {
    const level = levelMemo.get(block.blockConfigId) ?? 0
    levels[level] ??= []
    levels[level].push(block)
  })

  levels.forEach(level => {
    level.sort((a, b) => {
      const aDeps = depsByConfigId.get(a.blockConfigId) ?? []
      const bDeps = depsByConfigId.get(b.blockConfigId) ?? []
      const aAnchor = aDeps.length ? Math.min(...aDeps.map(dep => originalIndex.get(dep) ?? 0)) : (originalIndex.get(a.blockConfigId) ?? 0)
      const bAnchor = bDeps.length ? Math.min(...bDeps.map(dep => originalIndex.get(dep) ?? 0)) : (originalIndex.get(b.blockConfigId) ?? 0)
      return aAnchor - bAnchor || (originalIndex.get(a.blockConfigId) ?? 0) - (originalIndex.get(b.blockConfigId) ?? 0)
    })
  })

  const maxRows = Math.max(...levels.map(level => level.length))
  const width = GRAPH_PADDING * 2 + levels.length * GRAPH_NODE_WIDTH + (levels.length - 1) * GRAPH_COLUMN_GAP
  const height = GRAPH_PADDING * 2 + maxRows * GRAPH_NODE_HEIGHT + (maxRows - 1) * GRAPH_ROW_GAP

  const nodes: RunGraphNode[] = levels.flatMap((level, levelIndex) => {
    const groupHeight = level.length * GRAPH_NODE_HEIGHT + Math.max(0, level.length - 1) * GRAPH_ROW_GAP
    const startY = (height - groupHeight) / 2
    return level.map((block, rowIndex) => ({
      block,
      x: GRAPH_PADDING + levelIndex * (GRAPH_NODE_WIDTH + GRAPH_COLUMN_GAP),
      y: startY + rowIndex * (GRAPH_NODE_HEIGHT + GRAPH_ROW_GAP),
    }))
  })

  const nodeByConfigId = new Map(nodes.map(node => [node.block.blockConfigId, node]))
  const edges = blocks.flatMap(block => {
    const to = nodeByConfigId.get(block.blockConfigId)
    if (!to) return []
    return (depsByConfigId.get(block.blockConfigId) ?? []).flatMap(dep => {
      const from = nodeByConfigId.get(dep)
      if (!from) return []
      const sx = from.x + GRAPH_NODE_WIDTH
      const sy = from.y + GRAPH_NODE_HEIGHT / 2
      const tx = to.x
      const ty = to.y + GRAPH_NODE_HEIGHT / 2
      const curve = Math.max(48, (tx - sx) * 0.42)
      return [{
        from: dep,
        to: block.blockConfigId,
        path: `M ${sx} ${sy} C ${sx + curve} ${sy}, ${tx - curve} ${ty}, ${tx} ${ty}`,
      }]
    })
  })

  return { nodes, edges, width, height }
}

interface TimingBlockRow {
  id: string
  name: string
  agentId?: string
  imagePrepareDurationMs?: number
  testWindowMs?: number
  blockOverheadMs?: number
}

interface CollectedRunTiming {
  runStartedAt?: string
  buildStartedAt?: string
  buildFinishedAt?: string
  executionStartedAt?: string
  runFinishedAt?: string
  buildDurationMs?: number
  preparationDurationMs?: number
  imagePrepareTotalMs?: number
  imagePrepareMaxMs?: number
  otherOverheadMs?: number
  blockRows: TimingBlockRow[]
}

function collectRunTiming(run: RunDetailType): CollectedRunTiming {
  const firstBlockStart = earliest(run.blocks.map(block => block.startedAt))
  const executionStartedAt = run.timing?.executionStartedAt ?? firstBlockStart
  const runStartedAt = run.timing?.runStartedAt ?? run.startedAt
  const runFinishedAt = run.timing?.runFinishedAt ?? run.finishedAt
  const buildStartedAt = run.timing?.buildStartedAt
  const buildFinishedAt = run.timing?.buildFinishedAt
  const buildDurationMs = run.timing?.buildDurationMs ?? diffMs(buildStartedAt, buildFinishedAt)
  const preparationDurationMs = run.timing?.preparationDurationMs ?? diffMs(runStartedAt, executionStartedAt)
  const postBuildOverheadMs = diffMs(buildFinishedAt, executionStartedAt)

  const blockRows = run.blocks.map(block => {
    const testStartedAt = earliest(block.tests.map(test => test.startedAt))
    const testFinishedAt = latest(block.tests.map(test => test.finishedAt))
    const testWindowMs = diffMs(testStartedAt, testFinishedAt)
    const blockOverheadMs = subtractDurations(block.durationMs, testWindowMs)

    return {
      id: block.id,
      name: block.blockName,
      agentId: block.agentId,
      imagePrepareDurationMs: block.imagePrepareDurationMs,
      testWindowMs,
      blockOverheadMs,
    }
  })

  const imagePrepareDurations = blockRows
    .map(row => row.imagePrepareDurationMs)
    .filter((value): value is number => typeof value === 'number')
  const blockOverheads = blockRows
    .map(row => subtractDurations(row.blockOverheadMs, row.imagePrepareDurationMs))
    .filter((value): value is number => typeof value === 'number')

  return {
    runStartedAt,
    buildStartedAt,
    buildFinishedAt,
    executionStartedAt,
    runFinishedAt,
    buildDurationMs,
    preparationDurationMs,
    imagePrepareTotalMs: imagePrepareDurations.length ? imagePrepareDurations.reduce((sum, value) => sum + value, 0) : undefined,
    imagePrepareMaxMs: imagePrepareDurations.length ? Math.max(...imagePrepareDurations) : undefined,
    otherOverheadMs: sumKnown(postBuildOverheadMs, blockOverheads.length ? blockOverheads.reduce((sum, value) => sum + value, 0) : undefined),
    blockRows,
  }
}

function earliest(values: Array<string | undefined>): string | undefined {
  return values
    .filter((value): value is string => Boolean(value))
    .sort((a, b) => new Date(a).getTime() - new Date(b).getTime())[0]
}

function latest(values: Array<string | undefined>): string | undefined {
  return values
    .filter((value): value is string => Boolean(value))
    .sort((a, b) => new Date(b).getTime() - new Date(a).getTime())[0]
}

function diffMs(start?: string, end?: string): number | undefined {
  if (!start || !end) return undefined
  const diff = new Date(end).getTime() - new Date(start).getTime()
  return Number.isFinite(diff) && diff >= 0 ? diff : undefined
}

function subtractDurations(total?: number, part?: number): number | undefined {
  if (typeof total !== 'number' || typeof part !== 'number') return undefined
  return Math.max(0, total - part)
}

function sumKnown(...values: Array<number | undefined>): number | undefined {
  const known = values.filter((value): value is number => typeof value === 'number')
  return known.length ? known.reduce((sum, value) => sum + value, 0) : undefined
}

function formatDuration(ms?: number): string {
  if (typeof ms !== 'number') return 'нет данных'
  if (ms < 1000) return `${ms} ms`
  if (ms < 60_000) return `${(ms / 1000).toFixed(1)}s`
  const minutes = Math.floor(ms / 60_000)
  const seconds = Math.round((ms % 60_000) / 1000)
  return `${minutes}m ${seconds}s`
}

function formatDateTime(value?: string): string {
  if (!value) return 'нет данных'
  return new Date(value).toLocaleString()
}

function formatRange(start?: string, end?: string): string {
  if (!start && !end) return 'timestamps are not available yet'
  return `${formatDateTime(start)} -> ${formatDateTime(end)}`
}

function problemDetailIds(data: RunDetailType): { blocks: string[]; tests: string[] } {
  return {
    blocks: data.blocks
      .filter(block => block.status !== 'SUCCESS' && block.status !== 'PENDING' && block.status !== 'READY')
      .map(block => block.id),
    tests: data.blocks.flatMap(block =>
      block.tests
        .filter(test => test.status !== 'PASSED' && test.status !== 'PENDING')
        .map(test => test.id)
    ),
  }
}

function testFailureSummary(test: TestRun): { label: string; text: string } | null {
  if (test.status === 'PASSED' || test.status === 'PENDING') return null

  if (test.errorMessage) {
    return { label: 'error', text: firstMeaningfulLine(test.errorMessage) }
  }
  if (test.stderr) {
    return { label: 'stderr', text: firstMeaningfulLine(test.stderr) }
  }
  if (test.stdout) {
    return { label: 'stdout', text: firstMeaningfulLine(test.stdout) }
  }
  if (test.status === 'CANCELED') {
    return { label: 'status', text: 'Test was canceled before it produced output' }
  }
  if (test.status === 'TIMEOUT') {
    return { label: 'status', text: 'Test timed out before it produced output' }
  }
  if (test.exitCode != null) {
    return { label: 'exit', text: `Command exited with code ${test.exitCode}` }
  }
  return { label: 'status', text: `Test finished with status ${test.status}` }
}

function firstMeaningfulLine(value: string): string {
  const line = value
    .split(/\r?\n/)
    .map(part => part.trim())
    .find(Boolean) ?? value.trim()

  return line.length > 260 ? `${line.slice(0, 257)}...` : line
}
