package com.frameyu.app.system

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.concurrent.atomic.AtomicInteger

enum class AppVisibility {
    FOREGROUND,
    BACKGROUND,
}

class AppVisibilityState {
    private val started = AtomicInteger(0)

    val startedActivityCount: Int
        get() = started.get()

    val visibility: AppVisibility
        get() = if (started.get() > 0) {
            AppVisibility.FOREGROUND
        } else {
            AppVisibility.BACKGROUND
        }

    fun onActivityStarted() {
        started.incrementAndGet()
    }

    fun onActivityStopped() {
        started.updateAndGet { count -> (count - 1).coerceAtLeast(0) }
    }
}

class ProcessAppVisibilityTracker(
    private val state: AppVisibilityState = AppVisibilityState(),
) : Application.ActivityLifecycleCallbacks {
    val visibility: AppVisibility
        get() = state.visibility

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        state.onActivityStarted()
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        state.onActivityStopped()
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
