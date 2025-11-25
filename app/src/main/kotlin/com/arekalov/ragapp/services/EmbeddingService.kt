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
 * Сервис для генерации эмбеддингов через Ollama API
 */
class EmbeddingService(
    private val ollamaUrl: String,
    private val model: String
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
     * Генерация эмбеддинга для текста
     */
    suspend fun generateEmbedding(text: String): List<Float> {
        if (text.isBlank()) {
            logger.warn { "Попытка генерации эмбеддинга для пустого текста" }
            return emptyList()
        }
        
        try {
            val response = client.post("$ollamaUrl/api/embeddings") {
                contentType(ContentType.Application.Json)
                setBody(
                    OllamaEmbeddingRequest(
                        model = model,
                        prompt = text
                    )
                )
            }
            
            if (response.status == HttpStatusCode.OK) {
                val embeddingResponse = response.body<OllamaEmbeddingResponse>()
                logger.debug { "Получен эмбеддинг размерности ${embeddingResponse.embedding.size}" }
                return embeddingResponse.embedding
            } else {
                logger.error { "Ошибка при получении эмбеддинга: ${response.status}" }
                throw Exception("Не удалось получить эмбеддинг: ${response.status}")
            }
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при запросе к Ollama API" }
            throw e
        }
    }
    
    /**
     * Генерация эмбеддингов для списка текстов
     */
    suspend fun generateEmbeddings(texts: List<String>): List<List<Float>> {
        logger.info { "Генерация эмбеддингов для ${texts.size} текстов" }
        
        return texts.mapIndexed { index, text ->
            if (index > 0 && index % 10 == 0) {
                logger.info { "Обработано $index/${texts.size} текстов" }
            }
            generateEmbedding(text)
        }
    }
    
    /**
     * Проверка доступности Ollama API
     */
    suspend fun checkHealth(): Boolean {
        return try {
            val response = client.get("$ollamaUrl/api/tags")
            response.status == HttpStatusCode.OK
        } catch (e: Exception) {
            logger.error(e) { "Ollama API недоступен" }
            false
        }
    }
    
    /**
     * Закрытие HTTP клиента
     */
    fun close() {
        client.close()
        logger.info { "EmbeddingService закрыт" }
    }
}

/**
 * Запрос к Ollama API для генерации эмбеддинга
 */
@Serializable
data class OllamaEmbeddingRequest(
    val model: String,
    val prompt: String
)

/**
 * Ответ от Ollama API с эмбеддингом
 */
@Serializable
data class OllamaEmbeddingResponse(
    val embedding: List<Float>
)

