package dev.task.group.query

import dev.task.group.api.GroupMemberPermission
import dev.task.group.api.GroupSummary
import dev.task.group.api.GroupSummaryResponse
import dev.task.group.infrastructure.persistence.GroupReadRepository
import org.springframework.stereotype.Component

class InvalidRequestException(message: String) : RuntimeException(message)

class MemberGroupNotFoundException(memberId: String) : RuntimeException("member group not found: $memberId")

private val ID_REGEX = Regex("^[a-z0-9]{10}$")

fun requireValidId(id: String) {
    if (!ID_REGEX.matches(id)) throw InvalidRequestException("invalid member id: $id")
}

data class GetGroupSummariesQuery(val memberIds: Set<String>) {
    init {
        if (memberIds.size !in 1..100) throw InvalidRequestException("memberIds must contain 1..100 ids")
        memberIds.forEach(::requireValidId)
    }
}

@Component
class GetGroupSummariesHandler(private val repo: GroupReadRepository) {
    fun handle(query: GetGroupSummariesQuery): GroupSummaryResponse {
        val memberships = repo.findMemberships(query.memberIds).groupBy { it.groupId }
        val counts = repo.countMembers(memberships.keys).associate { it.groupId to it.totalMemberCount.toInt() }
        return GroupSummaryResponse(memberships.map { (groupId, ms) ->
            GroupSummary(groupId, ms.first().groupName, counts.getValue(groupId), ms.map { GroupMemberPermission(it.memberId, it.permission) })
        })
    }
}
