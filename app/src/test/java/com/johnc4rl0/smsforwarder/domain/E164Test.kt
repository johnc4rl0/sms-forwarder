package com.johnc4rl0.smsforwarder.domain

import com.google.common.truth.Truth.assertThat
import org.junit.Test

class E164Test {

    @Test
    fun isValid_accepts8To15DigitsWithPlus() {
        assertThat(E164.isValid("+12345678")).isTrue() // 8 digits
        assertThat(E164.isValid("+15551234567")).isTrue() // 11
        assertThat(E164.isValid("+123456789012345")).isTrue() // 15
    }

    @Test
    fun isValid_rejectsTooShortTooLongOrMissingPlus() {
        assertThat(E164.isValid("+1234567")).isFalse() // 7
        assertThat(E164.isValid("+1234567890123456")).isFalse() // 16
        assertThat(E164.isValid("15551234567")).isFalse()
        assertThat(E164.isValid("555-1234")).isFalse()
        assertThat(E164.isValid(null)).isFalse()
        assertThat(E164.isValid("")).isFalse()
        assertThat(E164.isValid("+0123456789")).isFalse() // leading 0 country
    }

    @Test
    fun isValid_rejectsLocalFormats() {
        assertThat(E164.isValid("5551234")).isFalse()
        assertThat(E164.isValid("(555) 123-4567")).isFalse()
        assertThat(E164.isValid("00 1 555 123 4567")).isFalse()
    }

    @Test
    fun normalize_stripsFormatting() {
        assertThat(E164.normalize("+1 (555) 123-4567")).isEqualTo("+15551234567")
        assertThat(E164.normalize("+15551234567")).isEqualTo("+15551234567")
    }

    @Test
    fun isLocalNumber_matchesKnownLines() {
        val locals = listOf("+15551111111", "+15552222222", "5553333333")
        assertThat(E164.isLocalNumber("+15551111111", locals)).isTrue()
        assertThat(E164.isLocalNumber("+1 555 111 1111", locals)).isTrue()
        assertThat(E164.isLocalNumber("+15553333333", locals)).isTrue() // 10-digit national matches E.164
        assertThat(E164.isLocalNumber("+15554444444", locals)).isFalse()
    }

    @Test
    fun senderMatchesDestination_digitEquality_andNationalFormat() {
        assertThat(E164.senderMatchesDestination("+15559999999", "+15559999999")).isTrue()
        assertThat(E164.senderMatchesDestination("15559999999", "+15559999999")).isTrue()
        assertThat(E164.senderMatchesDestination("5559999999", "+15559999999")).isTrue() // National MO format matches E.164
        assertThat(E164.senderMatchesDestination("+15550000000", "+15559999999")).isFalse()
        assertThat(E164.senderMatchesDestination(null, "+15559999999")).isFalse()
    }
}
