package dev.task.member.query.port

import dev.task.member.api.Permission
import dev.task.member.api.Pricing
import java.time.LocalDateTime

/** Port types use member-api enums only; the adapter maps from group-api. */
interface GroupReader {
    /** key = memberId; IDs the group service returns but were not requested are dropped. */
    fun findSummaries(memberIds: Collection<String>): Map<String, MemberGroupSummary>

    /** null = the member belongs to no group (not a failure). */
    fun findDetail(memberId: String): MemberGroupDetail?
}

data class MemberGroupSummary(val groupName: String, val permission: Permission)

/** registeredDate = when the member was added to the group. */
data class MemberGroupDetail(
    val groupId: String,
    val groupName: String,
    val registeredDate: LocalDateTime,
    val pricing: Pricing,
    val permission: Permission,
)
