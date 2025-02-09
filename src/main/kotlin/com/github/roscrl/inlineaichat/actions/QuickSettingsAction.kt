package com.github.roscrl.inlineaichat.actions

import com.github.roscrl.inlineaichat.settings.InlineAIChatSettingsState
import com.intellij.openapi.actionSystem.AnAction
import com.intellij.openapi.actionSystem.AnActionEvent
import com.intellij.openapi.ui.DialogWrapper
import com.intellij.openapi.ui.Messages
import com.intellij.ui.CollectionListModel
import com.intellij.ui.ToolbarDecorator
import com.intellij.ui.components.JBList
import com.intellij.ui.HyperlinkLabel
import com.intellij.util.ui.JBUI
import java.awt.BorderLayout
import javax.swing.*
import java.awt.event.KeyEvent
import java.awt.event.KeyAdapter

class QuickSettingsAction : AnAction() {
    override fun actionPerformed(e: AnActionEvent) {
        val project = e.project ?: return
        val settings = InlineAIChatSettingsState.instance
        QuickSettingsDialog().show()
    }
}

private class QuickSettingsDialog : DialogWrapper(true) {
    private val modelsModel = CollectionListModel<String>()
    private val modelsList = JBList(modelsModel).apply {
        setSelectionMode(ListSelectionModel.SINGLE_SELECTION)
        
        // Add enter key listener to quickly select model
        addKeyListener(object : KeyAdapter() {
            override fun keyPressed(e: KeyEvent) {
                if (e.keyCode == KeyEvent.VK_ENTER) {
                    selectedValue?.let { selectModel(it) }
                    close(OK_EXIT_CODE)
                }
            }
        })
        
        // Add double-click listener
        addMouseListener(object : java.awt.event.MouseAdapter() {
            override fun mouseClicked(e: java.awt.event.MouseEvent) {
                if (e.clickCount == 2) {
                    selectedValue?.let { selectModel(it) }
                    close(OK_EXIT_CODE)
                }
            }
        })
    }

    init {
        title = "Quick Model Switch"
        init()
        
        // Load models
        refreshModels()
        
        // Select current model
        modelsList.setSelectedValue(InlineAIChatSettingsState.instance.selectedModel, true)
    }
    
    private fun refreshModels() {
        modelsModel.removeAll()
        InlineAIChatSettingsState.instance.models.toList().sorted().forEach { modelsModel.add(it) }
    }

    override fun getPreferredFocusedComponent(): JComponent = modelsList

    private fun selectModel(model: String) {
        InlineAIChatSettingsState.instance.selectedModel = model
    }

    private fun createModelsPanel(): JPanel {
        val panel = JPanel(BorderLayout())
        
        val decorator = ToolbarDecorator.createDecorator(modelsList)
            .setAddAction { 
                val result = Messages.showInputDialog(
                    "Enter Model Name",
                    "Add Custom Model",
                    null
                )
                if (result != null) {
                    val newModel = result.trim()
                    if (newModel.isNotEmpty() && !InlineAIChatSettingsState.instance.models.contains(newModel)) {
                        InlineAIChatSettingsState.instance.models.add(newModel)
                        refreshModels()
                        modelsList.setSelectedValue(newModel, true)
                        selectModel(newModel)
                        close(OK_EXIT_CODE)
                    } else if (InlineAIChatSettingsState.instance.models.contains(newModel)) {
                        Messages.showWarningDialog(
                            "This model already exists in the list.",
                            "Duplicate Model"
                        )
                    }
                }
            }
            .setRemoveAction {
                val selectedModel = modelsList.selectedValue
                if (selectedModel != null) {
                    // Don't allow removing the last model
                    if (InlineAIChatSettingsState.instance.models.size <= 1) {
                        Messages.showWarningDialog(
                            "Cannot remove the last model. At least one model must be available.",
                            "Cannot Remove Model"
                        )
                        return@setRemoveAction
                    }
                    
                    // If removing active model, switch to another one first
                    if (selectedModel == InlineAIChatSettingsState.instance.selectedModel) {
                        val newModel = InlineAIChatSettingsState.instance.models.first { it != selectedModel }
                        selectModel(newModel)
                    }
                    
                    InlineAIChatSettingsState.instance.models.remove(selectedModel)
                    refreshModels()
                }
            }
            .createPanel()
        
        panel.add(decorator, BorderLayout.CENTER)
        
        val modelsLink = HyperlinkLabel("View available models on OpenRouter").apply {
            setHyperlinkTarget("https://openrouter.ai/models")
        }
        val linkPanel = JPanel(BorderLayout()).apply {
            add(modelsLink, BorderLayout.CENTER)
            border = JBUI.Borders.empty(5)
        }
        panel.add(linkPanel, BorderLayout.SOUTH)
        
        panel.preferredSize = JBUI.size(300, 400)
        return panel
    }

    override fun createCenterPanel(): JComponent = createModelsPanel()

    override fun doOKAction() {
        modelsList.selectedValue?.let { selectModel(it) }
        super.doOKAction()
    }
} 