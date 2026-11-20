package dev.task.member.query.port

import dev.task.group.api.MemberGroupDetailResponse
import dev.task.group.api.Permission

interface GroupReader {
    /** key = memberId; IDs the group service returns but were not requested are dropped. */
    fun findSummaries(memberIds: Collection<String>): Map<String, MemberGroupSummary>

    /** null = the member belongs to no group (not a failure). */
    fun findDetail(memberId: String): MemberGroupDetailResponse?
}

data class MemberGroupSummary(val groupName: String, val permission: Permission)
