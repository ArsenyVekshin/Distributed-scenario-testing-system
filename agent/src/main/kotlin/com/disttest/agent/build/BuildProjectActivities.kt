package com.disttest.agent.build

import com.disttest.agent.process.ProcessRunner
import com.disttest.agent.storage.S3ArtifactClient
import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.nio.file.Files
import java.nio.file.Path

// ─── Shared DTOs (must match coordinator's BuildProjectRequest / BuildProjectResult) ──

data class BuildProjectRequest(
    val runId: String,
    val projectId: String,
    val gitlabUrl: String,
    val branch: String,
    val accessToken: String,
    val scenarioConfigPath: String,
    val dockerfilePath: String,
    val imageTag: String,
    val artifactKey: String,
)

data class BuildProjectResult(
    val runId: String,
    val success: Boolean,
    val commitSha: String? = null,
    val scenarioYaml: String? = null,
    val artifactKey: String? = null,
    val imageTag: String? = null,
    val stdout: String = "",
    val stderr: String = "",
    val errorStage: String? = null,
    val errorMessage: String? = null,
)

// ─── Interface ────────────────────────────────────────────────────────────────

@ActivityInterface
interface BuildProjectActivities {
    @ActivityMethod
    fun prepareAndBuild(request: BuildProjectRequest): BuildProjectResult
}

// ─── Implementation ───────────────────────────────────────────────────────────

@Component
class BuildProjectActivitiesImpl(
    private val s3: S3ArtifactClient,
    @Value("\${agent.id:}") private val configuredAgentId: String,
) : BuildProjectActivities {

    private val log = LoggerFactory.getLogger(BuildProjectActivitiesImpl::class.java)

    override fun prepareAndBuild(request: BuildProjectRequest): BuildProjectResult {
        val workspace = Path.of("/tmp/disttest/runs/${request.runId}")
        val repoDir   = workspace.resolve("repo")
        val tarFile   = workspace.resolve("image.tar")

        try {
            Files.createDirectories(repoDir)

            Activity.getExecutionContext().heartbeat("cloning")

            // 1. Git clone with token (mask token in logs)
            val cloneUrl = buildCloneUrl(request.gitlabUrl, request.accessToken)
            val cloneResult = ProcessRunner.run(
                command = listOf("git", "clone", "--branch", request.branch, "--depth", "1", cloneUrl, repoDir.toString()),
                timeoutSeconds = 300,
            )
            if (cloneResult.timedOut || cloneResult.exitCode != 0) {
                return failure(
                    request    = request,
                    errorStage = "SOURCE",
                    errorMessage = "git clone failed: ${maskToken(cloneResult.stderr, request.accessToken)}",
                    stdout     = maskToken(cloneResult.stdout, request.accessToken),
                    stderr     = maskToken(cloneResult.stderr, request.accessToken),
                )
            }

            Activity.getExecutionContext().heartbeat("reading commit sha")

            // 2. Get commit SHA
            val shaResult = ProcessRunner.run(listOf("git", "-C", repoDir.toString(), "rev-parse", "HEAD"))
            val commitSha = shaResult.stdout.trim().takeIf { it.isNotBlank() }

            Activity.getExecutionContext().heartbeat("reading scenario yaml")

            // 3. Read scenario YAML
            val yamlFile = repoDir.resolve(request.scenarioConfigPath)
            if (!yamlFile.toFile().exists()) {
                return failure(
                    request      = request,
                    errorStage   = "CONFIG_VALIDATION",
                    errorMessage = "Scenario config file not found: ${request.scenarioConfigPath}",
                    stdout       = "",
                    stderr       = "",
                )
            }
            val scenarioYaml = yamlFile.toFile().readText()

            Activity.getExecutionContext().heartbeat("docker build")

            // 4. Docker build
            val epochMillis = System.currentTimeMillis()
            val buildResult = ProcessRunner.run(
                command = listOf(
                    "docker", "build",
                    "-f", repoDir.resolve(request.dockerfilePath).toString(),
                    "--label", "disttest.managed=true",
                    "--label", "disttest.runId=${request.runId}",
                    "--label", "disttest.createdAt=$epochMillis",
                    "-t", request.imageTag,
                    repoDir.toString(),
                ),
                timeoutSeconds = 600,
            )
            if (buildResult.timedOut || buildResult.exitCode != 0) {
                return failure(
                    request      = request,
                    errorStage   = "BUILD",
                    errorMessage = "docker build failed (exit=${buildResult.exitCode})",
                    stdout       = buildResult.stdout,
                    stderr       = buildResult.stderr,
                )
            }

            Activity.getExecutionContext().heartbeat("docker save")

            // 5. Docker save
            val saveResult = ProcessRunner.run(
                command = listOf("docker", "save", request.imageTag, "-o", tarFile.toString()),
                timeoutSeconds = 120,
            )
            if (saveResult.exitCode != 0) {
                return failure(
                    request      = request,
                    errorStage   = "BUILD",
                    errorMessage = "docker save failed: ${saveResult.stderr}",
                    stdout       = buildResult.stdout,
                    stderr       = buildResult.stderr,
                )
            }

            Activity.getExecutionContext().heartbeat("uploading artifact")

            // 6. Upload image.tar to MinIO
            try {
                s3.uploadObject(request.artifactKey, tarFile)
            } catch (e: Exception) {
                return failure(
                    request      = request,
                    errorStage   = "ARTIFACT_UPLOAD",
                    errorMessage = "MinIO upload failed: ${e.message}",
                    stdout       = buildResult.stdout,
                    stderr       = buildResult.stderr,
                )
            }

            log.info("Build succeeded for run '{}': imageTag={}, commit={}", request.runId, request.imageTag, commitSha)

            return BuildProjectResult(
                runId       = request.runId,
                success     = true,
                commitSha   = commitSha,
                scenarioYaml = scenarioYaml,
                artifactKey = request.artifactKey,
                imageTag    = request.imageTag,
                stdout      = buildResult.stdout,
                stderr      = buildResult.stderr,
            )

        } finally {
            // Always clean up workspace (repo + tar)
            try { workspace.toFile().deleteRecursively() } catch (_: Exception) {}
        }
    }

    private fun failure(
        request: BuildProjectRequest,
        errorStage: String,
        errorMessage: String,
        stdout: String,
        stderr: String,
    ) = BuildProjectResult(
        runId        = request.runId,
        success      = false,
        errorStage   = errorStage,
        errorMessage = errorMessage,
        stdout       = stdout,
        stderr       = stderr,
    )

    private fun buildCloneUrl(gitlabUrl: String, token: String): String {
        val uri = java.net.URI.create(gitlabUrl)
        return "${uri.scheme}://oauth2:$token@${uri.host}${if (uri.port != -1) ":${uri.port}" else ""}${uri.path}"
    }

    private fun maskToken(text: String, token: String): String =
        if (token.isBlank()) text else text.replace(token, "****")
}
