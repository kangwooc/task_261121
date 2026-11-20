package dev.task.member.infrastructure.persistence

import dev.task.member.query.CursorKey
import jakarta.persistence.Entity
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "members")
class MemberEntity(
    @Id val id: String,
    val name: String,
    val email: String,
    val address: String,
    val registeredAt: Instant,
)

data class MemberRow(val id: String, val name: String, val email: String, val address: String, val registeredAt: Instant)

data class PageMeta(val number: Int, val size: Int, val totalElements: Long, val totalPages: Int)

data class MemberSlice(val rows: List<MemberRow>, val page: PageMeta?, val nextCursor: CursorKey?, val hasNext: Boolean)
