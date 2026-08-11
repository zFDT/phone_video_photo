package com.frameyu.app.system

import android.app.Activity
import android.app.Application
import android.os.Bundle
import java.util.Collections
import java.util.IdentityHashMap
import java.util.concurrent.atomic.AtomicInteger

enum class AppVisibility {
    FOREGROUND,
    BACKGROUND,
}

class AppVisibilityState {
    private val lock = Any()
    private val startedTokens = Collections.newSetFromMap(IdentityHashMap<Any, Boolean>())
    private val started = AtomicInteger(0)

    val startedActivityCount: Int
        get() = started.get()

    val visibility: AppVisibility
        get() = if (started.get() > 0) {
            AppVisibility.FOREGROUND
        } else {
            AppVisibility.BACKGROUND
        }

    fun onActivityStarted(token: Any) {
        synchronized(lock) {
            if (startedTokens.add(token)) {
                started.incrementAndGet()
            }
        }
    }

    fun onActivityStopped(token: Any) {
        synchronized(lock) {
            if (startedTokens.remove(token)) {
                started.decrementAndGet()
            }
        }
    }
}

class ProcessAppVisibilityTracker(
    private val state: AppVisibilityState = AppVisibilityState(),
) : Application.ActivityLifecycleCallbacks {
    val visibility: AppVisibility
        get() = state.visibility

    override fun onActivityCreated(activity: Activity, savedInstanceState: Bundle?) = Unit

    override fun onActivityStarted(activity: Activity) {
        state.onActivityStarted(activity)
    }

    override fun onActivityResumed(activity: Activity) = Unit

    override fun onActivityPaused(activity: Activity) = Unit

    override fun onActivityStopped(activity: Activity) {
        state.onActivityStopped(activity)
    }

    override fun onActivitySaveInstanceState(activity: Activity, outState: Bundle) = Unit

    override fun onActivityDestroyed(activity: Activity) = Unit
}
