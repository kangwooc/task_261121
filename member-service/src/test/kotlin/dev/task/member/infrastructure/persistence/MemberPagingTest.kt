package dev.task.member.infrastructure.persistence

import dev.task.member.TestcontainersConfig
import dev.task.member.query.CursorKey
import dev.task.member.query.InvalidRequestException
import dev.task.member.query.Pagination
import dev.task.member.query.SortDirection
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.CsvSource
import org.springframework.beans.factory.annotation.Autowired
import org.springframework.boot.data.jpa.test.autoconfigure.DataJpaTest
import org.springframework.boot.jdbc.test.autoconfigure.AutoConfigureTestDatabase
import org.springframework.context.annotation.Import
import org.springframework.jdbc.core.JdbcTemplate
import java.text.Collator
import java.util.Locale
import kotlin.test.assertEquals
import kotlin.test.assertFailsWith
import kotlin.test.assertFalse
import kotlin.test.assertNotNull
import kotlin.test.assertNull
import kotlin.test.assertTrue

@DataJpaTest
@Import(TestcontainersConfig::class, MemberReadRepository::class)
@AutoConfigureTestDatabase(replace = AutoConfigureTestDatabase.Replace.NONE)
class MemberPagingTest @Autowired constructor(
    private val repo: MemberReadRepository,
    private val jdbc: JdbcTemplate,
) {
    private fun expected(dir: SortDirection): List<String> {
        val d = if (dir == SortDirection.ASC) "asc" else "desc"
        return jdbc.queryForList("select id from members order by name $d, registered_at $d, id $d", String::class.java).filterNotNull()
    }

    private fun offsetIds(size: Int, dir: SortDirection): List<String> {
        val out = mutableListOf<String>()
        var page = 0
        while (true) {
            val s = repo.findPage(size, dir, Pagination.Offset(page))
            if (s.rows.isEmpty()) break
            out += s.rows.map { it.id }
            page++
        }
        return out
    }

    private fun cursorIds(size: Int, dir: SortDirection): List<String> {
        val out = mutableListOf<String>()
        var after: CursorKey? = null
        while (true) {
            val s = repo.findPage(size, dir, Pagination.Cursor(after))
            out += s.rows.map { it.id }
            after = s.nextCursor
            if (after == null) { assertFalse(s.hasNext); break }
            assertTrue(s.hasNext)
        }
        return out
    }

    @ParameterizedTest
    @CsvSource("ASC,1", "ASC,3", "ASC,7", "DESC,1", "DESC,3", "DESC,7")
    fun offsetTraversalVisitsEveryMemberOnceInOrder(dir: SortDirection, size: Int) {
        val ids = offsetIds(size, dir)
        assertEquals(50, ids.toSet().size)
        assertEquals(expected(dir), ids)
    }

    @ParameterizedTest
    @CsvSource("ASC,1", "ASC,3", "ASC,7", "DESC,1", "DESC,3", "DESC,7")
    fun cursorTraversalVisitsEveryMemberOnceInOrder(dir: SortDirection, size: Int) {
        val ids = cursorIds(size, dir)
        assertEquals(50, ids.toSet().size)
        assertEquals(expected(dir), ids)
    }

    @ParameterizedTest
    @CsvSource("ASC,3", "ASC,7", "DESC,3", "DESC,7")
    fun offsetAndCursorProduceIdenticalOrder(dir: SortDirection, size: Int) {
        assertEquals(offsetIds(size, dir), cursorIds(size, dir))
    }

    @ParameterizedTest
    @CsvSource("ASC", "DESC")
    fun hangulNamesSortInKoreanDictionaryOrder(dir: SortDirection) {
        val names = repo.findPage(50, dir, Pagination.Offset(0)).rows.map { it.name }
        val hangul = names.filter { n -> n.all { it in '\uAC00'..'\uD7A3' } }
        val dictionary = hangul.sortedWith(Collator.getInstance(Locale.KOREAN))
        assertEquals(if (dir == SortDirection.ASC) dictionary else dictionary.reversed(), hangul)
        val triple = listOf("강하늘", "김민수", "나은").let { if (dir == SortDirection.ASC) it else it.reversed() }
        assertEquals(triple, names.filter { it in triple }.distinct())
    }

    @Test
    fun lastPageHasNoNext() {
        val s = repo.findPage(7, SortDirection.ASC, Pagination.Offset(7))
        assertEquals(1, s.rows.size)
        assertFalse(s.hasNext)
        assertEquals(50, s.page!!.totalElements)
        assertEquals(8, s.page!!.totalPages)
        assertNull(s.nextCursor)
        val first = repo.findPage(7, SortDirection.ASC, Pagination.Offset(0))
        assertTrue(first.hasNext)
        assertNull(first.nextCursor) // offset mode carries no cursor
    }

    @Test
    fun pageBeyondEndIsEmpty() {
        val s = repo.findPage(7, SortDirection.ASC, Pagination.Offset(99))
        assertTrue(s.rows.isEmpty())
        assertFalse(s.hasNext)
    }

    @Test
    fun cursorWithOtherDirectionIsRejected() {
        val c = repo.findPage(3, SortDirection.ASC, Pagination.Cursor(null)).nextCursor
        assertFailsWith<InvalidRequestException> { repo.findPage(3, SortDirection.DESC, Pagination.Cursor(c)) }
    }

    @Test
    fun microsecondDifferenceSurvivesCursorPaging() {
        // size 1 forces a cursor boundary between the two Alice Kim rows that differ by 1 microsecond
        val ids = cursorIds(1, SortDirection.ASC)
        // time order is opposite to id order, so dropping registeredAt from the sort key would fail
        assertEquals(ids.indexOf("c77u434yrz") + 1, ids.indexOf("a9pwo15d0x"))
    }

    @Test
    fun findByIdWorks() {
        assertNotNull(repo.findById("27ysni0g1i"))
        assertNull(repo.findById("zzzzzzzzzz"))
    }
}
