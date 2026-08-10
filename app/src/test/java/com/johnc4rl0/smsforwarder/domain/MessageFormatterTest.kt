package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import com.johnc4rl0.smsforwarder.domain.model.LineSelection
import org.junit.Test

class MessageFormatterTest {

    private val source = LineSelection(
        subscriptionId = 7,
        slotIndex = 0,
        carrierDisplayName = "CarrierA",
        reportedNumberE164 = "+15551234567",
        manualNumberE164 = null,
        identityToken = "tok-a",
    )

    @Test
    fun buildForwardPayload_exactHeaderFormat() {
        val payload = MessageFormatter.buildForwardPayload(
            sender = "+15559876543",
            source = source,
            originalBody = "Hello world",
        )
        assertThat(payload).isEqualTo(
            "[SMS-FWD/1] From +15559876543 via +15551234567\nHello world",
        )
    }

    @Test
    fun buildForwardPayload_preservesUnicodeBody() {
        val body = "你好 🎉 café — Ω"
        val payload = MessageFormatter.buildForwardPayload("+1", source, body)
        assertThat(payload).endsWith("\n$body")
        assertThat(payload).contains(body)
    }

    @Test
    fun buildForwardPayload_unknownSenderWhenMissing() {
        val payload = MessageFormatter.buildForwardPayload(null, source, "body")
        assertThat(payload).startsWith("[SMS-FWD/1] From Unknown via ")
        assertThat(payload).endsWith("\nbody")
    }

    @Test
    fun buildForwardPayload_unknownSenderWhenBlank() {
        val payload = MessageFormatter.buildForwardPayload("   ", source, "x")
        assertThat(payload).contains("From Unknown via")
    }

    @Test
    fun sanitizeHeaderField_collapsesNewlinesAndControls() {
        val raw = "Alice\nBob\r\t\u0001X"
        val sanitized = MessageFormatter.sanitizeHeaderField(raw)
        assertThat(sanitized).doesNotContain("\n")
        assertThat(sanitized).doesNotContain("\r")
        assertThat(sanitized).doesNotContain("\t")
        assertThat(sanitized).doesNotContain("\u0001")
    }

    @Test
    fun sanitizeHeaderField_limitsTo64Chars() {
        val long = "A".repeat(100)
        assertThat(MessageFormatter.sanitizeHeaderField(long)).hasLength(64)
    }

    @Test
    fun displaySourceLine_prefersManualThenReportedThenCarrier() {
        val withManual = source.copy(manualNumberE164 = "+19998887777")
        assertThat(MessageFormatter.displaySourceLine(withManual)).isEqualTo("+19998887777")

        val noNumber = source.copy(reportedNumberE164 = null, manualNumberE164 = null)
        assertThat(MessageFormatter.displaySourceLine(noNumber)).isEqualTo("CarrierA")

        val bare = source.copy(
            reportedNumberE164 = null,
            manualNumberE164 = null,
            carrierDisplayName = null,
        )
        assertThat(MessageFormatter.displaySourceLine(bare)).isEqualTo("sub:7")
    }

    @Test
    fun hasLoopMarker_detectsPrefixAfterWhitespace() {
        assertThat(MessageFormatter.hasLoopMarker("[SMS-FWD/1] From x via y\nbody")).isTrue()
        assertThat(MessageFormatter.hasLoopMarker("  \n\t[SMS-FWD/9] rest")).isTrue()
        assertThat(MessageFormatter.hasLoopMarker("Hello [SMS-FWD/1]")).isFalse()
        assertThat(MessageFormatter.hasLoopMarker("")).isFalse()
    }

    @Test
    fun estimateSegmentCount_shortGsmIsOne() {
        assertThat(MessageFormatter.estimateSegmentCount("Hi")).isEqualTo(1)
    }

    @Test
    fun estimateSegmentCount_longGsmIsMultipart() {
        val body = "A".repeat(200)
        assertThat(MessageFormatter.estimateSegmentCount(body)).isAtLeast(2)
    }

    @Test
    fun estimateSegmentCount_unicodeUsesUcs2Budgets() {
        val body = "你".repeat(80)
        assertThat(MessageFormatter.estimateSegmentCount(body)).isAtLeast(2)
    }

    @Test
    fun estimateSegmentCount_emojiPayload_calculatesUcs2Segments() {
        val singleEmojiBody = "Hello 😃" // 6 + 1 + 2 = 9 UTF-16 code units -> 1 segment
        assertThat(MessageFormatter.estimateSegmentCount(singleEmojiBody)).isEqualTo(1)

        val longEmojiBody = "😃".repeat(40) // 80 UTF-16 code units > 70 -> 2 segments
        assertThat(MessageFormatter.estimateSegmentCount(longEmojiBody)).isEqualTo(2)
    }
}
