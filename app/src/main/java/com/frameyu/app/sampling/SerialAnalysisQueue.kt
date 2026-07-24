package com.frameyu.app.sampling

/** 进程内串行策略核心；WorkManager 层使用同名唯一工作链实现跨进程持久化串行。 */
class SerialAnalysisQueue {
    private val pending = LinkedHashSet<Long>()
    private var activeTaskId: Long? = null

    @Synchronized
    fun enqueue(taskId: Long) {
        require(taskId >= 0L) { "任务 ID 不能为负数" }
        if (activeTaskId != taskId) pending += taskId
    }

    @Synchronized
    fun takeNext(): Long? {
        if (activeTaskId != null) return null
        val next = pending.firstOrNull() ?: return null
        pending.remove(next)
        activeTaskId = next
        return next
    }

    @Synchronized
    fun markFinished(taskId: Long) {
        if (activeTaskId == taskId) activeTaskId = null
        pending.remove(taskId)
    }
}
