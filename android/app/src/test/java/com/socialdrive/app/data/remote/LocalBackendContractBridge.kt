package com.socialdrive.app.data.remote

import java.io.BufferedReader
import java.io.BufferedWriter
import java.io.Closeable
import java.io.InputStreamReader
import java.io.OutputStreamWriter
import java.net.URI
import java.nio.file.Files
import java.nio.file.Path
import java.util.Base64
import java.util.concurrent.Executors
import java.util.concurrent.TimeUnit
import java.util.concurrent.TimeoutException
import java.util.concurrent.atomic.AtomicInteger
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.withTimeout

/** Test-only, socket-free bridge to the real Node handler. Diagnostics contain metadata only. */
internal class LocalBackendContractBridge : ApiHttpTransport, ObjectUploadTransport, Closeable {
    private val root = findRepositoryRoot()
    private val process = ProcessBuilder("node", root.resolve("infra/test/contract-harness-server.mjs").toString())
        .directory(root.toFile())
        .start()
    private val writer = BufferedWriter(OutputStreamWriter(process.outputStream, Charsets.UTF_8))
    private val reader = BufferedReader(InputStreamReader(process.inputStream, Charsets.UTF_8))
    private val reads = Executors.newSingleThreadExecutor()
    private val errorReads = Executors.newSingleThreadExecutor()
    private val sequence = AtomicInteger()
    private val lock = Any()

    val capturedRequests = mutableListOf<CapturedRequest>()
    var failNextApiRequest = false
    var malformNextApiResponse = false
    var responseGate: CompletableDeferred<Unit>? = null

    init {
        errorReads.submit {
            process.errorStream.use { stream ->
                val buffer = ByteArray(1024)
                while (stream.read(buffer) >= 0) Unit
            }
        }
    }

    override suspend fun execute(request: HttpRequest): HttpResponse {
        if (request.uri.host == "api.contract.invalid" && failNextApiRequest) {
            failNextApiRequest = false
            throw IllegalStateException("injected transport failure")
        }
        val target = if (request.uri.host == "api.contract.invalid") "api" else "object"
        capturedRequests += CapturedRequest(
            target = target,
            method = request.method,
            uri = request.uri.toString(),
            headers = request.headers.toMap(),
            body = request.body?.copyOf() ?: byteArrayOf(),
        )
        val wireUri = if (target == "object") request.uri.toString() else pathAndQuery(request.uri)
        val response = exchange(target, request.method.name, wireUri, request.headers, request.body)
        if (target == "api") responseGate?.let { gate -> withTimeout(OPERATION_TIMEOUT_MILLIS) { gate.await() } }
        if (target == "api" && malformNextApiResponse) {
            malformNextApiResponse = false
            return HttpResponse(response.status, response.headers, "{private-response-marker".toByteArray())
        }
        return response
    }

    fun snapshot(): HarnessSnapshot {
        val response = exchange("control", "GET", "/snapshot", emptyMap(), null)
        check(response.status == 200) { "Contract harness snapshot failed with status ${response.status}" }
        val root = JsonCodec.parse(response.body).objectValue()
        return HarnessSnapshot(
            requestCount = root.integer("requestCount"),
            driveCount = root.integer("driveCount"),
            objectCount = root.integer("objectCount"),
            forbiddenKeys = root.array("forbiddenKeys").map { (it as JsonValue.StringValue).value },
            sentinelMatches = root.integer("sentinelMatches"),
            routeKeys = root.array("routeKeys").map { row -> (row as JsonValue.Array).values.map { (it as JsonValue.StringValue).value } },
            pointKeys = root.array("pointKeys").map { row -> (row as JsonValue.Array).values.map { (it as JsonValue.StringValue).value } },
            objectAuthorizationCount = root.integer("objectAuthorizationCount"),
            logKeys = root.array("logKeys").map { row -> (row as JsonValue.Array).values.map { (it as JsonValue.StringValue).value } },
            createStatuses = root.integerList("createStatuses"),
            deleteStatuses = root.integerList("deleteStatuses"),
            deleteIdempotencyKeys = root.stringList("deleteIdempotencyKeys"),
        )
    }

    private fun exchange(
        target: String,
        method: String,
        pathAndQuery: String,
        headers: Map<String, String>,
        body: ByteArray?,
    ): HttpResponse = synchronized(lock) {
        check(process.isAlive) { "Contract harness process is not running" }
        val id = "operation-${sequence.incrementAndGet().toString().padStart(3, '0')}"
        val message = jsonObject(
            "id" to jsonString(id),
            "target" to jsonString(target),
            "method" to jsonString(method),
            "pathAndQuery" to jsonString(pathAndQuery),
            "headers" to JsonValue.Object(headers.mapValues { jsonString(it.value) }),
            "bodyBase64" to jsonString(Base64.getEncoder().encodeToString(body ?: byteArrayOf())),
        )
        writer.write(JsonCodec.encode(message).toString(Charsets.UTF_8))
        writer.newLine()
        writer.flush()
        val future = reads.submit<String?> { reader.readLine() }
        val line = try {
            future.get(5, TimeUnit.SECONDS)
        } catch (_: TimeoutException) {
            future.cancel(true)
            throw IllegalStateException("Contract harness timed out for $id")
        } ?: throw IllegalStateException("Contract harness ended before $id")
        val values = JsonCodec.parse(line.toByteArray()).objectValue()
        check(values.string("id") == id) { "Contract harness response id mismatch" }
        val responseHeaders = values.requiredStringMap("headers")
        val responseBody = Base64.getDecoder().decode(values.string("bodyBase64"))
        HttpResponse(values.integer("status"), responseHeaders, responseBody)
    }

    override fun close() {
        runCatching { writer.close() }
        if (!process.waitFor(2, TimeUnit.SECONDS)) process.destroy()
        if (process.isAlive) process.destroyForcibly()
        reads.shutdownNow()
        errorReads.shutdownNow()
    }

    private fun pathAndQuery(uri: URI): String = uri.rawPath + (uri.rawQuery?.let { "?$it" } ?: "")

    private fun findRepositoryRoot(): Path {
        var current = Path.of(System.getProperty("user.dir")).toAbsolutePath().normalize()
        repeat(8) {
            if (Files.isRegularFile(current.resolve("infra/test/contract-harness-server.mjs")) &&
                Files.isRegularFile(current.resolve("android/settings.gradle.kts"))) return current
            current = current.parent ?: return@repeat
        }
        error("Repository root for contract harness was not found")
    }

    private companion object { const val OPERATION_TIMEOUT_MILLIS = 5_000L }
}

internal data class CapturedRequest(
    val target: String,
    val method: HttpMethod,
    val uri: String,
    val headers: Map<String, String>,
    val body: ByteArray,
) {
    override fun toString(): String = "CapturedRequest(target=$target, method=$method, sensitiveFields=[REDACTED])"
}

internal data class HarnessSnapshot(
    val requestCount: Int,
    val driveCount: Int,
    val objectCount: Int,
    val forbiddenKeys: List<String>,
    val sentinelMatches: Int,
    val routeKeys: List<List<String>>,
    val pointKeys: List<List<String>>,
    val objectAuthorizationCount: Int,
    val logKeys: List<List<String>>,
    val createStatuses: List<Int>,
    val deleteStatuses: List<Int>,
    val deleteIdempotencyKeys: List<String>,
)

private fun Map<String, JsonValue>.integerList(name: String): List<Int> =
    (get(name) as? JsonValue.Array)?.values?.map { value ->
        (value as? JsonValue.NumberValue)?.value?.toIntOrNull()
            ?: throw CloudContractException("INVALID_HARNESS_RESPONSE")
    } ?: throw CloudContractException("INVALID_HARNESS_RESPONSE")

private fun Map<String, JsonValue>.stringList(name: String): List<String> =
    (get(name) as? JsonValue.Array)?.values?.map { value ->
        (value as? JsonValue.StringValue)?.value
            ?: throw CloudContractException("INVALID_HARNESS_RESPONSE")
    } ?: throw CloudContractException("INVALID_HARNESS_RESPONSE")

private fun Map<String, JsonValue>.requiredStringMap(name: String): Map<String, String> =
    (get(name) as? JsonValue.Object)?.values?.mapValues { (_, value) ->
        (value as? JsonValue.StringValue)?.value ?: throw CloudContractException("INVALID_HARNESS_RESPONSE")
    } ?: throw CloudContractException("INVALID_HARNESS_RESPONSE")
