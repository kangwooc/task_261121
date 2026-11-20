package dev.task.group.query

import dev.task.group.api.MemberGroupDetailResponse
import dev.task.group.infrastructure.persistence.GroupReadRepository
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

@Component
class GetMemberGroupDetailHandler(
    private val repo: GroupReadRepository,
    @Value("\${app.time-zone:Asia/Seoul}") zone: String,
) {
    private val zoneId = ZoneId.of(zone)

    fun handle(memberId: String): MemberGroupDetailResponse? {
        requireValidId(memberId)
        return repo.findMemberGroup(memberId)?.let {
            MemberGroupDetailResponse(
                it.memberId, it.groupId, it.groupName,
                LocalDateTime.ofInstant(it.joinedAt, zoneId), it.pricing, it.permission,
            )
        }
    }
}
