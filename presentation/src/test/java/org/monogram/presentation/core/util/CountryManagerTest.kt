package org.monogram.presentation.core.util

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test

private const val FALLBACK_VALUE = 5

/**
 * Test cases for country manager
 **/
class CountryManagerTest {
    private val countryList: List<Country> = CountryManager.getCountries()

    @Test
    fun `When we get countries list, then list is not empty`() {
        val result = countryList.size

        assert(result > 0)
    }

    @Test
    fun `When country mask is empty, then return default value`() {
        val country = countryList.random().copy(mask = "")
        val result = country.getMobileNumberLength()

        assertEquals(FALLBACK_VALUE, result)
    }

    @Test
    fun `When country mask is incorrect, then extension returns default value`() {
        val country = countryList.random().copy(mask = "UNKNOWN")
        val result = country.getMobileNumberLength()

        assertEquals(FALLBACK_VALUE, result)
    }

    @Test
    fun `When number ISO is Telegram anonymous number, then check proceeds normally`() {
        val country = countryList.find { it.name == "Telegram" }
        val result: Int? = country?.getMobileNumberLength()

        assertEquals("42", country!!.code)
        assertEquals(FALLBACK_VALUE, result)
    }

    @Test
    fun `When number ISO is Fragment number, then check proceeds normally`() {
        val country = countryList.find { it.name.contains("Fragment") }
        val result: Int? = country?.getMobileNumberLength()

        assertEquals("888", country!!.code)
        assertEquals(8, result)
    }

    @Test
    fun `When US, RU, UA numbers provided, then all of them passes correctly`() {
        val ruIso = countryList.find { it.iso == "RU" }
        val uaIso = countryList.find { it.iso == "UA" }
        val usIso = countryList.find { it.iso == "US" }

        if (listOfNotNull(ruIso, uaIso, usIso).size != 3) {
            fail("Some of major countries check failed")
        }

        // without country code
        assertEquals(10, ruIso!!.getMobileNumberLength())
        assertEquals(9, uaIso!!.getMobileNumberLength())
        assertEquals(10, usIso!!.getMobileNumberLength())
    }

    @Test
    fun `When all countries checked, then all checks passed`() {
        countryList.forEach { country ->
            assert(country.getMobileNumberLength() >= FALLBACK_VALUE) {
                fail("Failed, country $country lower than of fallback value")
            }
        }
    }

    @Test
    fun `When country phone is invalid, then return null value`() {
        val result = CountryManager.getCountryForPhone("null")
        assertEquals(null, result)
    }

    @Test
    fun `When country phone is valid, then return valid value`() {
        val country = Country(
            "Russian Federation",
            "7",
            iso = "RU",
            flagEmoji = "\uD83C\uDDF7\uD83C\uDDFA",
            mask = "XXX XXX XXXX"
        )
        val result = CountryManager.getCountryForPhone("79000000001")
        assertEquals(country, result)
    }

    @Test
    fun `format russian number`() =
        assertEquals("+7 999 123-45-67", CountryManager.formatPhoneNumber("+79991234567"))

    @Test
    fun `format returns raw string on invalid input`() =
        assertEquals("notaphone", CountryManager.formatPhoneNumber("notaphone"))

    @Test
    fun `format empty string returns empty`() =
        assertEquals("", CountryManager.formatPhoneNumber(""))

    @Test
    fun `mask hides all digits except last 4`() =
        assertEquals("+7 *** ***-45-67", CountryManager.maskPhoneNumber("+7 999 123-45-67"))

    @Test
    fun `mask short number returns stars`() =
        assertEquals("****", CountryManager.maskPhoneNumber("+123"))

    @Test
    fun `mask exactly 4 digits returns stars`() =
        assertEquals("****", CountryManager.maskPhoneNumber("+123 4"))

    @Test
    fun `mask preserves plus sign`() {
        val result = CountryManager.maskPhoneNumber("+7 (999) 123-45-67")
        assertTrue(result.startsWith("+"))
    }

    @Test
    fun `valid russian number`() =
        assertTrue(CountryManager.isValidPhoneNumber("+79991234567", "RU"))

    @Test
    fun `invalid number`() =
        assertFalse(CountryManager.isValidPhoneNumber("123", "RU"))

    @Test
    fun `empty string is invalid`() =
        assertFalse(CountryManager.isValidPhoneNumber("", "RU"))

    @Test
    fun `fragment number is valid`() =
        assertTrue(CountryManager.isValidPhoneNumber("+88808419042", "FT"))

    @Test
    fun `fragment number format`() =
        assertEquals("+888 0841 9042", CountryManager.formatPhoneNumber("+88808419042"))

    @Test
    fun `fragment number mask`() =
        assertEquals("+888 **** 9042", CountryManager.maskPhoneNumber("+888 0841 9042"))

    @Test
    fun `international networks number is valid`() =
        assertTrue(CountryManager.isValidPhoneNumber("+883510000000001", "GO"))

    @Test
    fun `international networks number format`() =
        assertEquals("+883510000000001", CountryManager.formatPhoneNumber("+883510000000001"))

    @Test
    fun `international networks number mask`() =
        assertEquals("+883********0001", CountryManager.maskPhoneNumber("+883510000000001"))

    @Test
    fun `Y-land number is valid`() =
        assertTrue(CountryManager.isValidPhoneNumber("42777", "YL"))

    @Test
    fun `Y-land number format`() =
        assertEquals("42777", CountryManager.formatPhoneNumber("42777"))

    @Test
    fun `Y-land number mask`() =
        assertEquals("42777", CountryManager.maskPhoneNumber("42777"))

    @Test
    fun `getExampleNumber returns valid example`() =
        assertEquals("+7 000 000-00-00", CountryManager.getExampleNumber("RU"))
}

