package com.disttest.coordinator.run

enum class ScenarioRunStatus {
    PENDING,
    BUILDING,
    RUNNING,
    SUCCESS,
    FAILED,
    CANCELED,
    TIMEOUT;

    fun isTerminal() = this in setOf(SUCCESS, FAILED, CANCELED, TIMEOUT)
}

enum class BlockRunStatus {
    PENDING,
    READY,
    RUNNING,
    SUCCESS,
    FAILED,
    SKIPPED,
    CANCELED,
    RETRYING,
    TIMEOUT
}

enum class TestRunStatus {
    PENDING,
    RUNNING,
    PASSED,
    FAILED,
    ERROR,
    TIMEOUT,
    CANCELED
}

enum class RunErrorStage {
    SOURCE,
    CONFIG_VALIDATION,
    BUILD,
    ARTIFACT_UPLOAD,
    TEST_EXECUTION,
    INTERNAL
}
