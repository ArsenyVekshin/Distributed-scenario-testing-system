package com.disttest.agent.temporal

import com.disttest.agent.nodes.AgentNodeClient
import com.disttest.agent.process.ProcessResult
import com.disttest.agent.process.ProcessRunner
import com.disttest.agent.storage.S3ArtifactClient
import io.temporal.activity.Activity
import io.temporal.activity.ActivityInterface
import io.temporal.activity.ActivityMethod
import org.slf4j.LoggerFactory
import org.springframework.stereotype.Component
import java.net.URI
import java.net.URLEncoder
import java.nio.charset.StandardCharsets
import java.nio.file.Files
import java.nio.file.Path
import java.time.Instant
import kotlin.io.path.exists
import kotlin.io.path.readText

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
    val startedAt: Instant? = null,
    val finishedAt: Instant? = null,
    val durationMs: Long? = null,
    val errorStage: String? = null,
    val errorMessage: String? = null,
)

@ActivityInterface
interface BuildProjectActivities {
    @ActivityMethod
    fun prepareAndBuild(request: BuildProjectRequest): BuildProjectResult
}

@Component
class BuildProjectActivitiesImpl(
    private val s3: S3ArtifactClient,
    private val nodeClient: AgentNodeClient,
) : BuildProjectActivities {

    private val log = LoggerFactory.getLogger(BuildProjectActivitiesImpl::class.java)

    override fun prepareAndBuild(request: BuildProjectRequest): BuildProjectResult {
        val startedAt = Instant.now()
        val workDir = Files.createTempDirectory("dst-build-${request.runId}-")
        val repoDir = workDir.resolve("repo")
        val imageTar = workDir.resolve("image.tar")
        val output = StringBuilder()
        val errors = StringBuilder()
        nodeClient.busy("build:${request.runId}")

        return try {
            log.info("Preparing build for run '{}' from branch '{}'", request.runId, request.branch)

            Activity.getExecutionContext().heartbeat("cloning repository")
            runChecked(
                stage = "GIT_CLONE",
                command = listOf(
                    "git",
                    "clone",
                    "--branch",
                    request.branch,
                    "--single-branch",
                    cloneUrl(request.gitlabUrl, request.accessToken),
                    repoDir.toString(),
                ),
                stdout = output,
                stderr = errors,
                timeoutSeconds = 300,
            )

            Activity.getExecutionContext().heartbeat("reading scenario config")
            val scenarioPath = repoDir.resolveNormalized(request.scenarioConfigPath)
            if (!scenarioPath.exists()) {
                error("Scenario config not found: ${request.scenarioConfigPath}")
            }
            val scenarioYaml = scenarioPath.readText(StandardCharsets.UTF_8)

            val commitSha = runChecked(
                stage = "GIT_REV_PARSE",
                command = listOf("git", "rev-parse", "HEAD"),
                workingDir = repoDir,
                stdout = output,
                stderr = errors,
                timeoutSeconds = 30,
            ).stdout.trim()

            Activity.getExecutionContext().heartbeat("building docker image")
            runChecked(
                stage = "DOCKER_BUILD",
                command = listOf(
                    "docker",
                    "build",
                    "-f",
                    request.dockerfilePath,
                    "-t",
                    request.imageTag,
                    ".",
                ),
                workingDir = repoDir,
                stdout = output,
                stderr = errors,
                timeoutSeconds = 900,
            )

            Activity.getExecutionContext().heartbeat("saving docker image")
            runChecked(
                stage = "DOCKER_SAVE",
                command = listOf("docker", "save", "-o", imageTar.toString(), request.imageTag),
                stdout = output,
                stderr = errors,
                timeoutSeconds = 300,
            )

            Activity.getExecutionContext().heartbeat("uploading image artifact")
            s3.uploadObject(request.artifactKey, imageTar)

            val finishedAt = Instant.now()
            BuildProjectResult(
                runId = request.runId,
                success = true,
                commitSha = commitSha,
                scenarioYaml = scenarioYaml,
                artifactKey = request.artifactKey,
                imageTag = request.imageTag,
                stdout = output.toString(),
                stderr = errors.toString(),
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
            )
        } catch (e: BuildStageException) {
            val finishedAt = Instant.now()
            log.warn("Build failed at {} for run '{}': {}", e.stage, request.runId, e.message)
            BuildProjectResult(
                runId = request.runId,
                success = false,
                stdout = output.toString(),
                stderr = errors.toString(),
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
                errorStage = e.stage,
                errorMessage = e.message,
            )
        } catch (e: Exception) {
            val finishedAt = Instant.now()
            log.warn("Build failed for run '{}': {}", request.runId, e.message)
            BuildProjectResult(
                runId = request.runId,
                success = false,
                stdout = output.toString(),
                stderr = errors.toString(),
                startedAt = startedAt,
                finishedAt = finishedAt,
                durationMs = finishedAt.toEpochMilli() - startedAt.toEpochMilli(),
                errorStage = "INTERNAL",
                errorMessage = e.message ?: e.toString(),
            )
        } finally {
            nodeClient.idle()
            deleteRecursively(workDir)
        }
    }

    private fun runChecked(
        stage: String,
        command: List<String>,
        workingDir: Path? = null,
        stdout: StringBuilder,
        stderr: StringBuilder,
        timeoutSeconds: Long,
    ): ProcessResult {
        val result = ProcessRunner.run(command, workingDir = workingDir, timeoutSeconds = timeoutSeconds)
        stdout.append(result.stdout)
        stderr.append(result.stderr)

        if (result.timedOut) {
            throw BuildStageException(stage, "Command timed out after ${timeoutSeconds}s: ${command.first()}")
        }
        if (result.exitCode != 0) {
            throw BuildStageException(stage, "Command failed with exit code ${result.exitCode}: ${command.first()}")
        }

        return result
    }

    private fun cloneUrl(gitlabUrl: String, accessToken: String): String {
        if (accessToken.isBlank()) return gitlabUrl
        val uri = URI(gitlabUrl)
        if (uri.scheme != "http" && uri.scheme != "https") return gitlabUrl

        val token = URLEncoder.encode(accessToken, StandardCharsets.UTF_8)
        return URI(
            uri.scheme,
            "oauth2:$token",
            uri.host,
            uri.port,
            uri.path,
            uri.query,
            uri.fragment,
        ).toString()
    }

    private fun Path.resolveNormalized(relativePath: String): Path =
        resolve(relativePath).normalize().also {
            require(it.startsWith(this.normalize())) { "Path escapes repository: $relativePath" }
        }

    private fun deleteRecursively(path: Path) {
        if (!path.exists()) return
        Files.walk(path).use { paths ->
            paths.sorted(Comparator.reverseOrder())
                .forEach { Files.deleteIfExists(it) }
        }
    }
}

private class BuildStageException(
    val stage: String,
    message: String,
) : RuntimeException(message)
