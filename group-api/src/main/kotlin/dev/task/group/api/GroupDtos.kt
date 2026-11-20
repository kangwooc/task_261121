package dev.task.group.api

import com.fasterxml.jackson.annotation.JsonFormat
import java.time.LocalDateTime

data class GroupSummaryResponse(val groups: List<GroupSummary>)

data class GroupSummary(val groupId: String, val groupName: String, val totalMemberCount: Int, val members: List<GroupMemberPermission>)

data class GroupMemberPermission(val memberId: String, val permission: Permission)

data class MemberGroupDetailResponse(
    val memberId: String,
    val groupId: String,
    val groupName: String,
    @field:JsonFormat(pattern = "yyyy-MM-dd HH:mm") val registeredDate: LocalDateTime,
    val pricing: Pricing,
    val permission: Permission,
)
