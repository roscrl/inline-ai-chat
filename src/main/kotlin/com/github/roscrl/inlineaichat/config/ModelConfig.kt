package com.github.roscrl.inlineaichat.config

import com.github.roscrl.inlineaichat.notifications.NotificationUtil
import com.intellij.openapi.diagnostic.Logger
import org.json.JSONException
import org.json.JSONObject
import java.io.IOException
import java.io.InputStreamReader
import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

object ModelConfig {
    private val logger = Logger.getInstance(ModelConfig::class.java)
    private const val DEFAULT_MODEL = "anthropic/claude-3.5-sonnet"
    
    val defaultModels: List<String> by lazy {
        try {
            logger.info("Loading default models from configuration")
            loadModels().also { models ->
                logger.info("Successfully loaded ${models.size} models: ${models.joinToString()}")
            }
        } catch (e: Exception) {
            logger.error("Failed to load models from configuration", e)
            handleModelLoadError(e)
            listOf(DEFAULT_MODEL).also {
                logger.info("Using fallback model: $DEFAULT_MODEL")
            }
        }
    }

    private fun loadModels(): List<String> {
        try {
            logger.info("Attempting to load models.json from resources")
            val inputStream = ModelConfig::class.java.classLoader.getResourceAsStream("models.json")
                ?: throw IOException("models.json not found in resources")

            val jsonContent = InputStreamReader(inputStream).use { it.readText() }
            logger.debug("Successfully read models.json: $jsonContent")
            
            val json = JSONObject(jsonContent)
            val modelsArray = json.getJSONArray("defaultModels")
            
            return (0 until modelsArray.length()).map { i ->
                modelsArray.getString(i)
            }.also { models ->
                if (models.isEmpty()) {
                    logger.error("No models found in configuration file")
                    throw IllegalStateException("No models found in configuration")
                }
                logger.info("Successfully loaded ${models.size} models: ${models.joinToString()}")
            }
        } catch (e: IOException) {
            logger.error("Failed to read models configuration", e)
            throw ModelConfigException("Failed to read models configuration", e)
        } catch (e: JSONException) {
            logger.error("Invalid models configuration format", e)
            throw ModelConfigException("Invalid models configuration format", e)
        } catch (e: Exception) {
            logger.error("Unexpected error loading models", e)
            throw ModelConfigException("Unexpected error loading models", e)
        }
    }
    
    private fun handleModelLoadError(error: Exception) {
        val (title, content) = when (error) {
            is ModelConfigException -> when (error.cause) {
                is IOException -> "Configuration Not Found" to "Could not find or read models.json. Using default model."
                is JSONException -> "Invalid Configuration" to "models.json has an invalid format. Using default model."
                else -> "Configuration Error" to "Error loading models configuration. Using default model."
            }
            else -> "Unexpected Error" to "An unexpected error occurred loading models. Using default model."
        }
        
        logger.warn("$title: $content (Error: ${error.message})")
        NotificationUtil.showError(
            title = title,
            content = "$content\nError: ${error.message}"
        )
    }
}

class ModelConfigException(message: String, cause: Throwable? = null) : Exception(message, cause) 