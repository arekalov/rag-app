package com.arekalov.ragapp.domain

import kotlinx.serialization.Serializable

/**
 * Представляет один чанк (фрагмент) документа с его эмбеддингом
 */
@Serializable
data class DocumentChunk(
    val id: String,
    val documentPath: String,
    val content: String,
    val embedding: List<Float>,
    val chunkIndex: Int,
    val metadata: Map<String, String> = emptyMap()
)

/**
 * Представляет документ в базе данных
 */
data class Document(
    val id: String,
    val path: String,
    val indexedAt: Long
)

/**
 * Результат поиска
 */
data class SearchResult(
    val chunk: DocumentChunk,
    val similarity: Double
)

