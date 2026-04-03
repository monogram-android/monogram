package org.monogram.data.utils

import junit.framework.TestCase.assertEquals
import org.junit.Test

/**
 * Test cases for date utils
 **/
class DateUtilsTest {

    @Test
    fun `When date in seconds is provided, then return same mills date`() {
        val test = 1775228794
        val actual = test.toDate().time
        val expected = test * 1000L

        assertEquals(/* expected = */ expected, /* actual = */ actual)
    }

}