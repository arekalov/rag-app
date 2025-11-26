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
    suspend fun generateEmbedding(text: String, retries: Int = 3): List<Float> {
        if (text.isBlank()) {
            logger.warn { "Попытка генерации эмбеддинга для пустого текста" }
            return emptyList()
        }
        
        // Ограничение длины текста (Ollama может не справиться с очень длинными текстами)
        val maxLength = 8000
        val truncatedText = if (text.length > maxLength) {
            logger.warn { "Текст обрезан с ${text.length} до $maxLength символов" }
            text.take(maxLength)
        } else {
            text
        }
        
        var lastException: Exception? = null
        
        repeat(retries) { attempt ->
            try {
                val response = client.post("$ollamaUrl/api/embeddings") {
                    contentType(ContentType.Application.Json)
                    setBody(
                        OllamaEmbeddingRequest(
                            model = model,
                            prompt = truncatedText
                        )
                    )
                }
                
                if (response.status == HttpStatusCode.OK) {
                    val embeddingResponse = response.body<OllamaEmbeddingResponse>()
                    logger.debug { "Получен эмбеддинг размерности ${embeddingResponse.embedding.size}" }
                    return embeddingResponse.embedding
                } else {
                    val errorMsg = "Ошибка при получении эмбеддинга: ${response.status}"
                    logger.error { errorMsg }
                    lastException = Exception(errorMsg)
                    
                    // Задержка перед повторной попыткой
                    if (attempt < retries - 1) {
                        val delayMs = (attempt + 1) * 1000L
                        logger.warn { "Повторная попытка ${attempt + 1}/$retries через ${delayMs}мс..." }
                        kotlinx.coroutines.delay(delayMs)
                    }
                }
            } catch (e: Exception) {
                logger.error(e) { "Ошибка при запросе к Ollama API (попытка ${attempt + 1}/$retries)" }
                lastException = e
                
                // Задержка перед повторной попыткой
                if (attempt < retries - 1) {
                    val delayMs = (attempt + 1) * 1000L
                    logger.warn { "Повторная попытка ${attempt + 1}/$retries через ${delayMs}мс..." }
                    kotlinx.coroutines.delay(delayMs)
                }
            }
        }
        
        throw lastException ?: Exception("Не удалось получить эмбеддинг после $retries попыток")
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
            
            // Небольшая задержка между запросами, чтобы не перегружать Ollama
            if (index > 0) {
                kotlinx.coroutines.delay(100)
            }
            
            try {
                generateEmbedding(text)
            } catch (e: Exception) {
                logger.error(e) { "Не удалось сгенерировать эмбеддинг для текста ${index + 1}/${texts.size}" }
                throw e
            }
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

