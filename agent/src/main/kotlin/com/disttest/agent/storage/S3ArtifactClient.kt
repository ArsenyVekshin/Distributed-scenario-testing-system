package com.disttest.agent.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.core.sync.RequestBody
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.GetObjectRequest
import software.amazon.awssdk.services.s3.model.PutObjectRequest
import java.net.URI
import java.nio.file.Path
import jakarta.annotation.PostConstruct

@Component
class S3ArtifactClient(
    @Value("\${s3.endpoint}")   private val endpoint:  String,
    @Value("\${s3.region}")     private val region:    String,
    @Value("\${s3.access-key}") private val accessKey: String,
    @Value("\${s3.secret-key}") private val secretKey: String,
    @Value("\${s3.bucket}")     private val bucket:    String,
) {
    private val log = LoggerFactory.getLogger(S3ArtifactClient::class.java)
    private lateinit var s3: S3Client

    @PostConstruct
    fun init() {
        s3 = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(
                    AwsBasicCredentials.create(accessKey, secretKey)
                )
            )
            .forcePathStyle(true)
            .build()
        log.info("S3ArtifactClient initialised: endpoint={}, bucket={}", endpoint, bucket)
    }

    /** Downloads an object from MinIO to a local file. */
    fun downloadObject(key: String, target: Path) {
        log.info("Downloading s3://{}/{} → {}", bucket, key, target)
        s3.getObject(
            GetObjectRequest.builder().bucket(bucket).key(key).build(),
            target
        )
    }

    /** Uploads a local file to MinIO. */
    fun uploadObject(key: String, source: Path) {
        log.info("Uploading {} → s3://{}/{}", source, bucket, key)
        s3.putObject(
            PutObjectRequest.builder()
                .bucket(bucket)
                .key(key)
                .build(),
            RequestBody.fromFile(source)
        )
    }
}
