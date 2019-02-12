package jetbrains.buildServer.artifacts.google.publish

import com.google.api.client.util.ExponentialBackOff
import com.google.cloud.storage.Bucket
import com.google.cloud.storage.StorageException
import com.intellij.openapi.diagnostic.Logger
import jetbrains.buildServer.agent.AgentRunningBuild
import jetbrains.buildServer.agent.ArtifactPublishingFailedException
import jetbrains.buildServer.artifacts.ArtifactDataInstance
import jetbrains.buildServer.serverSide.artifacts.google.GoogleUtils
import kotlinx.coroutines.experimental.*
import java.io.File
import java.io.FileInputStream
import java.util.concurrent.TimeUnit

class GoogleRegularFileUploader : GoogleFileUploader {

    override fun publishFiles(build: AgentRunningBuild,
                              pathPrefix: String,
                              filesToPublish: Map<File, String>) = runBlocking {
        val bucket = GoogleUtils.getStorageBucket(build.artifactStorageSettings)
        return@runBlocking filesToPublish.map { (file, path) ->
            publishArtifactAsync(build, bucket, pathPrefix, file, path)
        }.map { it.await() }
    }

    private fun publishArtifactAsync(build: AgentRunningBuild,
                                     bucket: Bucket,
                                     pathPrefix: String,
                                     file: File,
                                     path: String): Deferred<ArtifactDataInstance> = async(CommonPool, CoroutineStart.DEFAULT) {
        val filePath = GoogleFileUtils.normalizePath(path, file.name)
        val blobName = GoogleFileUtils.normalizePath(pathPrefix, filePath)
        val contentType = GoogleFileUtils.getContentType(file)
        val backOff = ExponentialBackOff()
        var backOffInterval: Long
        do {
            try {
                FileInputStream(file).use {
                    bucket.create(blobName, it, contentType)
                    val length = file.length()
                    return@async ArtifactDataInstance.create(filePath, length)
                }
            } catch (e: Throwable) {
                val message = "Failed to publish artifact $filePath: ${e.message}"
                LOG.infoAndDebugDetails(message, e)

                if (e is StorageException) {
                    if (!e.isRetryable) {
                        LOG.warn(e.message)
                        build.buildLogger.error(e.message)
                        throw ArtifactPublishingFailedException(message, false, e)
                    }
                }

                LOG.info(e.message)
                backOffInterval = backOff.nextBackOffMillis()
                if (backOffInterval != ExponentialBackOff.STOP) {
                    build.buildLogger.message("Failed to publish artifact $filePath: ${e.message}. Will retry in ${backOffInterval / 1000} seconds.")
                    delay(backOffInterval, TimeUnit.MILLISECONDS)
                }
            }
        } while (backOffInterval != ExponentialBackOff.STOP)

        throw throw ArtifactPublishingFailedException("Unable to publish artifact $filePath", false, null)
    }

    companion object {
        private val LOG = Logger.getInstance(GoogleRegularFileUploader::class.java.name)
    }
}