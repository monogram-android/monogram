package org.monogram.data.datasource.remote

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class MessageMapOptionsTest {
    @Test
    fun `default mapping keeps interactive enrichment enabled`() {
        val options = MessageMapOptions()

        assertTrue(options.resolveReplyPreviewFromNetwork)
        assertTrue(options.allowAutoDownload)
        assertTrue(options.resolveEnrichmentFromNetwork)
    }

    @Test
    fun `live mapping disables network work`() {
        val options = TdMessageRemoteDataSource.LIVE_MESSAGE_MAP_OPTIONS

        assertFalse(options.resolveReplyPreviewFromNetwork)
        assertFalse(options.allowAutoDownload)
        assertFalse(options.resolveEnrichmentFromNetwork)
    }
}
