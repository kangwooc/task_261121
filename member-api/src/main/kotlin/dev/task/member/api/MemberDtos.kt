package dev.task.member.api

import com.fasterxml.jackson.annotation.JsonFormat
import com.fasterxml.jackson.annotation.JsonInclude
import java.time.LocalDateTime

@JsonInclude(JsonInclude.Include.NON_NULL)
data class MemberListResponse(val members: List<MemberSummary>, val page: PageInfo? = null, val cursor: CursorInfo? = null)

// group/permission null must be serialized (class-level NON_NULL on the wrapper does not apply here)
data class MemberSummary(val id: String, val name: String, val group: String?, val permission: Permission?)

data class PageInfo(val number: Int, val size: Int, val totalElements: Long, val totalPages: Int)

data class CursorInfo(val next: String?, val hasNext: Boolean)

data class MemberDetailResponse(
    val id: String,
    val name: String,
    val email: String,
    val address: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm") val registeredDate: LocalDateTime,
    val group: MemberGroupInfo?,
)

data class MemberGroupInfo(
    val id: String,
    val name: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm") val registeredDate: LocalDateTime,
    val pricing: Pricing,
    val permission: Permission,
)
