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
        fun create(): OkHttpCloudTransports = OkHttpCloudTransports(
            OkHttpClient.Builder()
                .followRedirects(false)
                .followSslRedirects(false)
                .connectTimeout(10, TimeUnit.SECONDS)
                .readTimeout(20, TimeUnit.SECONDS)
                .writeTimeout(20, TimeUnit.SECONDS)
                .build(),
        )
    }
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
