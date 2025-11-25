package com.arekalov.ragapp.pipeline

import com.arekalov.ragapp.config.Config
import com.arekalov.ragapp.domain.Document
import com.arekalov.ragapp.domain.DocumentChunk
import com.arekalov.ragapp.services.*
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import mu.KotlinLogging
import java.util.UUID

private val logger = KotlinLogging.logger {}

/**
 * Пайплайн для индексации документов
 * Координирует процесс загрузки, разбиения, генерации эмбеддингов и сохранения
 */
class IndexingPipeline(private val config: Config) {
    private val documentLoader = DocumentLoader(config.obsidianPath)
    private val textChunker = TextChunker(config.chunkSize, config.chunkOverlap)
    private val embeddingService = EmbeddingService(config.ollamaUrl, config.ollamaModel)
    private val vectorStore = VectorStore(config.databasePath)
    
    /**
     * Запуск полного процесса индексации
     */
    suspend fun index(forceReindex: Boolean = false): IndexingResult {
        logger.info { "Начало индексации документов" }
        val startTime = System.currentTimeMillis()
        
        try {
            // Проверка доступности Ollama
            if (!embeddingService.checkHealth()) {
                logger.error { "Ollama API недоступен. Убедитесь, что Ollama запущен на ${config.ollamaUrl}" }
                return IndexingResult(
                    success = false,
                    documentsProcessed = 0,
                    chunksCreated = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = "Ollama API недоступен"
                )
            }
            
            // Загрузка документов
            val documents = documentLoader.loadDocuments()
            if (documents.isEmpty()) {
                logger.warn { "Документы не найдены в ${config.obsidianPath}" }
                return IndexingResult(
                    success = true,
                    documentsProcessed = 0,
                    chunksCreated = 0,
                    durationMs = System.currentTimeMillis() - startTime,
                    error = null
                )
            }
            
            var documentsProcessed = 0
            var chunksCreated = 0
            
            // Обработка каждого документа
            for ((index, loadedDocument) in documents.withIndex()) {
                logger.info { "Обработка документа ${index + 1}/${documents.size}: ${loadedDocument.metadata["fileName"]}" }
                
                // Проверяем, не проиндексирован ли уже документ
                if (!forceReindex && vectorStore.documentExists(loadedDocument.path)) {
                    logger.info { "Документ уже проиндексирован, пропускаем: ${loadedDocument.path}" }
                    continue
                }
                
                // Разбиение на чанки
                val textChunks = textChunker.chunkByWords(loadedDocument.content)
                logger.debug { "Создано ${textChunks.size} чанков" }
                
                if (textChunks.isEmpty()) {
                    logger.warn { "Документ не содержит текста для индексации: ${loadedDocument.path}" }
                    continue
                }
                
                // Генерация эмбеддингов для всех чанков
                val embeddings = withContext(Dispatchers.IO) {
                    embeddingService.generateEmbeddings(textChunks.map { it.content })
                }
                
                // Сохранение документа
                val document = Document(
                    id = loadedDocument.path,
                    path = loadedDocument.path,
                    indexedAt = System.currentTimeMillis()
                )
                vectorStore.saveDocument(document)
                
                // Сохранение чанков с эмбеддингами
                textChunks.forEachIndexed { chunkIndex, textChunk ->
                    val documentChunk = DocumentChunk(
                        id = UUID.randomUUID().toString(),
                        documentPath = loadedDocument.path,
                        content = textChunk.content,
                        embedding = embeddings[chunkIndex],
                        chunkIndex = textChunk.index,
                        metadata = loadedDocument.metadata
                    )
                    vectorStore.saveChunk(documentChunk)
                    chunksCreated++
                }
                
                documentsProcessed++
                logger.info { "Документ обработан: ${loadedDocument.metadata["fileName"]} (${textChunks.size} чанков)" }
            }
            
            val duration = System.currentTimeMillis() - startTime
            logger.info { "Индексация завершена: $documentsProcessed документов, $chunksCreated чанков за ${duration}мс" }
            
            return IndexingResult(
                success = true,
                documentsProcessed = documentsProcessed,
                chunksCreated = chunksCreated,
                durationMs = duration,
                error = null
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при индексации" }
            return IndexingResult(
                success = false,
                documentsProcessed = 0,
                chunksCreated = 0,
                durationMs = System.currentTimeMillis() - startTime,
                error = e.message
            )
        }
    }
    
    /**
     * Поиск по индексу
     */
    suspend fun search(query: String, topK: Int = 5): SearchResultData {
        logger.info { "Поиск: '$query'" }
        
        try {
            // Генерация эмбеддинга для запроса
            val queryEmbedding = withContext(Dispatchers.IO) {
                embeddingService.generateEmbedding(query)
            }
            
            // Поиск похожих чанков
            val results = vectorStore.search(queryEmbedding, topK)
            
            logger.info { "Найдено ${results.size} результатов" }
            return SearchResultData(
                success = true,
                results = results,
                error = null
            )
            
        } catch (e: Exception) {
            logger.error(e) { "Ошибка при поиске" }
            return SearchResultData(
                success = false,
                results = emptyList(),
                error = e.message
            )
        }
    }
    
    /**
     * Получение статистики индекса
     */
    fun getStats(): Map<String, Int> {
        return vectorStore.getStats()
    }
    
    /**
     * Закрытие ресурсов
     */
    fun close() {
        embeddingService.close()
        vectorStore.close()
        logger.info { "IndexingPipeline закрыт" }
    }
}

/**
 * Результат индексации
 */
data class IndexingResult(
    val success: Boolean,
    val documentsProcessed: Int,
    val chunksCreated: Int,
    val durationMs: Long,
    val error: String?
)

/**
 * Результат поиска
 */
data class SearchResultData(
    val success: Boolean,
    val results: List<com.arekalov.ragapp.domain.SearchResult>,
    val error: String?
)

