package dev.task.member.infrastructure.group

/** Same value is used as the response `code`, the log `stage` and the metric `stage` tag (spec §8.1). */
enum class GroupCallStage {
    CONCURRENCY_REJECTED,
    CIRCUIT_OPEN,
    CONNECT_TIMEOUT,
    READ_TIMEOUT,
    UPSTREAM_5XX,
    UPSTREAM_CONTRACT_ERROR,
    RETRY_BUDGET_EXCEEDED,
}
