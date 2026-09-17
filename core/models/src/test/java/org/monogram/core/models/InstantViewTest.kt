package org.monogram.core.models

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class InstantViewTest {
    @Test
    fun parseMediaKeyMapsPhotoDocumentAvatarAndGeo() {
        val photo = InstantViewPages.parseMediaKey("photo:77")
        requireNotNull(photo)
        assertEquals(77L, photo.id)
        assertEquals(INSTANT_VIEW_MEDIA_MSG_ID, photo.messageId)
        val avatar = InstantViewPages.parseMediaKey("avatar:-1001")
        requireNotNull(avatar)
        assertEquals(-1001L, avatar.id)
        assertEquals(0, avatar.messageId)
        val geo = InstantViewPages.parseMediaKey("geo:99:12")
        requireNotNull(geo)
        assertEquals(99L, geo.id)
        assertEquals(INSTANT_VIEW_MEDIA_MSG_ID, geo.messageId)
        assertEquals(null, InstantViewPages.parseMediaKey("photo:"))
        assertEquals(null, InstantViewPages.parseMediaKey(""))
    }

    @Test
    fun blockStableKeyPrefersMediaIdentity() {
        assertEquals(
            "photo:77",
            InstantViewPages.blockStableKey(
                3,
                InstantViewBlock.Photo(id = 77, cacheKey = "photo:77"),
            ),
        )
        assertEquals(
            "video:9",
            InstantViewPages.blockStableKey(
                1,
                InstantViewBlock.Document(kind = "video", id = 9, cacheKey = "doc:9"),
            ),
        )
    }

    @Test
    fun pageTitleIsHiddenWhenTitleBlockMatches() {
        val page = InstantViewPage(
            url = "https://ex",
            displayUrl = "https://ex",
            title = "Techjunkie Aman",
            blocks = listOf(
                InstantViewBlock.Text(kind = "title", text = "Techjunkie Aman"),
                InstantViewBlock.Text(kind = "paragraph", text = "Body"),
            ),
        )
        assertFalse(InstantViewPages.shouldShowPageTitle(page))
        assertTrue(
            InstantViewPages.shouldShowPageTitle(
                page.copy(blocks = listOf(InstantViewBlock.Text(kind = "paragraph", text = "Body"))),
            ),
        )
        assertFalse(
            InstantViewPages.shouldShowPageTitle(
                page.copy(title = "  "),
            ),
        )
    }

    @Test
    fun eligibilityFollowsOfficialWebpageTypeRules() {
        assertTrue(InstantViewPages.offersInstantView("article", true))
        assertTrue(InstantViewPages.offersInstantView(null, true))
        assertFalse(InstantViewPages.offersInstantView("article", false))
        assertFalse(InstantViewPages.offersInstantView("telegram_album", true))
        assertFalse(InstantViewPages.offersInstantView("telegram_message", true))
    }

    @Test
    fun parseKeepsDocumentedBlockKinds() {
        val blocks = InstantViewPages.parseBlocksJson(
            """
            [
              {"k":"title","t":"Hello","e":[{"k":"bold","o":0,"l":5}]},
              {"k":"paragraph","t":"Body"},
              {"k":"photo","id":77,"cache":"photo:77","w":800,"h":450},
              {"k":"related","title":{"t":"More"},"articles":[{"url":"https://ex","title":"Next"}]},
              {"k":"unsupported"}
            ]
            """.trimIndent(),
        )
        assertEquals(5, blocks.size)
        assertTrue(blocks[0] is InstantViewBlock.Text)
        assertEquals("Hello", (blocks[0] as InstantViewBlock.Text).text)
        assertEquals("bold", (blocks[0] as InstantViewBlock.Text).entities.single().kind)
        assertTrue(blocks[2] is InstantViewBlock.Photo)
        assertEquals("photo:77", (blocks[2] as InstantViewBlock.Photo).cacheKey)
        assertEquals("https://ex", (blocks[3] as InstantViewBlock.Related).articles.single().url)
        assertTrue(blocks[4] is InstantViewBlock.Unsupported)
    }

    @Test
    fun parseKeepsTableMapVideoAndAnchor() {
        val blocks = InstantViewPages.parseBlocksJson(
            """
            [
              {"k":"anchor","n":"intro"},
              {"k":"video","id":9,"cache":"doc:9","autoplay":true,"loop":true,"w":1280,"h":720},
              {"k":"table","bordered":true,"striped":true,"rows":[[{"t":"A","h":true,"cs":2,"rs":1}]]},
              {"k":"map","lat":1.5,"lng":2.5,"zoom":12,"w":320,"h":180},
              {"k":"slideshow","items":[{"k":"photo","id":1,"cache":"photo:1"}]}
            ]
            """.trimIndent(),
        )
        assertEquals("intro", (blocks[0] as InstantViewBlock.Anchor).name)
        val video = blocks[1] as InstantViewBlock.Document
        assertEquals("video", video.kind)
        assertTrue(video.autoplay && video.loop)
        assertEquals(1280, video.width)
        assertEquals(720, video.height)
        val table = blocks[2] as InstantViewBlock.Table
        assertTrue(table.bordered && table.striped)
        assertEquals(2, table.rows.single().single().colspan)
        val map = blocks[3] as InstantViewBlock.Map
        assertEquals(1.5, map.latitude, 0.0)
        assertEquals(2.5, map.longitude, 0.0)
        assertEquals("slideshow", (blocks[4] as InstantViewBlock.MediaGroup).kind)
    }

    @Test
    fun parseKeepsDocumentMetaRelatedAndMapCache() {
        val blocks = InstantViewPages.parseBlocksJson(
            """
            [
              {"k":"document","id":8,"cache":"doc:8","name":"notes.pdf","mime":"application/pdf","size":2048},
              {"k":"related","articles":[{"url":"https://ex","title":"Next","description":"More","author":"Ada","date":1700000000,"photo":"photo:1"}]},
              {"k":"map","lat":1.5,"lng":2.5,"zoom":12,"w":320,"h":180,"cache":"geo:9:12"},
              {"k":"authorDate","t":"Ada","d":1700000000}
            ]
            """.trimIndent(),
        )
        val doc = blocks[0] as InstantViewBlock.Document
        assertEquals("notes.pdf", doc.fileName)
        assertEquals("application/pdf", doc.mimeType)
        assertEquals(2048L, doc.fileSize)
        assertEquals("2 KB", InstantViewPages.formatFileSize(doc.fileSize))
        val related = (blocks[1] as InstantViewBlock.Related).articles.single()
        assertEquals("photo:1", related.photoCacheKey)
        assertTrue(InstantViewPages.relatedSubtitle(related).contains("Ada"))
        assertEquals("geo:9:12", (blocks[2] as InstantViewBlock.Map).cacheKey)
        assertEquals("Ada", (blocks[3] as InstantViewBlock.Text).text)
        assertEquals(1700000000, (blocks[3] as InstantViewBlock.Text).publishedDate)
        assertEquals("2023-11-14", InstantViewPages.formatPublishedDate(1700000000))
    }

    @Test
    fun fetchDecisionRefetchesPartialPagesOnce() {
        val partial = InstantViewPage(
            url = "https://example.com/a",
            displayUrl = "example.com/a",
            hasInstantView = true,
            part = true,
            hash = 4,
        )
        assertTrue(
            InstantViewPages.decideFetch(partial, alreadyRefetchedPartial = false)
                is InstantViewFetchDecision.RefetchFull,
        )
        assertTrue(
            InstantViewPages.decideFetch(partial, alreadyRefetchedPartial = true)
                is InstantViewFetchDecision.Show,
        )
        assertTrue(
            InstantViewPages.decideFetch(partial.copy(notModified = true), false)
                is InstantViewFetchDecision.KeepExisting,
        )
    }

    @Test
    fun linksResolveAnchorsAndSamePageHashes() {
        val current = "https://example.com/article"
        assertEquals(
            InstantViewLink.Anchor("intro"),
            InstantViewPages.resolveLink(current, current, "#intro"),
        )
        assertEquals(
            InstantViewLink.Anchor("intro"),
            InstantViewPages.resolveLink(current, current, "https://example.com/article#intro"),
        )
        val page = InstantViewPages.resolveLink(current, current, "https://other.example/b")
        assertTrue(page is InstantViewLink.Page)
        assertEquals("https://other.example/b", (page as InstantViewLink.Page).url)
        assertTrue(
            InstantViewPages.resolveLink(current, current, "mailto:a@b.c") is InstantViewLink.External,
        )
    }

    @Test
    fun findAnchorIndexWalksCoverAndTopLevel() {
        val blocks = listOf(
            InstantViewBlock.Text("paragraph", "hi"),
            InstantViewBlock.Cover(InstantViewBlock.Anchor("cover-target")),
            InstantViewBlock.Anchor("tail"),
        )
        assertEquals(1, InstantViewPages.findAnchorIndex(blocks, "cover-target"))
        assertEquals(2, InstantViewPages.findAnchorIndex(blocks, "tail"))
        assertEquals(null, InstantViewPages.findAnchorIndex(blocks, "missing"))
    }

    @Test
    fun findAnchorIndexUsesInTextAnchors() {
        val blocks = InstantViewPages.parseBlocksJson(
            """
            [
              {"k":"paragraph","t":"See intro","e":[{"k":"anchor","o":4,"l":5,"u":"#intro"}]},
              {"k":"paragraph","t":"plain"}
            ]
            """.trimIndent(),
        )
        assertEquals(0, InstantViewPages.findAnchorIndex(blocks, "intro"))
        assertEquals(null, InstantViewPages.findAnchorIndex(blocks, "missing"))
    }

    @Test
    fun placeTableOccupiesRowspanAndColspan() {
        val layout = InstantViewPages.placeTable(
            listOf(
                listOf(
                    InstantViewTableCell("A", rowspan = 2),
                    InstantViewTableCell("B", colspan = 2),
                ),
                listOf(InstantViewTableCell("C")),
            ),
        )
        assertEquals(2, layout.rowCount)
        assertEquals(3, layout.columnCount)
        assertEquals(0, layout.cells[0].row)
        assertEquals(0, layout.cells[0].col)
        assertEquals(0, layout.cells[1].row)
        assertEquals(1, layout.cells[1].col)
        assertEquals(1, layout.cells[2].row)
        assertEquals(1, layout.cells[2].col)
        assertEquals("C", layout.cells[2].cell.text)
    }

    @Test
    fun parseKeepsEmbedPostChannelCaptionAndButtonUrl() {
        val blocks = InstantViewPages.parseBlocksJson(
            """
            [
              {"k":"embedPost","url":"https://t.me/p","author":"Ada","date":1700000000,"photo":"photo:3","blocks":[{"k":"paragraph","t":"Quoted"}],"caption":{"t":"Cap","credit":{"t":"Credit"}}},
              {"k":"channel","id":-1001,"title":"News","username":"news","photo":"avatar:-1001"},
              {"k":"photo","id":4,"cache":"photo:4","url":"https://ex/p","caption":{"t":"Photo cap"}},
              {"k":"buttons","items":[{"t":"Open","e":[{"k":"text_url","o":0,"l":4,"u":"https://ex/go"}]}]},
              {"k":"embed","url":"https://ex/e","poster":"photo:9","caption":{"t":"Embed cap"}},
              {"k":"audio","id":5,"cache":"doc:5","title":"Song","performer":"Ada","duration":90}
            ]
            """.trimIndent(),
        )
        val post = blocks[0] as InstantViewBlock.EmbedPost
        assertEquals("Ada", post.author)
        assertEquals(1700000000, post.date)
        assertEquals("photo:3", post.photoCacheKey)
        assertEquals("Quoted", (post.blocks.single() as InstantViewBlock.Text).text)
        assertEquals("Cap", post.caption?.text)
        assertEquals("Credit", post.caption?.credit?.text)
        val channel = blocks[1] as InstantViewBlock.Channel
        assertEquals("News", channel.title)
        assertEquals("news", channel.username)
        assertEquals("avatar:-1001", channel.photoCacheKey)
        val photo = blocks[2] as InstantViewBlock.Photo
        assertEquals("https://ex/p", photo.url)
        assertEquals("Photo cap", photo.caption?.text)
        val button = (blocks[3] as InstantViewBlock.Buttons).items.single()
        assertEquals("Open", button.text)
        assertEquals("https://ex/go", button.entities.single().url)
        val embed = blocks[4] as InstantViewBlock.Embed
        assertEquals("photo:9", embed.posterCacheKey)
        assertEquals("Embed cap", embed.caption?.text)
        val audio = blocks[5] as InstantViewBlock.Document
        assertEquals("Song", audio.title)
        assertEquals("Ada", audio.performer)
        assertEquals(90, audio.durationSeconds)
    }
}
