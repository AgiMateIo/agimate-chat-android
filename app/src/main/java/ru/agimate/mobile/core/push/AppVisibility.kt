package ru.agimate.mobile.core.push

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger
import javax.inject.Inject
import javax.inject.Singleton

/**
 * Видит ли человек приложение прямо сейчас.
 *
 * Нужно уведомлениям: `OpenChatTracker` знает, какая переписка открыта, но переживает свёртывание
 * приложения — экран чата остаётся в стеке вместе со своей ViewModel. Без этого признака свёрнутое
 * приложение с открытым чатом молчало бы на все сообщения этой переписки.
 *
 * Считаем по started, а не по resumed: у приложения поверх диалога или в разделённом экране
 * уведомление тоже лишнее.
 */
@Singleton
class AppVisibility @Inject constructor() : Application.ActivityLifecycleCallbacks {

    private val started = AtomicInteger(0)

    val visible: Boolean get() = started.get() > 0

    override fun onActivityStarted(activity: Activity) {
        started.incrementAndGet()
    }

    override fun onActivityStopped(activity: Activity) {
        started.decrementAndGet()
    }

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit
    override fun onActivityResumed(activity: Activity) = Unit
    override fun onActivityPaused(activity: Activity) = Unit
    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit
    override fun onActivityDestroyed(activity: Activity) = Unit
}
