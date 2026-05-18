package com.disttest.coordinator.storage

import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Service
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.DeleteObjectRequest
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.NoSuchKeyException
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import software.amazon.awssdk.services.s3.presigner.S3Presigner
import software.amazon.awssdk.services.s3.presigner.model.GetObjectPresignRequest
import java.net.URI
import java.time.Duration

/**
 * Сервис хранения YAML-конфигов сценариев в S3-совместимом хранилище (MinIO).
 *
 * Ключ объекта: `scenarios/{scenarioId}/config.yaml`
 */
@Service
class ConfigStore(
    private val s3: S3Client,
    @Value("\${s3.bucket}")   private val bucket:   String,
    @Value("\${s3.endpoint}") private val endpoint: String,
    @Value("\${s3.region}")   private val region:   String,
    @Value("\${s3.access-key}") private val accessKey: String,
    @Value("\${s3.secret-key}") private val secretKey: String,
) {
    companion object {
        fun yamlKey(scenarioId: String) = "scenarios/$scenarioId/config.yaml"
    }

    /** Загружает YAML-строку в S3 и возвращает ключ объекта. */
    fun upload(key: String, content: String): String {
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .contentType("application/x-yaml")
                .build(),
            RequestBody.fromString(content)
        )
        return key
    }

    /** Скачивает объект по ключу и возвращает его содержимое как строку. */
    fun download(key: String): String =
        s3.getObjectAsBytes(
            GetObjectRequest.builder().bucket(bucket).key(key).build()
        ).asUtf8String()

    /** Удаляет объект по ключу. Не бросает исключение, если ключ не существует. */
    fun delete(key: String) {
        try {
            s3.deleteObject(DeleteObjectRequest.builder().bucket(bucket).key(key).build())
        } catch (_: NoSuchKeyException) { /* уже удалён */ }
    }

    /**
     * Генерирует presigned URL для прямого скачивания конфига.
     * Используется в REST-ответах, чтобы клиент мог получить YAML без промежуточного сервиса.
     */
    fun presignedDownloadUrl(key: String, ttl: Duration = Duration.ofMinutes(15)): String {
        val presigner = S3Presigner.builder()
            .endpointOverride(URI.create(endpoint))
            .region(software.amazon.awssdk.regions.Region.of(region))
            .credentialsProvider(
                software.amazon.awssdk.auth.credentials.StaticCredentialsProvider.create(
                    software.amazon.awssdk.auth.credentials.AwsBasicCredentials.create(accessKey, secretKey)
                )
            )
            .build()

        return presigner.presignGetObject(
            GetObjectPresignRequest.builder()
                .signatureDuration(ttl)
                .getObjectRequest { it.bucket(bucket).key(key) }
                .build()
        ).url().toString().also { presigner.close() }
    }
}
