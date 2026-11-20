package dev.task.member.infrastructure.persistence

import org.springframework.data.domain.Limit
import org.springframework.data.domain.Page
import org.springframework.data.domain.Pageable
import org.springframework.data.jpa.repository.JpaRepository
import org.springframework.data.jpa.repository.Query
import java.time.Instant

private const val SELECT = "select new dev.task.member.infrastructure.persistence.MemberRow(m.id, m.name, m.email, m.address, m.registeredAt) from MemberEntity m "

interface MemberJpaRepository : JpaRepository<MemberEntity, String> {
    fun findAllProjectedBy(pageable: Pageable): Page<MemberRow>

    fun findProjectedById(id: String): MemberRow?

    @Query(SELECT + "order by m.name asc, m.registeredAt asc, m.id asc")
    fun firstAsc(limit: Limit): List<MemberRow>

    @Query(SELECT + "order by m.name desc, m.registeredAt desc, m.id desc")
    fun firstDesc(limit: Limit): List<MemberRow>

    @Query(SELECT + "where (m.name, m.registeredAt, m.id) > (:name, :registeredAt, :id) order by m.name asc, m.registeredAt asc, m.id asc")
    fun afterAsc(name: String, registeredAt: Instant, id: String, limit: Limit): List<MemberRow>

    @Query(SELECT + "where (m.name, m.registeredAt, m.id) < (:name, :registeredAt, :id) order by m.name desc, m.registeredAt desc, m.id desc")
    fun afterDesc(name: String, registeredAt: Instant, id: String, limit: Limit): List<MemberRow>
}
