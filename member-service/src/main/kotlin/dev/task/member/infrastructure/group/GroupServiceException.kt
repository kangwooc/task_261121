package dev.task.member.infrastructure.group

import java.time.Duration

/** [attempts] = HTTP attempts actually made (0 when rejected before any attempt). */
class GroupServiceException(
    val stage: GroupCallStage,
    val attempts: Int,
    val elapsed: Duration,
    cause: Throwable?,
) : RuntimeException("group call failed stage=$stage attempts=$attempts", cause)
