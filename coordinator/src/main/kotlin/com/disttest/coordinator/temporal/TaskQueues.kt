package com.disttest.coordinator.temporal

/** Shared Temporal task queue names used by coordinator and agents. */
object TaskQueues {
    /** Coordinator's own worker — runs scenario orchestration workflows and state activities. */
    const val COORDINATOR = "coordinator-task-queue"

    /** Agent workers — execute git clone, docker build, MinIO upload (build activities). */
    const val BUILD = "build-task-queue"

    /** Agent workers — execute test blocks via docker run (test activities). */
    const val AGENT = "agent-task-queue"
}
