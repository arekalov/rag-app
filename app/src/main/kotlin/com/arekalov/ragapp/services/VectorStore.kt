package com.arekalov.ragapp.services

import com.arekalov.ragapp.domain.Document
import com.arekalov.ragapp.domain.DocumentChunk
import com.arekalov.ragapp.domain.SearchResult
import kotlinx.serialization.encodeToString
import kotlinx.serialization.json.Json
import mu.KotlinLogging
import java.io.File
import java.sql.Connection
import java.sql.DriverManager
import kotlin.math.sqrt

private val logger = KotlinLogging.logger {}

/**
 * Хранилище векторных эмбеддингов в SQLite
 */
class VectorStore(private val databasePath: String) {
    private val connection: Connection

    init {
        // Создаем директорию если не существует
        File(databasePath).parentFile?.mkdirs()
        
        connection = DriverManager.getConnection("jdbc:sqlite:$databasePath")
        createTables()
        logger.info { "VectorStore инициализирован: $databasePath" }
    }

    /**
     * Создание таблиц в базе данных
     */
    private fun createTables() {
        connection.createStatement().use { statement ->
            // Таблица документов
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS documents (
                    id TEXT PRIMARY KEY,
                    path TEXT NOT NULL UNIQUE,
                    indexed_at INTEGER NOT NULL
                )
                """.trimIndent()
            )

            // Таблица чанков
            statement.executeUpdate(
                """
                CREATE TABLE IF NOT EXISTS chunks (
                    id TEXT PRIMARY KEY,
                    doc_id TEXT NOT NULL,
                    content TEXT NOT NULL,
                    embedding_json TEXT NOT NULL,
                    chunk_index INTEGER NOT NULL,
                    metadata_json TEXT NOT NULL,
                    FOREIGN KEY (doc_id) REFERENCES documents(id) ON DELETE CASCADE
                )
                """.trimIndent()
            )

            // Индекс для быстрого поиска по документу
            statement.executeUpdate(
                """
                CREATE INDEX IF NOT EXISTS idx_chunks_doc_id 
                ON chunks(doc_id)
                """.trimIndent()
            )
        }
    }

    /**
     * Сохранение документа
     */
    fun saveDocument(document: Document) {
        connection.prepareStatement(
            "INSERT OR REPLACE INTO documents (id, path, indexed_at) VALUES (?, ?, ?)"
        ).use { statement ->
            statement.setString(1, document.id)
            statement.setString(2, document.path)
            statement.setLong(3, document.indexedAt)
            statement.executeUpdate()
        }
        logger.debug { "Документ сохранен: ${document.path}" }
    }

    /**
     * Сохранение чанка с эмбеддингом
     */
    fun saveChunk(chunk: DocumentChunk) {
        connection.prepareStatement(
            """
            INSERT OR REPLACE INTO chunks 
            (id, doc_id, content, embedding_json, chunk_index, metadata_json) 
            VALUES (?, ?, ?, ?, ?, ?)
            """.trimIndent()
        ).use { statement ->
            statement.setString(1, chunk.id)
            statement.setString(2, chunk.documentPath)
            statement.setString(3, chunk.content)
            statement.setString(4, Json.encodeToString(chunk.embedding))
            statement.setInt(5, chunk.chunkIndex)
            statement.setString(6, Json.encodeToString(chunk.metadata))
            statement.executeUpdate()
        }
    }

    /**
     * Получение всех чанков
     */
    fun getAllChunks(): List<DocumentChunk> {
        val chunks = mutableListOf<DocumentChunk>()
        connection.createStatement().use { statement ->
            val resultSet = statement.executeQuery(
                "SELECT id, doc_id, content, embedding_json, chunk_index, metadata_json FROM chunks"
            )
            while (resultSet.next()) {
                chunks.add(
                    DocumentChunk(
                        id = resultSet.getString("id"),
                        documentPath = resultSet.getString("doc_id"),
                        content = resultSet.getString("content"),
                        embedding = Json.decodeFromString(resultSet.getString("embedding_json")),
                        chunkIndex = resultSet.getInt("chunk_index"),
                        metadata = Json.decodeFromString(resultSet.getString("metadata_json"))
                    )
                )
            }
        }
        return chunks
    }

    /**
     * Поиск похожих чанков по эмбеддингу запроса
     * Использует косинусное сходство
     */
    fun search(queryEmbedding: List<Float>, topK: Int = 5): List<SearchResult> {
        val allChunks = getAllChunks()
        
        return allChunks.map { chunk ->
            val similarity = cosineSimilarity(queryEmbedding, chunk.embedding)
            SearchResult(chunk, similarity)
        }
        .sortedByDescending { it.similarity }
        .take(topK)
    }

    /**
     * Вычисление косинусного сходства между двумя векторами
     */
    private fun cosineSimilarity(vec1: List<Float>, vec2: List<Float>): Double {
        require(vec1.size == vec2.size) { "Векторы должны быть одинакового размера" }
        
        var dotProduct = 0.0
        var norm1 = 0.0
        var norm2 = 0.0
        
        for (i in vec1.indices) {
            dotProduct += vec1[i] * vec2[i]
            norm1 += vec1[i] * vec1[i]
            norm2 += vec2[i] * vec2[i]
        }
        
        return dotProduct / (sqrt(norm1) * sqrt(norm2))
    }

    /**
     * Получение статистики базы данных
     */
    fun getStats(): Map<String, Int> {
        var documentCount = 0
        var chunkCount = 0
        
        connection.createStatement().use { statement ->
            val docResult = statement.executeQuery("SELECT COUNT(*) as count FROM documents")
            if (docResult.next()) {
                documentCount = docResult.getInt("count")
            }
            
            val chunkResult = statement.executeQuery("SELECT COUNT(*) as count FROM chunks")
            if (chunkResult.next()) {
                chunkCount = chunkResult.getInt("count")
            }
        }
        
        return mapOf(
            "documents" to documentCount,
            "chunks" to chunkCount
        )
    }

    /**
     * Проверка существования документа по пути
     */
    fun documentExists(path: String): Boolean {
        connection.prepareStatement(
            "SELECT COUNT(*) as count FROM documents WHERE path = ?"
        ).use { statement ->
            statement.setString(1, path)
            val resultSet = statement.executeQuery()
            return resultSet.next() && resultSet.getInt("count") > 0
        }
    }

    /**
     * Удаление документа и его чанков
     */
    fun deleteDocument(path: String) {
        connection.prepareStatement(
            "DELETE FROM documents WHERE path = ?"
        ).use { statement ->
            statement.setString(1, path)
            statement.executeUpdate()
        }
        logger.info { "Документ удален: $path" }
    }

    /**
     * Закрытие соединения с базой данных
     */
    fun close() {
        connection.close()
        logger.info { "VectorStore закрыт" }
    }
}

