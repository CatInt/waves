package io.waves.youtube

import io.ktor.client.HttpClient
import io.ktor.client.request.header
import io.ktor.client.request.post
import io.ktor.client.request.setBody
import io.ktor.client.statement.HttpResponse
import io.ktor.client.statement.bodyAsText
import io.ktor.http.ContentType
import io.ktor.http.HttpHeaders
import io.ktor.http.contentType
import io.ktor.http.isSuccess
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.longOrNull

internal val youtubeJson = Json { ignoreUnknownKeys = true; isLenient = true }

internal suspend fun HttpClient.postJson(url: String, body: String, userAgent: String, extraHeaders: Map<String, String> = emptyMap()): String {
    val response = post(url) {
        contentType(ContentType.Application.Json)
        header(HttpHeaders.UserAgent, userAgent)
        header(HttpHeaders.Origin, "https://www.youtube.com")
        header(HttpHeaders.Accept, "application/json")
        extraHeaders.forEach { (k, v) -> header(k, v) }
        setBody(body)
    }
    return response.bodyOrThrow()
}

private suspend fun HttpResponse.bodyOrThrow(): String {
    val text = bodyAsText()
    if (!status.isSuccess()) throw YoutubeException("YouTube returned HTTP ${status.value}")
    return text
}

internal fun jsonObject(text: String): JsonObject? =
    runCatching { youtubeJson.parseToJsonElement(text) as? JsonObject }.getOrNull()

internal fun JsonObject.obj(key: String): JsonObject? = this[key] as? JsonObject

internal fun JsonObject.string(key: String): String? =
    (this[key] as? JsonPrimitive)?.takeIf { it.isString }?.content?.takeIf { it.isNotEmpty() }

internal fun JsonObject.long(key: String): Long? = (this[key] as? JsonPrimitive)?.longOrNull
