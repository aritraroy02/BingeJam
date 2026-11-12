package com.syncit.app.di

import com.syncit.core.auth.AuthService
import com.syncit.core.social.AppReports
import com.syncit.core.social.AvatarStore
import com.syncit.core.social.ActivityTracker
import com.syncit.core.social.DirectMessages
import com.syncit.core.social.FriendInvites
import com.syncit.core.social.FriendsRepository
import com.syncit.core.social.GroupChats
import com.syncit.core.social.PresenceRepository
import com.syncit.core.social.ProfileRepository
import com.syncit.core.social.PushRegistry
import com.syncit.core.social.Safety
import com.syncit.core.social.WatchlistStore
import com.syncit.core.social.WatchInvites

/**
 * The v2 account/social services the UI uses, all :core interfaces. AppContainer picks either the
 * in-memory fakes ([FakeSocial]) or the real :data-firebase / :media implementations.
 */
class SocialServices(
    val auth: AuthService,
    val profiles: ProfileRepository,
    val friends: FriendsRepository,
    val dms: DirectMessages,
    val presence: PresenceRepository,
    val watchInvites: WatchInvites,
    val activityTracker: ActivityTracker,
    val push: PushRegistry,
    val avatars: AvatarStore,
    /** True for [FakeSocial]: "Continue with Google" skips Credential Manager and signs in directly. */
    val isFake: Boolean = false,
    /** Group chats, each with its own room (off in the fakes). */
    val groups: GroupChats = GroupChats.None,
    /** Personal invite links (off in the fakes). */
    val invites: FriendInvites = FriendInvites.None,
    /** Report and block groups (off in the fakes). */
    val safety: Safety = Safety.None,
    /** Crash reports, users and notifications for the owner and developers (off in the fakes). */
    val appReports: AppReports = AppReports.None,
    /** The watchlist in the account, shared with the desktop app (off in the fakes). */
    val watchlist: WatchlistStore = WatchlistStore.None,
)
