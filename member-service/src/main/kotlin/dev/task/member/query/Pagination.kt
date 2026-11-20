package dev.task.member.query

import java.time.Instant

enum class SortDirection { ASC, DESC }

sealed interface Pagination {
    /** page is 0-based. */
    data class Offset(val page: Int) : Pagination

    /** after == null means the first page. */
    data class Cursor(val after: CursorKey?) : Pagination
}

data class CursorKey(val name: String, val registeredAt: Instant, val id: String, val direction: SortDirection)
