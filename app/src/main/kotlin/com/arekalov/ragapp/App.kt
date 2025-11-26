package com.arekalov.ragapp

import com.arekalov.ragapp.config.Config
import com.arekalov.ragapp.pipeline.IndexingPipeline
import com.arekalov.ragapp.services.*
import kotlinx.coroutines.runBlocking
import mu.KotlinLogging

private val logger = KotlinLogging.logger {}

fun main(args: Array<String>) = runBlocking {
    printBanner()
    
    try {
        // Загрузка конфигурации
        val config = Config.load()
        
        // Инициализация компонентов
        val embeddingService = EmbeddingService(config.ollamaUrl, config.ollamaModel)
        val vectorStore = VectorStore(config.databasePath)
        val yandexGptService = YandexGptService(config.yandexApiKey, config.yandexFolderId)
        val ragAgent = RagAgent(vectorStore, embeddingService, yandexGptService)
        val pipeline = IndexingPipeline(config)
        
        try {
            // Главный цикл приложения
            mainLoop(pipeline, ragAgent)
        } finally {
            // Закрытие ресурсов
            ragAgent.close()
            embeddingService.close()
            vectorStore.close()
        }
        
    } catch (e: Exception) {
        logger.error(e) { "Критическая ошибка" }
        println("❌ Ошибка: ${e.message}")
    }
}

/**
 * Главный цикл приложения
 */
suspend fun mainLoop(pipeline: IndexingPipeline, ragAgent: RagAgent) {
    var isRunning = true
    
    while (isRunning) {
        printMenu()
        print("Выберите команду: ")
        System.out.flush() // Принудительный flush для корректного отображения
        
        val input = readLine()?.trim()?.lowercase()
        
        // Если readLine() вернул null (нет консоли), выходим
        if (input == null) {
            println()
            println("❌ Ошибка: невозможно прочитать ввод.")
            println("💡 Запустите приложение напрямую:")
            println("   ./app/build/install/app/bin/app")
            println("   или")
            println("   ./gradlew run --console=plain < /dev/tty")
            break
        }
        
        when (input) {
            "1", "index" -> handleIndex(pipeline)
            "2", "rag" -> handleChat(ragAgent, useRag = true)
            "3", "norag" -> handleChat(ragAgent, useRag = false)
            "4", "compare" -> handleCompare(ragAgent)
            "5", "stats" -> handleStats(pipeline)
            "help" -> printMenu()
            "exit", "quit", "0" -> {
                println("\n👋 До свидания!")
                isRunning = false
            }
            else -> {
                println("❌ Неизвестная команда. Введите 'help' для справки")
            }
        }
        
        if (isRunning) {
            println()
        }
    }
}

/**
 * Вывод баннера
 */
fun printBanner() {
    println()
    println("╔═══════════════════════════════════════════════════════════════╗")
    println("║           RAG App - Retrieval-Augmented Generation          ║")
    println("║              День 15-16: Индексация + RAG агент              ║")
    println("╚═══════════════════════════════════════════════════════════════╝")
    println()
}

/**
 * Вывод меню
 */
fun printMenu() {
    println("═══════════════════════════════════════════════════════════════")
    println("📋 Команды:")
    println("  1 (или 'index')   - Индексировать документы")
    println("  2 (или 'rag')     - Чат с использованием RAG")
    println("  3 (или 'norag')   - Чат БЕЗ использования RAG")
    println("  4 (или 'compare') - Сравнить RAG vs без RAG")
    println("  5 (или 'stats')   - Статистика индекса")
    println("  0 (или 'exit')    - Выход")
    println("═══════════════════════════════════════════════════════════════")
}

/**
 * Обработка команды индексации
 */
suspend fun handleIndex(pipeline: IndexingPipeline) {
    println("\n🔍 Начинаем индексацию документов...")
    print("Переиндексировать все документы? (y/n): ")
    val forceReindex = readLine()?.trim()?.lowercase() == "y"
    
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
        
        val stats = pipeline.getStats()
        println("📈 Общая статистика индекса:")
        println("  • Всего документов: ${stats["documents"]}")
        println("  • Всего чанков: ${stats["chunks"]}")
    } else {
        println("❌ Ошибка при индексации: ${result.error}")
    }
}

/**
 * Обработка чата
 */
suspend fun handleChat(ragAgent: RagAgent, useRag: Boolean) {
    val mode = if (useRag) "с RAG" else "БЕЗ RAG"
    println("\n╔═══════════════════════════════════════════════════════════════╗")
    println("║ 💬 Режим чата $mode")
    println("║ 💡 Введите 'back' для возврата в главное меню")
    println("╚═══════════════════════════════════════════════════════════════╝")
    println()
    
    while (true) {
        print("Вопрос: ")
        System.out.flush()
        
        val question = readLine()?.trim()
        
        // Проверка на null
        if (question == null) {
            println("\n❌ Ошибка чтения ввода. Возврат в меню.")
            break
        }
        
        if (question.lowercase() == "back") {
            break
        }
        
        if (question.isEmpty()) {
            continue
        }
        
        try {
            println()
            println("🤔 Думаю...")
            
            val response = if (useRag) {
                ragAgent.answerWithRag(question)
            } else {
                ragAgent.answerWithoutRag(question)
            }
            
            println()
            println("═══════════════════════════════════════════════════════════════")
            println("🤖 Ответ (${response.mode}):")
            println()
            println(response.answer)
            println()
            
            if (useRag && response.usedContext.isNotEmpty()) {
                println("📚 Использован контекст из документов:")
                response.usedContext.forEachIndexed { index, result ->
                    val fileName = result.chunk.metadata["fileName"] ?: "unknown"
                    val similarity = String.format("%.2f", result.similarity * 100)
                    println("  ${index + 1}. $fileName (релевантность: $similarity%)")
                }
                println()
            }
            
            println("⏱️  Время ответа: ${response.durationMs / 1000.0} сек")
            println("═══════════════════════════════════════════════════════════════")
            println()
            
        } catch (e: Exception) {
            println("❌ Ошибка: ${e.message}")
            println()
        }
    }
}

/**
 * Обработка сравнения режимов
 */
suspend fun handleCompare(ragAgent: RagAgent) {
    println("\n🔄 Сравнение режимов RAG vs без RAG")
    println()
    print("Введите вопрос для сравнения: ")
    val question = readLine()?.trim() ?: return
    
    if (question.isEmpty()) {
        return
    }
    
    try {
        println()
        println("🤔 Получаю ответы в обоих режимах...")
        println()
        
        val comparison = ragAgent.compare(question)
        
        println("╔═══════════════════════════════════════════════════════════════╗")
        println("║                     СРАВНЕНИЕ РЕЗУЛЬТАТОВ                      ║")
        println("╚═══════════════════════════════════════════════════════════════╝")
        println()
        println("❓ Вопрос: $question")
        println()
        
        // Ответ С RAG
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ 🔍 ОТВЕТ С RAG                                              │")
        println("└─────────────────────────────────────────────────────────────┘")
        println()
        println(comparison.withRag.answer)
        println()
        if (comparison.withRag.usedContext.isNotEmpty()) {
            println("📚 Источники:")
            comparison.withRag.usedContext.forEachIndexed { index, result ->
                val fileName = result.chunk.metadata["fileName"] ?: "unknown"
                val similarity = String.format("%.2f", result.similarity * 100)
                println("  ${index + 1}. $fileName (релевантность: $similarity%)")
            }
            println()
        }
        println("⏱️  Время: ${comparison.withRag.durationMs / 1000.0} сек")
        println()
        
        // Ответ БЕЗ RAG
        println("┌─────────────────────────────────────────────────────────────┐")
        println("│ 🧠 ОТВЕТ БЕЗ RAG (только знания модели)                     │")
        println("└─────────────────────────────────────────────────────────────┘")
        println()
        println(comparison.withoutRag.answer)
        println()
        println("⏱️  Время: ${comparison.withoutRag.durationMs / 1000.0} сек")
        println()
        
        // Выводы
        println("╔═══════════════════════════════════════════════════════════════╗")
        println("║                          ВЫВОДЫ                                ║")
        println("╚═══════════════════════════════════════════════════════════════╝")
        println()
        
        val timeDiff = comparison.withRag.durationMs - comparison.withoutRag.durationMs
        
        // Проверяем, действительно ли RAG помог (не просто нашел контекст, а дал полезный ответ)
        val ragAnswerLower = comparison.withRag.answer.lowercase()
        val ragWasHelpful = comparison.withRag.usedContext.isNotEmpty() && 
                           !ragAnswerLower.contains("нет информации") &&
                           !ragAnswerLower.contains("не содержит") &&
                           !ragAnswerLower.contains("недостаточно") &&
                           comparison.withRag.answer.length > 50  // Полноценный ответ
        
        if (ragWasHelpful) {
            println("✅ RAG помог:")
            println("  • Ответ основан на конкретных документах из вашей базы")
            println("  • Использованы ${comparison.withRag.usedContext.size} релевантных фрагментов")
            
            val avgSimilarity = comparison.withRag.usedContext.map { it.similarity }.average() * 100
            println("  • Средняя релевантность: ${String.format("%.1f", avgSimilarity)}%")
            println("  • Ответ более специфичен и основан на ваших данных")
        } else if (comparison.withRag.usedContext.isNotEmpty()) {
            println("⚠️  RAG нашел документы, но они не содержали нужной информации:")
            val maxSimilarity = comparison.withRag.usedContext.maxOfOrNull { it.similarity }?.times(100) ?: 0.0
            println("  • Найдено ${comparison.withRag.usedContext.size} документов (макс. релевантность: ${String.format("%.1f", maxSimilarity)}%)")
            println("  • Однако информация в них не релевантна вопросу")
            println("  • Возможно, нужно переформулировать вопрос или добавить больше документов")
        } else {
            println("⚠️  RAG не нашел релевантного контекста:")
            println("  • В базе знаний нет информации по этому вопросу")
            println("  • Возможно, нужно проиндексировать больше документов")
        }
        
        println()
        
        if (timeDiff > 0) {
            println("⏱️  С RAG медленнее на ${timeDiff / 1000.0} сек (из-за поиска в базе)")
        } else {
            println("⏱️  С RAG быстрее на ${-timeDiff / 1000.0} сек")
        }
        
        println()
        println("💡 Рекомендация:")
        if (ragWasHelpful) {
            println("  Используйте RAG для вопросов о ваших документах")
        } else if (comparison.withRag.usedContext.isNotEmpty()) {
            println("  Для этого вопроса лучше использовать режим без RAG")
            println("  или переформулируйте вопрос для лучшего поиска")
        } else {
            println("  Для общих вопросов можно использовать режим без RAG")
        }
        
        println()
        
    } catch (e: Exception) {
        println("❌ Ошибка: ${e.message}")
    }
}

/**
 * Обработка команды статистики
 */
fun handleStats(pipeline: IndexingPipeline) {
    println("\n📊 Статистика индекса")
    println()
    
    val stats = pipeline.getStats()
    
    if (stats["documents"] == 0) {
        println("⚠️  Индекс пуст. Запустите индексацию (команда 'index')")
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
}
