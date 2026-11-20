package dev.task.member.query

import dev.task.member.api.MemberDetailResponse
import dev.task.member.api.MemberGroupInfo
import dev.task.member.api.Permission
import dev.task.member.api.Pricing
import dev.task.member.infrastructure.persistence.MemberReadRepository
import dev.task.member.query.port.GroupReader
import org.springframework.beans.factory.annotation.Value
import org.springframework.stereotype.Component
import java.time.LocalDateTime
import java.time.ZoneId

class MemberNotFoundException(val memberId: String) : RuntimeException("member not found: $memberId")

private val ID = Regex("^[a-z0-9]{10}$")

/** No @Transactional, see [GetMembersHandler]. */
@Component
class GetMemberDetailHandler(
    private val repo: MemberReadRepository,
    private val groups: GroupReader,
    @Value("\${app.time-zone:Asia/Seoul}") zone: String,
) {
    private val zoneId = ZoneId.of(zone)

    fun handle(memberId: String): MemberDetailResponse {
        if (!ID.matches(memberId)) throw InvalidRequestException("invalid member id")
        val m = repo.findById(memberId) ?: throw MemberNotFoundException(memberId)
        val g = groups.findDetail(memberId)?.let {
            MemberGroupInfo(
                it.groupId, it.groupName, it.registeredDate,
                Pricing.valueOf(it.pricing.name), Permission.valueOf(it.permission.name),
            )
        }
        return MemberDetailResponse(m.id, m.name, m.email, m.address, LocalDateTime.ofInstant(m.registeredAt, zoneId), g)
    }
}
