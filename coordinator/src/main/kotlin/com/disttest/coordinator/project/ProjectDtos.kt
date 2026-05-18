package com.disttest.coordinator.project

import java.time.Instant

data class CreateProjectRequest(
    val name: String,
    val gitlabUrl: String,
    val branch: String,
    val accessToken: String,
    val scenarioConfigPath: String = "scenario.dst.yaml",
    val dockerfilePath: String = "Dockerfile",
)

data class UpdateProjectRequest(
    val name: String?,
    val gitlabUrl: String?,
    val branch: String?,
    val accessToken: String?,
    val scenarioConfigPath: String?,
    val dockerfilePath: String?,
)

data class ProjectDto(
    val id: String,
    val name: String,
    val gitlabUrl: String,
    val branch: String,
    val accessTokenMasked: String,
    val scenarioConfigPath: String,
    val dockerfilePath: String,
    val createdAt: Instant,
    val updatedAt: Instant,
)

fun ProjectEntity.toDto() = ProjectDto(
    id                 = id,
    name               = name,
    gitlabUrl          = gitlabUrl,
    branch             = branch,
    accessTokenMasked  = maskToken(accessToken),
    scenarioConfigPath = scenarioConfigPath,
    dockerfilePath     = dockerfilePath,
    createdAt          = createdAt,
    updatedAt          = updatedAt,
)

private fun maskToken(token: String): String {
    if (token.length <= 8) return "****"
    return token.take(token.length - 4).replace(Regex("."), "*") + token.takeLast(4)
}
