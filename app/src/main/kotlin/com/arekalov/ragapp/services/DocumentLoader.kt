package com.arekalov.ragapp.services

import mu.KotlinLogging
import java.io.File
import java.nio.file.Files
import java.nio.file.attribute.BasicFileAttributes

private val logger = KotlinLogging.logger {}

/**
 * Загрузчик документов из директории Obsidian
 */
class DocumentLoader(private val rootPath: String) {
    
    /**
     * Загрузка всех Markdown файлов из директории
     */
    fun loadDocuments(): List<LoadedDocument> {
        val rootDir = File(rootPath)
        
        if (!rootDir.exists()) {
            logger.error { "Директория не существует: $rootPath" }
            return emptyList()
        }
        
        if (!rootDir.isDirectory) {
            logger.error { "Путь не является директорией: $rootPath" }
            return emptyList()
        }
        
        logger.info { "Сканирование директории: $rootPath" }
        
        val documents = mutableListOf<LoadedDocument>()
        scanDirectory(rootDir, documents)
        
        logger.info { "Найдено ${documents.size} документов" }
        return documents
    }
    
    /**
     * Рекурсивное сканирование директории
     */
    private fun scanDirectory(directory: File, documents: MutableList<LoadedDocument>) {
        directory.listFiles()?.forEach { file ->
            when {
                file.isDirectory -> {
                    // Пропускаем скрытые директории (начинающиеся с точки)
                    if (!file.name.startsWith(".")) {
                        scanDirectory(file, documents)
                    }
                }
                file.isFile && file.extension.lowercase() == "md" -> {
                    try {
                        val document = loadDocument(file)
                        documents.add(document)
                        logger.debug { "Загружен документ: ${file.path}" }
                    } catch (e: Exception) {
                        logger.error(e) { "Ошибка при загрузке документа: ${file.path}" }
                    }
                }
            }
        }
    }
    
    /**
     * Загрузка одного документа
     */
    private fun loadDocument(file: File): LoadedDocument {
        val content = file.readText(Charsets.UTF_8)
        val metadata = extractMetadata(file)
        
        return LoadedDocument(
            path = file.absolutePath,
            content = content,
            metadata = metadata
        )
    }
    
    /**
     * Извлечение метаданных из файла
     */
    private fun extractMetadata(file: File): Map<String, String> {
        val metadata = mutableMapOf<String, String>()
        
        try {
            val attrs = Files.readAttributes(file.toPath(), BasicFileAttributes::class.java)
            
            metadata["fileName"] = file.name
            metadata["fileSize"] = file.length().toString()
            metadata["createdAt"] = attrs.creationTime().toString()
            metadata["modifiedAt"] = attrs.lastModifiedTime().toString()
            
            // Извлечение относительного пути
            val rootDir = File(rootPath)
            val relativePath = file.relativeTo(rootDir).path
            metadata["relativePath"] = relativePath
            
        } catch (e: Exception) {
            logger.warn(e) { "Не удалось извлечь метаданные для: ${file.path}" }
        }
        
        return metadata
    }
}

/**
 * Представляет загруженный документ
 */
data class LoadedDocument(
    val path: String,
    val content: String,
    val metadata: Map<String, String>
)

