package com.frameyu.app.analysis.batch

object BatchStateMachine {
    private val transitions = mapOf(
        (WorkItemStatus.PENDING to WorkItemEvent.START) to WorkItemStatus.RUNNING,
        (WorkItemStatus.PENDING to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
        (WorkItemStatus.RUNNING to WorkItemEvent.COMPLETE) to WorkItemStatus.COMPLETED,
        (WorkItemStatus.RUNNING to WorkItemEvent.PAUSE) to WorkItemStatus.PAUSED,
        (WorkItemStatus.RUNNING to WorkItemEvent.RETRYABLE_FAILURE) to WorkItemStatus.RETRYABLE_FAILED,
        (WorkItemStatus.RUNNING to WorkItemEvent.PERMANENT_FAILURE) to WorkItemStatus.PERMANENT_FAILED,
        (WorkItemStatus.RUNNING to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
        (WorkItemStatus.PAUSED to WorkItemEvent.RESUME) to WorkItemStatus.PENDING,
        (WorkItemStatus.PAUSED to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
        (WorkItemStatus.RETRYABLE_FAILED to WorkItemEvent.RETRY) to WorkItemStatus.PENDING,
        (WorkItemStatus.RETRYABLE_FAILED to WorkItemEvent.CANCEL) to WorkItemStatus.CANCELLED,
    )

    fun transition(source: WorkItemStatus, event: WorkItemEvent): WorkItemStatus =
        transitions[source to event]
            ?: throw IllegalArgumentException("非法状态转换：source=$source，event=$event")
}
