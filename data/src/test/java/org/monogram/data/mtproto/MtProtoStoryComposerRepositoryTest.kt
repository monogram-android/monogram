package org.monogram.data.mtproto

import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.monogram.domain.models.stories.StoryComposerDraftModel
import org.monogram.domain.models.stories.StoryComposerMediaItemModel
import org.monogram.domain.models.stories.StoryMediaModel
import org.monogram.domain.models.stories.StoryMediaType
import org.monogram.domain.models.stories.StoryModel
import org.monogram.domain.models.stories.StoryPostResultModel
import org.monogram.domain.models.stories.StoryPrivacyMode
import org.monogram.domain.models.stories.StoryPrivacySettingsModel
import org.monogram.mtproto.handshake.MtProtoHandshakeConfig
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMediaUploadedPhoto
import org.monogram.mtproto.tl.generated.cloud.layer223.InputMediaUploadedDocument
import org.monogram.mtproto.tl.generated.cloud.layer223.DocumentAttributeVideo
import org.monogram.mtproto.tl.generated.cloud.layer223.InputPeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.PeerChat
import org.monogram.mtproto.tl.generated.cloud.layer223.StoryItemDeleted
import org.monogram.mtproto.tl.generated.cloud.layer223.UpdateStory
import org.monogram.mtproto.tl.generated.cloud.layer223.Updates_02c952992b
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.EditStory
import org.monogram.mtproto.tl.generated.cloud.layer223.stories.SendStory
import org.monogram.mtproto.tl.runtime.TlMethod
import org.monogram.mtproto.transport.CloudLayer223ConnectionConfig
import org.monogram.mtproto.transport.MtProtoRpcTransport

class MtProtoStoryComposerRepositoryTest {
    @Test
    fun `posts a photo story then returns the authoritative staged story`() = runBlocking {
        val transport = RecordingTransport()
        val stories = RecordingStories()
        val stager = MtProtoStoryResultStager(stories)
        val expected = story(11)
        val repository = repository(transport, stager, MtProtoStoryReadRepository { _, _, _ -> expected })

        val result = repository.post(-42L, photoDraft())

        val request = transport.request as SendStory
        assertEquals(InputPeerChat(42), request.peer)
        assertEquals("caption", request.caption)
        assertEquals(3600, request.period)
        assertTrue(request.pinned)
        assertTrue(request.noforwards)
        assertTrue(request.media is InputMediaUploadedPhoto)
        assertEquals(MtProtoStoryKey("GROUP", 42L, 11), stories.staged?.key)
        assertEquals(StoryPostResultModel.Success(expected), result)
    }

    @Test
    fun `edits story metadata without replacing media`() = runBlocking {
        val transport = RecordingTransport()
        val stories = RecordingStories()
        val stager = MtProtoStoryResultStager(stories)
        val repository = repository(transport, stager, MtProtoStoryReadRepository { _, _, _ -> null })

        assertTrue(repository.edit(-42L, 11, photoDraft()))

        val request = transport.request as EditStory
        assertEquals(InputPeerChat(42), request.peer)
        assertEquals(11, request.id)
        assertEquals(null, request.media)
        assertEquals("caption", request.caption)
        assertEquals(MtProtoStoryKey("GROUP", 42L, 11), stories.staged?.key)
    }

    @Test
    fun `posts a video story with validated protocol metadata`() = runBlocking {
        val transport = RecordingTransport()
        val repository = repository(
            transport = transport,
            stager = MtProtoStoryResultStager(RecordingStories()),
            reader = MtProtoStoryReadRepository { _, _, _ -> story(11) },
            videoMetadataReader = MtProtoStoryVideoMetadataReader {
                MtProtoStoryVideoMetadata(width = 1920, height = 1080, durationSeconds = 12.5)
            },
        )

        val result = repository.post(
            -42L,
            photoDraft().copy(mediaItems = listOf(StoryComposerMediaItemModel("clip.webm", StoryMediaType.VIDEO))),
        )

        val media = (transport.request as SendStory).media as InputMediaUploadedDocument
        val attribute = media.attributes.single() as DocumentAttributeVideo
        assertEquals("video/webm", media.mimeType)
        assertEquals(1920, attribute.w)
        assertEquals(1080, attribute.h)
        assertEquals(12.5, attribute.duration, 0.0)
        assertTrue(result is StoryPostResultModel.Success)
    }

    private fun repository(
        transport: RecordingTransport,
        stager: MtProtoStoryResultStager,
        reader: MtProtoStoryReadRepository,
        videoMetadataReader: MtProtoStoryVideoMetadataReader = MtProtoStoryVideoMetadataReader {
            error("video metadata must not be requested")
        },
    ) = MtProtoStoryComposerRepositoryImpl(
        configSource = TelegramMtProtoBootstrapConfigSource { config() },
        transportFactory = MtProtoSessionTransportFactory { transport },
        uploader = MtProtoFileUploader { org.monogram.mtproto.tl.generated.cloud.layer223.InputFile_ef0db4e0fa(1, 1, "story.jpg", "hash") },
        users = NoOpMtProtoUserProjectionStore,
        chats = NoOpMtProtoChatProjectionStore,
        storyResultStager = stager,
        storyReader = reader,
        videoMetadataReader = videoMetadataReader,
    )

    private fun photoDraft() = StoryComposerDraftModel(
        mediaItems = listOf(StoryComposerMediaItemModel("story.jpg", StoryMediaType.PHOTO)),
        caption = "caption",
        privacy = StoryPrivacySettingsModel(StoryPrivacyMode.EVERYONE),
        activePeriodSeconds = 3600,
        protectContent = true,
        keepOnProfile = true,
    )

    private fun story(id: Int) = StoryModel(
        id = id,
        posterChatId = -42L,
        date = 1,
        caption = "caption",
        media = StoryMediaModel(StoryMediaType.PHOTO, null, null),
    )

    private fun config() = TelegramMtProtoBootstrapConfig(
        endpoint = TelegramMtProtoEndpoint(2, "dc", 443),
        handshake = MtProtoHandshakeConfig(2, listOf("key")),
        cloud = CloudLayer223ConnectionConfig(1, "device", "system", "app", "en"),
    )

    private class RecordingStories : MtProtoStoryProjectionStore by NoOpMtProtoStoryProjectionStore {
        var staged: MtProtoStoryPayload? = null
        override suspend fun upsert(scope: MtProtoAuthKeyScope, story: MtProtoStoryPayload) {
            staged = story
        }
    }

    private class RecordingTransport : MtProtoRpcTransport {
        lateinit var request: TlMethod<*>

        @Suppress("UNCHECKED_CAST")
        override suspend fun <R> execute(method: TlMethod<R>): R {
            request = method
            return Updates_02c952992b(
                updates = listOf(UpdateStory(PeerChat(42), StoryItemDeleted(11))),
                users = emptyList(),
                chats = emptyList(),
                date = 1,
                seq = 1,
            ) as R
        }

        override fun close() = Unit
    }
}
