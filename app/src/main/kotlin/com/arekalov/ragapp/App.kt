package com.arekalov.ragapp

import com.arekalov.ragapp.config.Config
import com.arekalov.ragapp.pipeline.IndexingPipeline
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) = runBlocking {
    if (args.isEmpty()) {
        printUsage()
        return@runBlocking
    }
    
    val command = args[0]
    
    try {
        // Загрузка конфигурации
        val config = Config.load()
        
        // Создание пайплайна
        val pipeline = IndexingPipeline(config)
        
        try {
            when (command.lowercase()) {
                "index" -> {
                    val forceReindex = args.contains("--force")
                    handleIndex(pipeline, forceReindex)
                }
                "search" -> {
                    if (args.size < 2) {
                        println("❌ Ошибка: необходимо указать поисковый запрос")
                        println("Использование: search <запрос>")
                        return@runBlocking
                    }
                    val query = args.drop(1).joinToString(" ")
                    handleSearch(pipeline, query)
                }
                "stats" -> {
                    handleStats(pipeline)
                }
                "help" -> {
                    printUsage()
                }
                else -> {
                    println("❌ Неизвестная команда: $command")
                    printUsage()
                }
            }
        } finally {
            pipeline.close()
        }
        
    } catch (e: Exception) {
        logger.error(e) { "Критическая ошибка при выполнении команды" }
        println("❌ Ошибка: ${e.message}")
    }
}

/**
 * Обработка команды индексации
 */
suspend fun handleIndex(pipeline: IndexingPipeline, forceReindex: Boolean) {
    println("🔍 Начинаем индексацию документов...")
    println()
    
    val result = pipeline.index(forceReindex)
    
    if (result.success) {
        println("✅ Индексация завершена успешно!")
        println()
        println("📊 Статистика:")
        println("  • Обработано документов: ${result.documentsProcessed}")
        println("  • Создано чанков: ${result.chunksCreated}")
        println("  • Время выполнения: ${result.durationMs / 1000.0} сек")
        println()
        
        // Показываем общую статистику
        val stats = pipeline.getStats()
        println("📈 Общая статистика индекса:")
        println("  • Всего документов: ${stats["documents"]}")
        println("  • Всего чанков: ${stats["chunks"]}")
    } else {
        println("❌ Ошибка при индексации:")
        println("  ${result.error}")
        println()
        
        if (result.error?.contains("Ollama API недоступен") == true) {
            println("💡 Убедитесь, что:")
            println("  1. Ollama установлен и запущен")
            println("  2. Модель nomic-embed-text доступна (ollama pull nomic-embed-text)")
            println("  3. Ollama доступен по адресу из конфига")
        }
    }
}

/**
 * Обработка команды поиска
 */
suspend fun handleSearch(pipeline: IndexingPipeline, query: String) {
    println("🔍 Поиск: '$query'")
    println()
    
    val result = pipeline.search(query, topK = 5)
    
    if (result.success) {
        if (result.results.isEmpty()) {
            println("🤷 Результатов не найдено")
            return
        }
        
        println("📝 Найдено ${result.results.size} результатов:")
        println()
        
        result.results.forEachIndexed { index, searchResult ->
            val similarity = String.format("%.4f", searchResult.similarity)
            val fileName = searchResult.chunk.metadata["fileName"] ?: "unknown"
            
            println("${index + 1}. Документ: $fileName")
            println("   Сходство: $similarity")
            println("   Чанк #${searchResult.chunk.chunkIndex}")
            println()
            
            // Показываем первые 200 символов контента
            val preview = if (searchResult.chunk.content.length > 200) {
                searchResult.chunk.content.take(200) + "..."
            } else {
                searchResult.chunk.content
            }
            println("   Содержимое:")
            preview.lines().forEach { line ->
                println("   │ $line")
            }
            println()
        }
    } else {
        println("❌ Ошибка при поиске:")
        println("  ${result.error}")
    }
}

/**
 * Обработка команды статистики
 */
fun handleStats(pipeline: IndexingPipeline) {
    println("📊 Статистика индекса")
    println()
    
    val stats = pipeline.getStats()
    
    if (stats["documents"] == 0) {
        println("⚠️  Индекс пуст. Запустите 'index' для индексации документов.")
        return
    }
    
    println("📈 Данные:")
    println("  • Проиндексировано документов: ${stats["documents"]}")
    println("  • Всего чанков: ${stats["chunks"]}")
    
    val avgChunksPerDoc = if (stats["documents"]!! > 0) {
        stats["chunks"]!! / stats["documents"]!!
    } else {
        0
    }
    println("  • Среднее число чанков на документ: $avgChunksPerDoc")
    println()
}

/**
 * Вывод справки
 */
fun printUsage() {
    println()
    println("╔═══════════════════════════════════════════════════════════════╗")
    println("║              RAG App - Document Indexing System              ║")
    println("╚═══════════════════════════════════════════════════════════════╝")
    println()
    println("Использование:")
    println("  ./gradlew run --args=\"<команда> [опции]\"")
    println()
    println("Команды:")
    println("  index [--force]  - Индексировать документы из Obsidian")
    println("                     --force: переиндексировать все документы")
    println("  search <запрос>  - Найти документы по запросу")
    println("  stats            - Показать статистику индекса")
    println("  help             - Показать эту справку")
    println()
    println("Примеры:")
    println("  ./gradlew run --args=\"index\"")
    println("  ./gradlew run --args=\"search kotlin coroutines\"")
    println("  ./gradlew run --args=\"stats\"")
    println()
    println("Конфигурация:")
    println("  Редактируйте app/src/main/resources/application.conf")
    println()
}
