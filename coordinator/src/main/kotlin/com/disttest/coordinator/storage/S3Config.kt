package com.disttest.coordinator.storage

import org.slf4j.LoggerFactory
import org.springframework.beans.factory.annotation.Value
import org.springframework.context.annotation.Bean
import org.springframework.context.annotation.Configuration
import software.amazon.awssdk.auth.credentials.AwsBasicCredentials
import software.amazon.awssdk.auth.credentials.StaticCredentialsProvider
import software.amazon.awssdk.http.urlconnection.UrlConnectionHttpClient
import software.amazon.awssdk.regions.Region
import software.amazon.awssdk.services.s3.S3Client
import software.amazon.awssdk.services.s3.model.CreateBucketRequest
import software.amazon.awssdk.services.s3.model.HeadBucketRequest
import software.amazon.awssdk.services.s3.model.NoSuchBucketException
import java.net.URI

@Configuration
class S3Config(
    @Value("\${s3.endpoint}")   private val endpoint:  String,
    @Value("\${s3.region}")     private val region:    String,
    @Value("\${s3.access-key}") private val accessKey: String,
    @Value("\${s3.secret-key}") private val secretKey: String,
    @Value("\${s3.bucket}")     private val bucket:    String,
) {
    private val log = LoggerFactory.getLogger(S3Config::class.java)

    @Bean
    fun s3Client(): S3Client {
        val client = S3Client.builder()
            .endpointOverride(URI.create(endpoint))
            .region(Region.of(region))
            .credentialsProvider(
                StaticCredentialsProvider.create(AwsBasicCredentials.create(accessKey, secretKey))
            )
            .httpClientBuilder(UrlConnectionHttpClient.builder())
            .forcePathStyle(true)   // MinIO требует path-style URL
            .build()

        ensureBucketExists(client)
        return client
    }

    private fun ensureBucketExists(client: S3Client) {
        try {
            client.headBucket(HeadBucketRequest.builder().bucket(bucket).build())
            log.info("S3 bucket '{}' already exists", bucket)
        } catch (_: NoSuchBucketException) {
            client.createBucket(CreateBucketRequest.builder().bucket(bucket).build())
            log.info("S3 bucket '{}' created", bucket)
        }
    }
}
