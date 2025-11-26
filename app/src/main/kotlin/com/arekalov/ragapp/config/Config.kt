package com.arekalov.ragapp.config

import com.typesafe.config.ConfigFactory

data class Config(
    val obsidianPath: String,
    val chunkSize: Int,
    val chunkOverlap: Int,
    val ollamaUrl: String,
    val ollamaModel: String,
    val databasePath: String,
    val yandexApiKey: String,
    val yandexFolderId: String
) {
    companion object {
        fun load(): Config {
            val config = ConfigFactory.load()
            return Config(
                obsidianPath = config.getString("ragapp.obsidian.path"),
                chunkSize = config.getInt("ragapp.chunking.size"),
                chunkOverlap = config.getInt("ragapp.chunking.overlap"),
                ollamaUrl = config.getString("ragapp.ollama.url"),
                ollamaModel = config.getString("ragapp.ollama.model"),
                databasePath = config.getString("ragapp.storage.database-path"),
                yandexApiKey = config.getString("ragapp.yandex.api-key"),
                yandexFolderId = config.getString("ragapp.yandex.folder-id")
            )
        }
    }
}

