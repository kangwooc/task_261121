package dev.task.group.infrastructure.persistence

import dev.task.group.api.Permission
import dev.task.group.api.Pricing
import java.time.Instant

data class GroupMembershipView(val memberId: String, val groupId: String, val groupName: String, val permission: Permission)

data class GroupCountView(val groupId: String, val totalMemberCount: Long)

data class MemberGroupDetailView(
    val memberId: String,
    val groupId: String,
    val groupName: String,
    val joinedAt: Instant,
    val pricing: Pricing,
    val permission: Permission,
)
