package com.github.roscrl.inlineaichat.settings

import com.github.roscrl.llm.config.ModelConfig
import com.intellij.openapi.application.ApplicationManager
import com.intellij.openapi.components.PersistentStateComponent
import com.intellij.openapi.components.Service
import com.intellij.openapi.components.State
import com.intellij.openapi.components.Storage
import com.intellij.openapi.diagnostic.Logger
import com.intellij.util.xmlb.XmlSerializerUtil

@Service
@State(
    name = "com.github.roscrl.inlineaichat.settings.InlineAIChatSettingsState",
    storages = [Storage("InlineAIChatSettings.xml")]
)
class InlineAIChatSettingsState : PersistentStateComponent<InlineAIChatSettingsState> {
    private val logger = Logger.getInstance(InlineAIChatSettingsState::class.java)

    var openRouterApiKey: String = ""
    var selectedModel: String
        get() = _selectedModel ?: availableModels.firstOrNull() ?: ""
        set(value) {
            val oldValue = _selectedModel
            _selectedModel = value
            if (oldValue != value) {
                listeners.forEach { it() }
            }
        }
    private var _selectedModel: String? = null
    var systemPrompt: String = "You are a helpful AI assistant."

    // Store all models in one set, including defaults
    var models: MutableSet<String> = mutableSetOf()

    private val listeners = mutableListOf<() -> Unit>()

    init {
        logger.info("Initializing LLMSettingsState")
        initializeModels()
    }

    private fun initializeModels() {
        try {
            if (models.isEmpty()) {
                logger.info("Loading default models from ModelConfig")
                val defaultModels = ModelConfig.defaultModels
                logger.info("Loaded ${defaultModels.size} default models: ${defaultModels.joinToString()}")
                models.addAll(defaultModels)
                if (_selectedModel == null || _selectedModel !in models) {
                    _selectedModel = defaultModels.firstOrNull()
                    logger.info("Set initial selected model to: $_selectedModel")
                }
            } else {
                logger.info("Models already initialized with ${models.size} models")
            }
        } catch (e: Exception) {
            logger.error("Failed to initialize models", e)
        }
    }

    fun addListener(listener: () -> Unit) {
        listeners.add(listener)
    }

    fun removeListener(listener: () -> Unit) {
        listeners.remove(listener)
    }

    val availableModels: List<String>
        get() = models.toList().sorted()

    override fun getState(): InlineAIChatSettingsState = this

    override fun loadState(state: InlineAIChatSettingsState) {
        logger.info("Loading state")
        XmlSerializerUtil.copyBean(state, this)

        // Always ensure we have models after loading state
        initializeModels()
        logger.info("State loaded, models count: ${models.size}, selected model: $_selectedModel")
    }

    companion object {
        val instance: InlineAIChatSettingsState
            get() = ApplicationManager.getApplication().getService(InlineAIChatSettingsState::class.java)
    }
} 