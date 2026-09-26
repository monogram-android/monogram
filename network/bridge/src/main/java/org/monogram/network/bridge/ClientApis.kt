package org.monogram.network.bridge

import org.monogram.network.bridge.chat.ChatApi
import org.monogram.network.bridge.media.MediaApi
import org.monogram.network.bridge.message.MessageApi
import org.monogram.network.bridge.notify.NotifyApi
import org.monogram.network.bridge.profile.ProfileApi
import org.monogram.network.bridge.session.SessionCore
import org.monogram.network.bridge.updates.UpdatesPump
import org.monogram.network.bridge.web.WebApi

internal class ClientApis(core: SessionCore) {
    internal val core: SessionCore = core
    internal val profile = ProfileApi(core)
    internal val chat = ChatApi(core)
    internal val message = MessageApi(core, profile)
    internal val media = MediaApi(core)
    internal val web = WebApi(core)
    internal val notify = NotifyApi(core)
    internal val updates = UpdatesPump(core)
}