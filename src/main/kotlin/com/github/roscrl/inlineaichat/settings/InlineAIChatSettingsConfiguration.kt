package com.github.roscrl.inlineaichat.settings

import com.intellij.openapi.options.Configurable
import javax.swing.JComponent
import javax.swing.JPanel

class InlineAIChatSettingsConfiguration : Configurable {
    private var settingsComponent: InlineAIChatSettingsConfigurable? = null

    override fun getDisplayName(): String = "Inline AI Chat Settings"

    override fun createComponent(): JComponent {
        settingsComponent = InlineAIChatSettingsConfigurable()
        return settingsComponent?.createComponent() ?: JPanel()
    }

    override fun isModified(): Boolean {
        return settingsComponent?.isModified() ?: false
    }

    override fun apply() {
        settingsComponent?.apply()
    }

    override fun reset() {
        settingsComponent?.reset()
    }

    override fun disposeUIResources() {
        settingsComponent?.disposeUIResources()
        settingsComponent = null
    }
} 