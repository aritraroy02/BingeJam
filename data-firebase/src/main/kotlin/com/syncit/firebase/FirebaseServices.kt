package com.syncit.firebase

import android.content.Context
import com.google.firebase.auth.FirebaseAuth
import com.google.firebase.database.FirebaseDatabase
import com.syncit.core.auth.AuthService
import com.syncit.core.lookup.TitleLookup
import com.syncit.core.lookup.TrendingCatalog
import com.syncit.core.social.AvatarStore
import com.syncit.core.social.DirectMessages
import com.syncit.core.social.FriendsRepository
import com.syncit.core.social.GroupChats
import com.syncit.core.social.PresenceRepository
import com.syncit.core.social.ProfileRepository
import com.syncit.core.social.PushRegistry
import com.syncit.core.social.WatchInvites
import com.syncit.core.transport.SyncTransport
import com.syncit.core.voice.VoiceAuth
import com.syncit.firebase.social.ChatRoomLinks
import com.syncit.firebase.social.FirebaseAvatarStore
import com.syncit.firebase.social.FirebaseGroupChats
import com.syncit.firebase.social.FirebaseDirectMessages
import com.syncit.firebase.social.FirebaseFriendsRepository
import com.syncit.firebase.social.FirebasePresenceRepository
import com.syncit.firebase.social.FirebaseProfileRepository
import com.syncit.firebase.social.FirebasePushRegistry
import com.syncit.firebase.social.FirebaseWatchInvites
import com.syncit.firebase.social.PushRelay
import com.syncit.firebase.social.RelayTitleLookup
import com.syncit.firebase.social.RelayTrending
import com.syncit.firebase.social.RelayVoiceAuth

/**
 * Everything :data-firebase provides, wired once. :app creates one instance (AppContainer) and
 * hands the pieces out. All of them share one auth session and one server clock.
 *
 * Signing out first marks us offline in presence (that write needs the account's token).
 * Deleting the account erases its data first (FirebaseAccountEraser), then the Auth user.
 */
class FirebaseServices private constructor(context: Context) {
    private val db: FirebaseDatabase = database()
    private val session = FirebaseSession(FirebaseAuth.getInstance())
    private val clock = FirebaseServerClock(db)

    private val presenceImpl = FirebasePresenceRepository(session, db)
    private val pushRelay = PushRelay(session)
    private val avatarsImpl = FirebaseAvatarStore(session)
    private val transportImpl = FirebaseSyncTransport(session, db, clock)
    private val eraser = FirebaseAccountEraser(db, presenceImpl, transportImpl, avatarsImpl)

    val auth: AuthService = FirebaseAuthService(
        context,
        session,
        db,
        eraseAccountData = eraser::erase,
        deletionAbandoned = eraser::abandon,
        beforeSignOut = presenceImpl::stop,
    )
    val profiles: ProfileRepository = FirebaseProfileRepository(session, db)
    val friends: FriendsRepository = FirebaseFriendsRepository(session, db, pushRelay)
    private val roomLinks = ChatRoomLinks(db)
    val dms: DirectMessages = FirebaseDirectMessages(session, db, clock, pushRelay, roomLinks = roomLinks)

    /** Group chats, each with its own room. */
    val groups: GroupChats = FirebaseGroupChats(session, db, pushRelay, roomLinks)
    val push: PushRegistry = FirebasePushRegistry(session, db)
    val avatars: AvatarStore = avatarsImpl
    val presence: PresenceRepository = presenceImpl
    val watchInvites: WatchInvites = FirebaseWatchInvites(session, db, pushRelay)
    val transport: SyncTransport = transportImpl

    /** Netflix title IDs for "Play in Netflix" (relay + Watchmode). */
    val titleLookup: TitleLookup = RelayTitleLookup(session)
    val trending: TrendingCatalog = RelayTrending(session)

    /** Room voice chat's sign-in to the relay. */
    val voiceAuth: VoiceAuth = RelayVoiceAuth(session)

    companion object {
        @Volatile private var instance: FirebaseServices? = null

        /** Returns the process-wide instance (created on first call), so listeners aren't duplicated. */
        fun create(context: Context): FirebaseServices =
            instance ?: synchronized(this) {
                instance ?: FirebaseServices(context.applicationContext).also { instance = it }
            }

        internal fun database(): FirebaseDatabase =
            if (FirebaseConfig.DATABASE_URL.isBlank()) {
                FirebaseDatabase.getInstance()
            } else {
                FirebaseDatabase.getInstance(FirebaseConfig.DATABASE_URL)
            }
    }
}
