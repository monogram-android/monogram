package org.monogram

import android.app.PictureInPictureParams
import android.content.Intent
import android.content.pm.PackageManager
import android.content.res.Configuration
import android.os.Build
import android.os.Bundle
import android.util.Rational
import androidx.activity.ComponentActivity
import androidx.activity.SystemBarStyle
import androidx.activity.compose.setContent
import androidx.activity.enableEdgeToEdge
import androidx.compose.foundation.isSystemInDarkTheme
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.runtime.CompositionLocalProvider
import androidx.compose.runtime.collectAsState
import androidx.compose.runtime.getValue
import androidx.compose.runtime.mutableStateOf
import androidx.compose.runtime.remember
import androidx.compose.runtime.setValue
import androidx.compose.ui.Modifier
import androidx.core.splashscreen.SplashScreen.Companion.installSplashScreen
import androidx.lifecycle.lifecycleScope
import com.arkivanov.decompose.defaultComponentContext
import com.arkivanov.mvikotlin.main.store.DefaultStoreFactory
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import org.monogram.core.common.AppLog
import org.monogram.core.common.Outcome
import org.monogram.core.common.PerfLog
import org.monogram.core.database.SessionMetadataStore
import org.monogram.core.ui.AppearanceSettings
import org.monogram.core.ui.ImageCache
import org.monogram.core.ui.ThemePreference
import org.monogram.core.ui.media.LocalPictureInPictureActive
import org.monogram.core.ui.media.LocalPictureInPictureController
import org.monogram.core.ui.media.MediaPlaybackHolder
import org.monogram.core.ui.media.MediaSurface
import org.monogram.core.ui.media.MediaViewerPipStage
import org.monogram.core.ui.media.MediaViewerSharedRoot
import org.monogram.core.ui.media.PictureInPictureController
import org.monogram.core.ui.theme.MonogramTheme
import org.monogram.push.NotificationAccountRoute
import org.monogram.push.NotificationPresenter
import org.monogram.push.notificationAccountRoute
import org.monogram.root.IncomingShare
import org.monogram.root.IncomingShareStager
import org.monogram.root.RootComponent
import org.monogram.root.RootContent
import android.graphics.Color as AndroidColor

open class MainActivity : ComponentActivity() {
    private lateinit var root: RootComponent
    private val startupReady = CompletableDeferred<Unit>()
    private var startJob: Job? = null
    private var inPictureInPicture by mutableStateOf(false)
    private var pendingIncomingShare: IncomingShare? = null
    private var shareIntentHandled = false
    private var notificationIntentHandled = false
    private var linkIntentHandled = false
    private var accountSwitchUserId: Long? = null
    private var accountSwitchDestination: RootComponent.Config? = null

    companion object {
        private const val STATE_INCOMING_SHARE = "main.incoming_share"
        private const val STATE_SHARE_INTENT_HANDLED = "main.share_intent_handled"
        private const val STATE_NOTIFICATION_INTENT_HANDLED = "main.notification_intent_handled"
        private const val STATE_LINK_INTENT_HANDLED = "main.link_intent_handled"
        private const val STATE_SWITCH_USER_ID = "main.switch_user_id"
        private const val STATE_SWITCH_DESTINATION = "main.switch_destination"
    }

    /** Only this activity owns the PiP window; the media viewer stays in its dialog. */
    private val pictureInPicture = object : PictureInPictureController {
        override val supported: Boolean
            get() = Build.VERSION.SDK_INT >= Build.VERSION_CODES.O && packageManager.hasSystemFeature(
                PackageManager.FEATURE_PICTURE_IN_PICTURE
            )

        override fun enter() {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (!supported) return
            val params = PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build()
            runCatching { enterPictureInPictureMode(params) }
        }

        override fun updateActions(playing: Boolean, canSkipNext: Boolean) {
            if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
            if (!supported) return
            runCatching {
                setPictureInPictureParams(
                    PictureInPictureParams.Builder().setAspectRatio(Rational(16, 9)).build(),
                )
            }
        }
    }

    override fun onPictureInPictureModeChanged(
        isInPictureInPictureMode: Boolean,
        newConfig: Configuration,
    ) {
        super.onPictureInPictureModeChanged(isInPictureInPictureMode, newConfig)
        inPictureInPicture = isInPictureInPictureMode
        MediaPlaybackHolder.peek()?.attachSurface(
            if (isInPictureInPictureMode) MediaSurface.PIP
            else MediaSurface.MINI_PLAYER,
        )
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        val splashScreen = installSplashScreen()
        splashScreen.setKeepOnScreenCondition { !startupReady.isCompleted }
        super.onCreate(savedInstanceState)
        pendingIncomingShare = IncomingShare.fromBundle(
            savedInstanceState?.getBundle(STATE_INCOMING_SHARE),
        )
        shareIntentHandled = savedInstanceState?.getBoolean(STATE_SHARE_INTENT_HANDLED) == true
        notificationIntentHandled =
            savedInstanceState?.getBoolean(STATE_NOTIFICATION_INTENT_HANDLED) == true
        linkIntentHandled = savedInstanceState?.getBoolean(STATE_LINK_INTENT_HANDLED) == true
        accountSwitchUserId = savedInstanceState?.getLong(STATE_SWITCH_USER_ID)?.takeIf { it != 0L }
        accountSwitchDestination = accountSwitchDestinationFromKey(
            savedInstanceState?.getString(STATE_SWITCH_DESTINATION),
        )
        val crashLog = AppLog.readCrashLog()
        if (crashLog != null) {
            AppLog.clearCrashLog()
            startActivity(CrashActivity.intent(this, crashLog))
            finish()
            return
        }
        if (this !is BubbleActivity) enableEdgeToEdge(
            statusBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT,
            ),
            navigationBarStyle = SystemBarStyle.auto(
                lightScrim = AndroidColor.TRANSPARENT,
                darkScrim = AndroidColor.TRANSPARENT,
            ),
        )
        val app = application as MonogramApp
        val componentContext = defaultComponentContext()
        lifecycleScope.launch {
            app.accountStatusChanges.collect {
                if (startupReady.isCompleted && !isFinishing) recreate()
            }
        }
        lifecycleScope.launch {
            app.awaitReady()
            if (!notificationIntentHandled && isNotificationIntent(intent.action)) {
                when (val route = notificationAccountRoute(
                    intent.getLongExtra(NotificationPresenter.EXTRA_ACCOUNT_USER_ID, 0L),
                    app.accountRegistry.activeUserId,
                    app.accountRegistry.all(),
                )) {
                    NotificationAccountRoute.Reject -> {
                        finish()
                        return@launch
                    }

                    NotificationAccountRoute.Open -> Unit
                    is NotificationAccountRoute.Switch -> app.switchAccount(route.userId)
                }
            }
            val loadedAccountMenuItems = app.accountMenuItems().associateBy { it.userId }
            val bindings = app.rootAccountBindings()
            val accountMenuItems = bindings.accountUserIds.map { id ->
                loadedAccountMenuItems[id]?.copy(active = id == bindings.account?.userId)
                    ?: org.monogram.feature.chats.AccountMenuItem(
                        userId = id,
                        title = getString(
                            org.monogram.feature.chats.R.string.chats_account_fallback, id
                        ),
                        active = id == bindings.account?.userId,
                        unavailable = true,
                    )
            }

            fun boundAccountId(): Long? = bindings.account?.userId
                ?: app.accountRegistry.activeUserId?.takeIf { app.client === bindings.client }

            val startOnHome = withContext(Dispatchers.IO) {
                resolveStartOnHome(
                    sessionAuthorized = { bindings.sessionStore.isAuthorized() },
                    locallyAuthorized = { bindings.client.isLocallyAuthorized() },
                    explicitlySignedOut = {
                        bindings.sessionStore.readMeta(SessionMetadataStore.KEY_AUTHORIZED) == "0"
                    },
                )
            }
            root = RootComponent(
                componentContext = componentContext,
                storeFactory = DefaultStoreFactory(),
                client = bindings.client,
                warmup = bindings.warmup,
                sessionStore = bindings.sessionStore,
                mediaRepository = bindings.mediaRepository,
                startOnHome = startOnHome,
                accountSwitchDestination = accountSwitchDestination.takeIf {
                    accountSwitchUserId == bindings.account?.userId
                },
                pushRegistration = bindings.push,
                notificationLocal = bindings.notifications,
                onIncomingShareConsumed = { pendingIncomingShare = null },
                appUpdate = app.appUpdate,
                bubbleChatId = if (this@MainActivity is BubbleActivity) {
                    intent.getLongExtra(NotificationPresenter.EXTRA_CHAT_ID, 0L).takeIf { it != 0L }
                } else null,
                liveLocations = bindings.liveLocations,
                onRootBack = { finish() },
                onAccountWorkStop = {
                    boundAccountId()?.let { app.stopAccountWork(it) }
                },
                onAccountWorkResume = {
                    if (app.client === bindings.client) app.resumeActiveAccountWork()
                },
                onAccountAuthorized = app::adoptAuthorizedAccount,
                onBeginAddAccount = app::beginAdditionalAuthorization,
                onAdditionalAccountAuthorized = { session, pending ->
                    app.adoptAdditionalAuthorized(session, pending)
                    recreate()
                },
                onAccountLoggedOut = {
                    val userId = boundAccountId()
                    if (userId != null) app.stopAccountWork(userId)
                    if (userId != null && app.accountRegistry.all().size > 1) {
                        app.removeAccount(userId)
                        recreate()
                    }
                },
                accountUserIds = bindings.accountUserIds,
                accountMenuItems = accountMenuItems,
                loadAccountMenuItems = app::accountMenuItems,
                loadAccountDetails = app::loadSettingsAccountDetails,
                accountNeedsSignInIds = accountMenuItems.filter { it.needsSignIn }
                    .mapTo(mutableSetOf()) { it.userId },
                activeAccountUserId = bindings.account?.userId,
                activeAccountDcId = bindings.account?.dcId,
                onSwitchAccount = { userId ->
                    val destination = root.accountSwitchDestination()
                    app.switchAccount(userId)
                    accountSwitchUserId = userId
                    accountSwitchDestination = destination
                    recreate()
                },
                onRemoveAccount = { userId ->
                    app.removeAccount(userId)
                    recreate()
                },
            )
            accountSwitchUserId = null
            accountSwitchDestination = null
            if (pendingIncomingShare != null) {
                root.openIncomingShare(pendingIncomingShare!!)
            } else if (shouldHandleIncomingIntent(
                    intent.action,
                    shareIntentHandled,
                    notificationIntentHandled,
                    linkIntentHandled
                )
            ) {
                handleIncomingIntent(intent)
            }
            val imageCacheRoot = bindings.mediaRepository.cacheRoot
            setContent {
                ImageCache.AccountScope(imageCacheRoot) {
                    val appearance by AppearanceSettings.state.collectAsState()
                    val systemDark = isSystemInDarkTheme()
                    val darkTheme = when (appearance.theme) {
                        ThemePreference.Light -> false
                        ThemePreference.Dark -> true
                        ThemePreference.System -> systemDark
                    }
                    MonogramTheme(
                        darkTheme = darkTheme,
                        dynamicColor = appearance.dynamicColor,
                        accentPreset = appearance.accentPreset,
                    ) {
                        val pip = remember { pictureInPicture }
                        CompositionLocalProvider(
                            LocalPictureInPictureController provides pip,
                            LocalPictureInPictureActive provides inPictureInPicture,
                        ) {
                            Surface(
                                modifier = Modifier.fillMaxSize(),
                                color = MaterialTheme.colorScheme.surface,
                            ) {
                                if (inPictureInPicture) {
                                    val session = MediaPlaybackHolder.peek()
                                    if (session?.current != null) {
                                        MediaViewerPipStage(
                                            session = session, modifier = Modifier.fillMaxSize()
                                        )
                                    } else {
                                        RootContent(
                                            component = root, modifier = Modifier.fillMaxSize()
                                        )
                                    }
                                } else {
                                    MediaViewerSharedRoot {
                                        RootContent(
                                            component = root,
                                            modifier = Modifier.fillMaxSize(),
                                        )
                                    }
                                }
                            }
                        }
                    }
                }
            }
            startupReady.complete(Unit)
        }
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            shareIntentHandled = false
        }
        if (isNotificationIntent(intent.action)) notificationIntentHandled = false
        if (intent.dataString != null) linkIntentHandled = false
        if (startupReady.isCompleted) handleIncomingIntent(intent)
    }

    override fun onSaveInstanceState(outState: Bundle) {
        pendingIncomingShare?.toBundle()?.let { outState.putBundle(STATE_INCOMING_SHARE, it) }
        outState.putBoolean(STATE_NOTIFICATION_INTENT_HANDLED, notificationIntentHandled)
        outState.putBoolean(STATE_LINK_INTENT_HANDLED, linkIntentHandled)
        accountSwitchUserId?.let { outState.putLong(STATE_SWITCH_USER_ID, it) }
        accountSwitchDestination?.let {
            outState.putString(STATE_SWITCH_DESTINATION, accountSwitchDestinationKey(it))
        }
        outState.putBoolean(
            STATE_SHARE_INTENT_HANDLED,
            shareIntentHandled || runCatching { root.hasIncomingShare() }.getOrDefault(false),
        )
        super.onSaveInstanceState(outState)
    }

    override fun onStart() {
        super.onStart()
        startJob?.cancel()
        val app = application as MonogramApp
        app.activityStarted(this)
        startJob = lifecycleScope.launch {
            app.awaitReady()
            app.push.requestPermission(this@MainActivity)
            startupReady.await()
            val started = PerfLog.nowMs()
            when (val result = runCatching { app.client.connect() }.getOrNull()) {
                is Outcome.Ok -> PerfLog.mark(
                    "activity:connect", PerfLog.nowMs() - started, "result=ok"
                )

                is Outcome.Err -> PerfLog.mark(
                    "activity:connect", PerfLog.nowMs() - started, "result=err"
                )

                null -> PerfLog.mark("activity:connect", PerfLog.nowMs() - started, "result=throw")
            }
        }
    }

    override fun onStop() {
        val app = application as MonogramApp
        startJob?.cancel()
        app.activityStopped(this)
        super.onStop()
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent == null) return
        if (intent.action == Intent.ACTION_SEND || intent.action == Intent.ACTION_SEND_MULTIPLE) {
            lifecycleScope.launch {
                val share = withContext(Dispatchers.IO) {
                    IncomingShareStager.stage(this@MainActivity, intent)
                } ?: return@launch
                pendingIncomingShare = share
                shareIntentHandled = true
                root.openIncomingShare(share)
            }
            return
        }
        if (isNotificationIntent(intent.action)) {
            val registry = (application as MonogramApp).accountRegistry
            val accountId = intent.getLongExtra(NotificationPresenter.EXTRA_ACCOUNT_USER_ID, 0L)
            when (val route = notificationAccountRoute(
                accountId,
                registry.activeUserId,
                registry.all(),
            )) {
                NotificationAccountRoute.Reject -> {
                    notificationIntentHandled = true
                    return
                }
                NotificationAccountRoute.Open -> Unit
                is NotificationAccountRoute.Switch -> {
                    lifecycleScope.launch {
                        (application as MonogramApp).switchAccount(route.userId)
                        recreate()
                    }
                    return
                }
            }
            notificationIntentHandled = true
        }
        if (intent.action == NotificationPresenter.ACTION_OPEN_CHAT) {
            val chatId = intent.getLongExtra(NotificationPresenter.EXTRA_CHAT_ID, 0L)
            val messageId = intent.getIntExtra(NotificationPresenter.EXTRA_MESSAGE_ID, 0)
            if (chatId != 0L) root.openFromNotification(chatId, messageId)
            return
        }
        if (intent.action == NotificationPresenter.ACTION_OPEN_NOTIFICATIONS) {
            root.openNotificationSettings()
            return
        }
        val uri = intent.dataString ?: return
        root.openTelegramUri(uri)
        linkIntentHandled = true
    }
}

internal fun isNotificationIntent(action: String?): Boolean =
    action == NotificationPresenter.ACTION_OPEN_CHAT || action == NotificationPresenter.ACTION_OPEN_NOTIFICATIONS

internal fun shouldHandleIncomingIntent(
    action: String?,
    shareHandled: Boolean,
    notificationHandled: Boolean,
    linkHandled: Boolean = false,
): Boolean = when {
    action == Intent.ACTION_SEND || action == Intent.ACTION_SEND_MULTIPLE -> !shareHandled
    isNotificationIntent(action) -> !notificationHandled
    else -> !linkHandled
}

internal fun accountSwitchDestinationKey(destination: RootComponent.Config): String =
    when (destination) {
        is RootComponent.Config.Settings -> when {
            destination.openProxy -> "settings_proxy"
            destination.openFolders -> "settings_folders"
            destination.openNotifications -> "settings_notifications"
            else -> "settings"
    }

        RootComponent.Config.Proxy -> "proxy"
        else -> "home"
    }

internal fun accountSwitchDestinationFromKey(key: String?): RootComponent.Config? = when (key) {
    "home" -> RootComponent.Config.Home
    "settings" -> RootComponent.Config.Settings()
    "settings_proxy" -> RootComponent.Config.Settings(openProxy = true)
    "settings_folders" -> RootComponent.Config.Settings(openFolders = true)
    "settings_notifications" -> RootComponent.Config.Settings(openNotifications = true)
    "proxy" -> RootComponent.Config.Proxy
    else -> null
}
