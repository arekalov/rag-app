package com.arekalov.ragapp.services

import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

/**
 * Разбиение текста на чанки фиксированного размера
 */
class TextChunker(
    private val chunkSize: Int,
    private val overlap: Int
) {
    
    init {
        require(chunkSize > 0) { "Размер чанка должен быть положительным" }
        require(overlap >= 0) { "Перекрытие должно быть неотрицательным" }
        require(overlap < chunkSize) { "Перекрытие должно быть меньше размера чанка" }
    }
    
    /**
     * Разбиение текста на чанки
     * Возвращает список чанков с сохранением контекста
     */
    fun chunk(text: String): List<TextChunk> {
        if (text.isBlank()) {
            logger.warn { "Пустой текст для разбиения" }
            return emptyList()
        }
        
        val chunks = mutableListOf<TextChunk>()
        var currentPosition = 0
        var chunkIndex = 0
        
        while (currentPosition < text.length) {
            val endPosition = minOf(currentPosition + chunkSize, text.length)
            val chunkText = text.substring(currentPosition, endPosition)
            
            // Пропускаем пустые чанки
            if (chunkText.isNotBlank()) {
                chunks.add(
                    TextChunk(
                        content = chunkText.trim(),
                        index = chunkIndex,
                        startPosition = currentPosition,
                        endPosition = endPosition
                    )
                )
                chunkIndex++
            }
            
            // Двигаемся вперед с учетом перекрытия
            currentPosition += chunkSize - overlap
        }
        
        logger.debug { "Текст разбит на ${chunks.size} чанков (размер: $chunkSize, перекрытие: $overlap)" }
        return chunks
    }
    
    /**
     * Разбиение текста на чанки по словам (более умный подход)
     * Старается не разрывать слова
     */
    fun chunkByWords(text: String): List<TextChunk> {
        if (text.isBlank()) {
            logger.warn { "Пустой текст для разбиения" }
            return emptyList()
        }
        
        val chunks = mutableListOf<TextChunk>()
        val words = text.split(Regex("\\s+"))
        
        var currentChunk = StringBuilder()
        var chunkIndex = 0
        var startPosition = 0
        var currentWordIndex = 0
        
        while (currentWordIndex < words.size) {
            val word = words[currentWordIndex]
            
            // Проверяем, не превысит ли добавление слова размер чанка
            if (currentChunk.length + word.length + 1 > chunkSize && currentChunk.isNotEmpty()) {
                // Сохраняем текущий чанк
                val chunkText = currentChunk.toString().trim()
                chunks.add(
                    TextChunk(
                        content = chunkText,
                        index = chunkIndex,
                        startPosition = startPosition,
                        endPosition = startPosition + chunkText.length
                    )
                )
                chunkIndex++
                
                // Создаем перекрытие: берем последние несколько слов из текущего чанка
                val wordsInChunk = currentChunk.toString().split(Regex("\\s+"))
                val overlapWords = if (overlap > 0 && wordsInChunk.size > 1) {
                    val overlapWordCount = maxOf(1, wordsInChunk.size / 4) // ~25% слов для перекрытия
                    wordsInChunk.takeLast(overlapWordCount).joinToString(" ")
                } else {
                    ""
                }
                
                startPosition += chunkText.length - overlapWords.length
                currentChunk = StringBuilder(overlapWords)
                if (overlapWords.isNotEmpty()) {
                    currentChunk.append(" ")
                }
            }
            
            // Добавляем слово к текущему чанку
            if (currentChunk.isNotEmpty()) {
                currentChunk.append(" ")
            }
            currentChunk.append(word)
            currentWordIndex++
        }
        
        // Добавляем последний чанк
        if (currentChunk.isNotEmpty()) {
            val chunkText = currentChunk.toString().trim()
            chunks.add(
                TextChunk(
                    content = chunkText,
                    index = chunkIndex,
                    startPosition = startPosition,
                    endPosition = startPosition + chunkText.length
                )
            )
        }
        
        logger.debug { "Текст разбит на ${chunks.size} чанков по словам (размер: $chunkSize, перекрытие: $overlap)" }
        return chunks
    }
}

/**
 * Представляет один чанк текста
 */
data class TextChunk(
    val content: String,
    val index: Int,
    val startPosition: Int,
    val endPosition: Int
)

