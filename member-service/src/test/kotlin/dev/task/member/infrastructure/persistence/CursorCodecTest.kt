package dev.task.member.infrastructure.persistence

import dev.task.member.query.CursorKey
import dev.task.member.query.InvalidRequestException
import dev.task.member.query.SortDirection
import org.junit.jupiter.api.Test
import org.junit.jupiter.params.ParameterizedTest
import org.junit.jupiter.params.provider.ValueSource
import java.time.Instant
import java.util.Base64
import kotlin.test.assertEquals

class CursorCodecTest {
    @Test
    fun roundTripsWithMicrosecondPrecision() {
        val key = CursorKey("김민수 Ålice", Instant.parse("2025-02-01T00:00:00.000001Z"), "abcde12345", SortDirection.DESC)
        assertEquals(key, CursorCodec.decode(CursorCodec.encode(key)))
        val before = CursorKey("a", Instant.parse("1969-12-31T23:59:59.999999Z"), "abcde12345", SortDirection.ASC)
        assertEquals(before, CursorCodec.decode(CursorCodec.encode(before)))
    }

    private fun b64(json: String) = Base64.getUrlEncoder().withoutPadding().encodeToString(json.toByteArray())

    @ParameterizedTest
    @ValueSource(strings = [
        "%%% not base64 %%%",
        "bm90IGpzb24",                                          // "not json"
        "e30",                                                  // {}
        "W10",                                                  // []
    ])
    fun rejectsNonBase64OrNonJson(raw: String) {
        assertThrows(raw)
    }

    @ParameterizedTest
    @ValueSource(strings = [
        """{"n":"a","r":1,"d":"ASC"}""",                         // missing i
        """{"r":1,"i":"x","d":"ASC"}""",                         // missing n
        """{"n":"a","r":"2025-01-01","i":"x","d":"ASC"}""",      // r string
        """{"n":"a","r":1.5,"i":"x","d":"ASC"}""",               // r not integral
        """{"n":1,"r":1,"i":"x","d":"ASC"}""",                   // n number
        """{"n":"a","r":1,"i":"x","d":"SIDEWAYS"}""",            // bad direction
        """{"n":"a","r":1,"i":"x"}""",                           // missing d
        """{"n":null,"r":1,"i":"x","d":"ASC"}""",
    ])
    fun rejectsStructurallyInvalidPayloads(json: String) {
        assertThrows(b64(json))
    }

    private fun assertThrows(raw: String) {
        kotlin.test.assertFailsWith<InvalidRequestException> { CursorCodec.decode(raw) }
    }
}
