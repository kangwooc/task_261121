package dev.task.group.infrastructure.persistence

import dev.task.group.api.Permission
import dev.task.group.api.Pricing
import jakarta.persistence.Entity
import jakarta.persistence.EnumType
import jakarta.persistence.Enumerated
import jakarta.persistence.Id
import jakarta.persistence.Table
import java.time.Instant

@Entity
@Table(name = "groups")
class GroupEntity(
    @Id val id: String,
    val name: String,
    @Enumerated(EnumType.STRING) val pricing: Pricing,
    val registeredAt: Instant,
)

@Entity
@Table(name = "group_members")
class GroupMemberEntity(
    @Id val memberId: String,
    val groupId: String,
    @Enumerated(EnumType.STRING) val permission: Permission,
    val joinedAt: Instant,
)
