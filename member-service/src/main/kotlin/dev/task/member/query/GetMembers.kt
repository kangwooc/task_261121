package dev.task.member.query

import dev.task.member.api.CursorInfo
import dev.task.member.api.MemberListResponse
import dev.task.member.api.MemberSummary
import dev.task.member.api.PageInfo
import dev.task.member.api.Permission
import dev.task.member.infrastructure.persistence.CursorCodec
import dev.task.member.infrastructure.persistence.MemberReadRepository
import dev.task.member.query.port.GroupReader
import org.springframework.stereotype.Component

data class GetMembersQuery(val size: Int, val direction: SortDirection, val pagination: Pagination) {
    companion object {
        fun of(size: Int?, sort: String?, pagination: String?, page: Int?, cursor: String?): GetMembersQuery {
            val s = size ?: 20
            if (s !in 1..100) throw InvalidRequestException("size must be between 1 and 100")
            val direction = when (sort ?: "name,asc") {
                "name,asc" -> SortDirection.ASC
                "name,desc" -> SortDirection.DESC
                else -> throw InvalidRequestException("sort must be name,asc or name,desc")
            }
            val p = when (pagination ?: "offset") {
                "offset" -> {
                    if (cursor != null) throw InvalidRequestException("cursor is only allowed with pagination=cursor")
                    if ((page ?: 0) < 0) throw InvalidRequestException("page must be >= 0")
                    Pagination.Offset(page ?: 0)
                }
                "cursor" -> {
                    if (page != null) throw InvalidRequestException("page is only allowed with pagination=offset")
                    val after = cursor?.let(CursorCodec::decode)
                    if (after != null && after.direction != direction) throw InvalidRequestException("cursor direction does not match sort")
                    Pagination.Cursor(after)
                }
                else -> throw InvalidRequestException("pagination must be offset or cursor")
            }
            return GetMembersQuery(s, direction, p)
        }
    }
}

/** No @Transactional: the repository call ends its own read-only tx (connection released) before the group call. */
@Component
class GetMembersHandler(private val repo: MemberReadRepository, private val groups: GroupReader) {
    fun handle(query: GetMembersQuery): MemberListResponse {
        val slice = repo.findPage(query.size, query.direction, query.pagination)
        val summaries = if (slice.rows.isEmpty()) emptyMap() else groups.findSummaries(slice.rows.map { it.id })
        val members = slice.rows.map {
            val g = summaries[it.id]
            MemberSummary(it.id, it.name, g?.groupName, g?.let { s -> Permission.valueOf(s.permission.name) })
        }
        return MemberListResponse(
            members,
            slice.page?.let { PageInfo(it.number, it.size, it.totalElements, it.totalPages) },
            if (query.pagination is Pagination.Cursor) CursorInfo(slice.nextCursor?.let(CursorCodec::encode), slice.hasNext) else null,
        )
    }
}
