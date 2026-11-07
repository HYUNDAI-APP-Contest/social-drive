package com.socialdrive.app.data.remote

import kotlinx.coroutines.suspendCancellableCoroutine
import okhttp3.Call
import okhttp3.Callback
import okhttp3.MediaType.Companion.toMediaTypeOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import okhttp3.RequestBody.Companion.toRequestBody
import okhttp3.Response
import java.io.IOException
import java.util.concurrent.TimeUnit
import kotlin.coroutines.resume
import kotlin.coroutines.resumeWithException

class OkHttpCloudTransports private constructor(private val client: OkHttpClient) : ApiHttpTransport, ObjectUploadTransport {
    override suspend fun execute(request: HttpRequest): HttpResponse {
        val body = request.body?.toRequestBody(request.headers["Content-Type"]?.toMediaTypeOrNull())
        val builder = Request.Builder().url(request.uri.toURL())
        request.headers.forEach { (name, value) -> builder.header(name, value) }
        builder.method(request.method.name, body)
        return client.newCall(builder.build()).await().use { response ->
            val responseBody = response.body
            val length = responseBody?.contentLength() ?: 0
            if (length > MAX_CLOUD_RESPONSE_BYTES) throw CloudDriveException(CloudFailureKind.CONTRACT, "RESPONSE_TOO_LARGE", false)
            val bytes = responseBody?.byteStream()?.use { input ->
                val output = java.io.ByteArrayOutputStream()
                val buffer = ByteArray(8_192)
                var total = 0
                while (true) {
                    val count = input.read(buffer)
                    if (count < 0) break
                    total += count
                    if (total > MAX_CLOUD_RESPONSE_BYTES) throw CloudDriveException(CloudFailureKind.CONTRACT, "RESPONSE_TOO_LARGE", false)
                    output.write(buffer, 0, count)
                }
                output.toByteArray()
            } ?: byteArrayOf()
            HttpResponse(response.code, response.headers.toMultimap().mapValues { it.value.joinToString(",") }, bytes)
        }
    }

    companion object {
        fun create(): OkHttpCloudTransports = OkHttpCloudTransports(createCloudHttpClient())
    }
}

internal fun createCloudHttpClient(): OkHttpClient = OkHttpClient.Builder()
    .followRedirects(false)
    .followSslRedirects(false)
    .retryOnConnectionFailure(false)
    .connectTimeout(CLOUD_CONNECT_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .readTimeout(CLOUD_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .writeTimeout(CLOUD_IO_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .callTimeout(CLOUD_CALL_TIMEOUT_SECONDS, TimeUnit.SECONDS)
    .build()

internal fun sharedCloudTransports(): OkHttpCloudTransports = CloudTransportHolder.instance

private object CloudTransportHolder {
    val instance: OkHttpCloudTransports = OkHttpCloudTransports.create()
}

private suspend fun Call.await(): Response = suspendCancellableCoroutine { continuation ->
    continuation.invokeOnCancellation { cancel() }
    enqueue(object : Callback {
        override fun onFailure(call: Call, error: IOException) {
            if (continuation.isActive) continuation.resumeWithException(error)
        }

        override fun onResponse(call: Call, response: Response) {
            if (continuation.isActive) continuation.resume(response) else response.close()
        }
    })
}

private const val MAX_CLOUD_RESPONSE_BYTES = 2 * 1024 * 1024
internal const val CLOUD_CONNECT_TIMEOUT_SECONDS = 10L
internal const val CLOUD_IO_TIMEOUT_SECONDS = 20L
internal const val CLOUD_CALL_TIMEOUT_SECONDS = 30L
