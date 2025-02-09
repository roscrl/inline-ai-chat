package com.github.roscrl.inlineaichat.notifications

import com.intellij.notification.NotificationGroupManager
import com.intellij.notification.NotificationType
import com.intellij.openapi.project.Project

private const val GROUP_ID = "Inline AI Chat Plugin Notifications"

object NotificationUtil {
    fun showError(
        title: String,
        content: String,
        project: Project? = null
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            ?.createNotification(title, content, NotificationType.ERROR)
            ?.notify(project)
    }
    
    fun showWarning(
        title: String,
        content: String,
        project: Project? = null
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            ?.createNotification(title, content, NotificationType.WARNING)
            ?.notify(project)
    }
    
    fun showInfo(
        title: String,
        content: String,
        project: Project? = null
    ) {
        NotificationGroupManager.getInstance()
            .getNotificationGroup(GROUP_ID)
            ?.createNotification(title, content, NotificationType.INFORMATION)
            ?.notify(project)
    }
} 