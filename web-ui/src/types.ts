// ─── Legacy scenario types (kept for backward compat) ────────────────────────

export type ScenarioStatus = 'DRAFT' | 'RUNNING' | 'COMPLETED' | 'FAILED' | 'STOPPED'
export type BlockStatus   = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'SKIPPED'
export type TestStatus    = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR' | 'TIMED_OUT'
export type NodeStatus    = 'IDLE' | 'BUSY' | 'OFFLINE'

export interface ScenarioSummary {
  id: string
  name: string
  description: string
  status: ScenarioStatus
  blockCount: number
  createdAt: string
}

export interface TestDetail {
  id: string
  name: string
  status: TestStatus
  durationMs?: number
  output?: string
  error?: string
}

export interface BlockDetail {
  id: string
  name: string
  status: BlockStatus
  parallelism: number
  dependsOn: string[]
  nodeId?: string
  totalTests: number
  passedTests: number
  failedTests: number
  durationMs?: number
  tests: TestDetail[]
}

export interface ScenarioDetail extends ScenarioSummary {
  configYaml: string
  workflowRunId?: string
  completedBlocks: number
  totalBlocks: number
  blocks: BlockDetail[]
}

export interface NodeInfo {
  id: string
  address: string
  status: NodeStatus
  currentBlockId?: string
  registeredAt: string
  lastHeartbeatAt?: string
}

// ─── Projects ─────────────────────────────────────────────────────────────────

export interface Project {
  id: string
  name: string
  gitlabUrl: string
  branch: string
  accessTokenMasked: string
  scenarioConfigPath: string
  dockerfilePath: string
  createdAt: string
  updatedAt: string
}

export interface CreateProjectRequest {
  name: string
  gitlabUrl: string
  branch: string
  accessToken: string
  scenarioConfigPath?: string
  dockerfilePath?: string
}

// ─── Runs ─────────────────────────────────────────────────────────────────────

export type RunStatus = 'PENDING' | 'BUILDING' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'CANCELED' | 'TIMEOUT'
export type BlockRunStatus = 'PENDING' | 'READY' | 'RUNNING' | 'SUCCESS' | 'FAILED' | 'SKIPPED' | 'CANCELED' | 'RETRYING' | 'TIMEOUT'
export type TestRunStatus = 'PENDING' | 'RUNNING' | 'PASSED' | 'FAILED' | 'ERROR' | 'TIMEOUT' | 'CANCELED'

export interface RunSummary {
  id: string
  projectId: string
  status: RunStatus
  branch: string
  commitSha?: string
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  blocksTotal: number
  blocksSuccess: number
  blocksFailed: number
  testsTotal: number
  testsPassed: number
  testsFailed: number
}

export interface RunSummaryCounts {
  blocksTotal: number
  blocksSuccess: number
  blocksFailed: number
  blocksSkipped: number
  blocksCanceled: number
  blocksTimeout: number
  testsTotal: number
  testsPassed: number
  testsFailed: number
  testsError: number
  testsTimeout: number
}

export interface TestRun {
  id: string
  testConfigId: string
  testName: string
  command: string
  status: TestRunStatus
  exitCode?: number
  attempts: number
  stdout?: string
  stderr?: string
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  errorMessage?: string
}

export interface BlockRun {
  id: string
  blockConfigId: string
  blockName: string
  dependsOn?: string[]
  status: BlockRunStatus
  agentId?: string
  parallelism: number
  timeoutSeconds: number
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  imagePrepareStartedAt?: string
  imagePrepareFinishedAt?: string
  imagePrepareDurationMs?: number
  errorMessage?: string
  tests: TestRun[]
}

export interface RunTiming {
  runStartedAt?: string
  buildStartedAt?: string
  buildFinishedAt?: string
  buildDurationMs?: number
  executionStartedAt?: string
  runFinishedAt?: string
  totalDurationMs?: number
  preparationDurationMs?: number
}

export interface RunDetail {
  id: string
  projectId: string
  scenarioId?: string
  status: RunStatus
  branch: string
  commitSha?: string
  artifactKey?: string
  imageTag?: string
  errorStage?: string
  errorMessage?: string
  buildStdout?: string
  buildStderr?: string
  timing?: RunTiming
  startedAt?: string
  finishedAt?: string
  durationMs?: number
  summary: RunSummaryCounts
  blocks: BlockRun[]
}
