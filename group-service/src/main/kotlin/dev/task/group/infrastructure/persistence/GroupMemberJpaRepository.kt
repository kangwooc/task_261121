package dev.task.group.infrastructure.persistence

import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query

interface GroupMemberJpaRepository : JpaRepository<GroupMemberEntity, String> {
    @Query(
        """select new dev.task.group.infrastructure.persistence.GroupMembershipView(gm.memberId, g.id, g.name, gm.permission)
           from GroupMemberEntity gm join GroupEntity g on g.id = gm.groupId where gm.memberId in :memberIds""",
    )
    fun findMemberships(memberIds: Collection<String>): List<GroupMembershipView>

    @Query(
        """select new dev.task.group.infrastructure.persistence.GroupCountView(gm.groupId, count(gm))
           from GroupMemberEntity gm where gm.groupId in :groupIds group by gm.groupId""",
    )
    fun countMembers(groupIds: Collection<String>): List<GroupCountView>

    @Query(
        """select new dev.task.group.infrastructure.persistence.MemberGroupDetailView(gm.memberId, g.id, g.name, gm.joinedAt, g.pricing, gm.permission)
           from GroupMemberEntity gm join GroupEntity g on g.id = gm.groupId where gm.memberId = :memberId""",
    )
    fun findMemberGroup(memberId: String): MemberGroupDetailView?
}
