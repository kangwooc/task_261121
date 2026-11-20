package dev.task.member.infrastructure.persistence

import dev.task.member.query.CursorKey
import dev.task.member.query.InvalidRequestException
import dev.task.member.query.Pagination
import dev.task.member.query.SortDirection
import org.springframework.data.domain.Limit
import org.springframework.data.domain.PageRequest
import org.springframework.data.domain.Sort
import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class MemberReadRepository(private val jpa: MemberJpaRepository) {
    @Transactional(readOnly = true)
    fun findPage(size: Int, direction: SortDirection, pagination: Pagination): MemberSlice = when (pagination) {
        is Pagination.Offset -> {
            val dir = if (direction == SortDirection.ASC) Sort.Direction.ASC else Sort.Direction.DESC
            val p = jpa.findAllProjectedBy(PageRequest.of(pagination.page, size, Sort.by(dir, "name", "registeredAt", "id")))
            MemberSlice(p.content, PageMeta(p.number, p.size, p.totalElements, p.totalPages), null, p.hasNext())
        }
        is Pagination.Cursor -> {
            val after = pagination.after
            if (after != null && after.direction != direction) throw InvalidRequestException("cursor direction does not match sort")
            val limit = Limit.of(size + 1)
            val fetched = when {
                after == null && direction == SortDirection.ASC -> jpa.firstAsc(limit)
                after == null -> jpa.firstDesc(limit)
                direction == SortDirection.ASC -> jpa.afterAsc(after.name, after.registeredAt, after.id, limit)
                else -> jpa.afterDesc(after.name, after.registeredAt, after.id, limit)
            }
            val hasNext = fetched.size > size
            val rows = fetched.take(size)
            val last = rows.lastOrNull()
            MemberSlice(rows, null, if (hasNext && last != null) CursorKey(last.name, last.registeredAt, last.id, direction) else null, hasNext)
        }
    }

    @Transactional(readOnly = true)
    fun findById(id: String): MemberRow? = jpa.findProjectedById(id)
}
