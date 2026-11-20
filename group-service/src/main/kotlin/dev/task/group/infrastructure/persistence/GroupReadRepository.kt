package dev.task.group.infrastructure.persistence

import org.springframework.stereotype.Repository
import org.springframework.transaction.annotation.Transactional

@Repository
class GroupReadRepository(private val jpa: GroupMemberJpaRepository) {
    @Transactional(readOnly = true)
    fun findMemberships(memberIds: Collection<String>): List<GroupMembershipView> =
        if (memberIds.isEmpty()) emptyList() else jpa.findMemberships(memberIds)

    @Transactional(readOnly = true)
    fun countMembers(groupIds: Collection<String>): List<GroupCountView> =
        if (groupIds.isEmpty()) emptyList() else jpa.countMembers(groupIds)

    @Transactional(readOnly = true)
    fun findMemberGroup(memberId: String): MemberGroupDetailView? = jpa.findMemberGroup(memberId)
}
