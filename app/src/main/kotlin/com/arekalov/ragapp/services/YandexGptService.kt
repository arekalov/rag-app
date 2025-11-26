package com.arekalov.ragapp.services

import io.ktor.client.*
import io.ktor.client.call.*
import io.ktor.client.engine.cio.*
import io.ktor.client.plugins.contentnegotiation.*
import io.ktor.client.request.*
import io.ktor.http.*
import io.ktor.serialization.kotlinx.json.*
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Сервис для работы с Yandex GPT API
 */
class YandexGptService(
    private val apiKey: String,
    private val folderId: String
) {
    private val client = HttpClient(CIO) {
        install(ContentNegotiation) {
            json(Json {
                prettyPrint = true
                isLenient = true
                ignoreUnknownKeys = true
            })
        }
    }
    
    /**
     * Отправка сообщения в Yandex GPT
     */
    suspend fun chat(messages: List<YandexMessage>, temperature: Double = 0.7): String {
        try {
            val request = YandexGptRequest(
                modelUri = "gpt://$folderId/yandexgpt",
                completionOptions = YandexCompletionOptions(
                    stream = false,
                    temperature = temperature,
                    maxTokens = 8000
                ),
                messages = messages
            )
            
            val response = client.post("https://llm.api.cloud.yandex.net/foundationModels/v1/completion") {
                contentType(ContentType.Application.Json)
                bearerAuth(apiKey)
                setBody(request)
            }
            
            if (response.status == HttpStatusCode.OK) {
                val gptResponse = response.body<YandexGptResponse>()
                val alternative = gptResponse.result.alternatives.firstOrNull()
                    ?: throw Exception("Нет ответа от API")
                
                logger.debug { 
                    "Использовано токенов: ${gptResponse.result.usage.totalTokens}" 
                }
                
                return alternative.message.text
            } else {
                throw Exception("Ошибка API: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при запросе к Yandex GPT" }
            throw e
        }
    }
    
    /**
     * Закрытие HTTP клиента
     */
    fun close() {
        client.close()
        logger.info { "YandexGptService закрыт" }
    }
}

// DTO для Yandex GPT API

@Serializable
data class YandexGptRequest(
    val modelUri: String,
    val completionOptions: YandexCompletionOptions,
    val messages: List<YandexMessage>
)

@Serializable
data class YandexCompletionOptions(
    val stream: Boolean = false,
    val temperature: Double = 0.7,
    val maxTokens: Int = 8000
)

@Serializable
data class YandexMessage(
    val role: String,
    val text: String
)

@Serializable
data class YandexGptResponse(
    val result: YandexResultData
)

@Serializable
data class YandexResultData(
    val alternatives: List<YandexAlternative>,
    val usage: YandexUsage,
    val modelVersion: String
)

@Serializable
data class YandexAlternative(
    val message: YandexMessage,
    val status: String
)

@Serializable
data class YandexUsage(
    val inputTextTokens: String,
    val completionTokens: String,
    val totalTokens: String
)

