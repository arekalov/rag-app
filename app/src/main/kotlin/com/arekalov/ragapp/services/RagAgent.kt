package com.arekalov.ragapp.services

import com.arekalov.ragapp.domain.SearchResult
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * RAG (Retrieval-Augmented Generation) агент
 * Поддерживает два режима: с использованием RAG и без него
 */
class RagAgent(
    private val vectorStore: VectorStore,
    private val embeddingService: EmbeddingService,
    private val yandexGptService: YandexGptService
) {
    
    /**
     * Получение ответа с использованием RAG
     * Вопрос → Поиск релевантных чанков → Объединение с вопросом → Запрос к LLM
     */
    suspend fun answerWithRag(question: String, topK: Int = 3): RagResponse {
        logger.info { "Ответ с RAG на вопрос: '$question'" }
        
        val startTime = System.currentTimeMillis()
        
        try {
            // Шаг 1: Генерация эмбеддинга для вопроса
            val queryEmbedding = embeddingService.generateEmbedding(question)
            logger.debug { "Эмбеддинг сгенерирован" }
            
            // Шаг 2: Поиск релевантных чанков
            val searchResults = vectorStore.search(queryEmbedding, topK)
            logger.debug { "Найдено ${searchResults.size} релевантных чанков" }
            
            if (searchResults.isEmpty()) {
                return RagResponse(
                    answer = "К сожалению, я не нашел релевантной информации в базе знаний для ответа на ваш вопрос.",
                    usedContext = emptyList(),
                    durationMs = System.currentTimeMillis() - startTime,
                    mode = "RAG (нет контекста)"
                )
            }
            
            // Проверка релевантности результатов
            val maxSimilarity = searchResults.maxOfOrNull { it.similarity } ?: 0.0
            logger.debug { "Максимальная релевантность: ${String.format("%.2f", maxSimilarity * 100)}%" }
            
            // Если релевантность слишком низкая, сообщаем об этом
            if (maxSimilarity < 0.6) {
                val lowRelevanceNote = "\n\n⚠️ Примечание: Релевантность найденных документов низкая (${String.format("%.1f", maxSimilarity * 100)}%). Возможно, в вашей базе знаний нет информации по этому вопросу. Попробуйте режим 'norag' для общего ответа."
                
                return RagResponse(
                    answer = "В предоставленных документах нет достаточно релевантной информации для ответа на этот вопрос.$lowRelevanceNote",
                    usedContext = searchResults,
                    durationMs = System.currentTimeMillis() - startTime,
                    mode = "RAG (низкая релевантность)"
                )
            }
            
            // Шаг 3: Формирование контекста из найденных чанков
            val context = buildContext(searchResults)
            
            // Шаг 4: Формирование промпта
            val prompt = buildRagPrompt(question, context)
            
            // Шаг 5: Запрос к LLM
            val messages = listOf(
                YandexMessage(role = "system", text = "Ты полезный ассистент. Отвечай на вопросы, используя предоставленный контекст. Если контекст частично релевантен, постарайся дать полезный ответ на основе того, что есть. Будь конкретным и информативным."),
                YandexMessage(role = "user", text = prompt)
            )
            
            val answer = yandexGptService.chat(messages, temperature = 0.3)
            
            val duration = System.currentTimeMillis() - startTime
            logger.info { "Ответ с RAG получен за ${duration}мс" }
            
            return RagResponse(
                answer = answer,
                usedContext = searchResults,
                durationMs = duration,
                mode = "RAG"
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при получении ответа с RAG" }
            throw e
        }
    }
    
    /**
     * Получение ответа БЕЗ использования RAG
     * Прямой запрос к LLM без контекста из базы знаний
     */
    suspend fun answerWithoutRag(question: String): RagResponse {
        logger.info { "Ответ без RAG на вопрос: '$question'" }
        
        val startTime = System.currentTimeMillis()
        
        try {
            val messages = listOf(
                YandexMessage(role = "system", text = "Ты полезный ассистент. Отвечай кратко и по существу на основе своих знаний."),
                YandexMessage(role = "user", text = question)
            )
            
            val answer = yandexGptService.chat(messages, temperature = 0.7)
            
            val duration = System.currentTimeMillis() - startTime
            logger.info { "Ответ без RAG получен за ${duration}мс" }
            
            return RagResponse(
                answer = answer,
                usedContext = emptyList(),
                durationMs = duration,
                mode = "Без RAG"
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при получении ответа без RAG" }
            throw e
        }
    }
    
    /**
     * Сравнение ответов с RAG и без RAG
     */
    suspend fun compare(question: String, topK: Int = 3): ComparisonResult {
        logger.info { "Сравнение режимов для вопроса: '$question'" }
        
        val withRag = answerWithRag(question, topK)
        val withoutRag = answerWithoutRag(question)
        
        return ComparisonResult(
            question = question,
            withRag = withRag,
            withoutRag = withoutRag
        )
    }
    
    /**
     * Построение контекста из результатов поиска
     */
    private fun buildContext(searchResults: List<SearchResult>): String {
        val context = searchResults.mapIndexed { index, result ->
            val fileName = result.chunk.metadata["fileName"] ?: "unknown"
            val similarity = String.format("%.2f", result.similarity * 100)
            
            """
            [Документ ${index + 1}: $fileName, релевантность: $similarity%]
            ${result.chunk.content}
            """.trimIndent()
        }.joinToString("\n\n---\n\n")
        
        // Логируем контекст для отладки
        logger.debug { "Контекст для LLM (${context.length} символов):\n$context" }
        
        return context
    }
    
    /**
     * Построение промпта для RAG
     */
    private fun buildRagPrompt(question: String, context: String): String {
        return """
        Контекст из базы знаний пользователя:
        
        $context
        
        ---
        
        Вопрос: $question
        
        Инструкции:
        - Ответь на вопрос, используя информацию из предоставленного контекста
        - Если контекст содержит релевантную информацию, используй её для ответа
        - Если контекст только частично релевантен, постарайся дать полезный ответ на основе того, что есть
        - Если контекст совсем не связан с вопросом, честно скажи об этом
        - Будь конкретным и информативным
        """.trimIndent()
    }
    
    /**
     * Закрытие ресурсов
     */
    fun close() {
        yandexGptService.close()
        logger.info { "RagAgent закрыт" }
    }
}

/**
 * Ответ от RAG агента
 */
data class RagResponse(
    val answer: String,
    val usedContext: List<SearchResult>,
    val durationMs: Long,
    val mode: String
)

/**
 * Результат сравнения режимов
 */
data class ComparisonResult(
    val question: String,
    val withRag: RagResponse,
    val withoutRag: RagResponse
)

