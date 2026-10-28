package com.syncit.app.social

import com.syncit.core.model.ActivityApp
import com.syncit.core.model.JoinRequest
import com.syncit.core.model.JoinStatus
import com.syncit.core.model.RoomInvite
import com.syncit.core.model.StreamingService
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class RoomWordingTest {
    private fun request(app: ActivityApp, title: String?) =
        JoinRequest("host", "riya", "riya", "Riya", app, title, JoinStatus.PENDING, null, 0L)

    private fun invite(app: ActivityApp?, title: String?) = RoomInvite("riya", "riya", "Riya", "ABC123", app, title, 0L)

    @Test fun jamAppsAreMusicAndYouTube() {
        for (app in listOf(ActivityApp.SPOTIFY, ActivityApp.YOUTUBE_MUSIC, ActivityApp.AMAZON_MUSIC, ActivityApp.YOUTUBE)) {
            assertTrue(app.name, RoomWording.isJam(app))
        }
        for (app in listOf(ActivityApp.NETFLIX, ActivityApp.JIOHOTSTAR, ActivityApp.PRIME_VIDEO)) {
            assertFalse(app.name, RoomWording.isJam(app))
        }
        assertFalse(RoomWording.isJam(null as ActivityApp?))
        assertTrue(RoomWording.isJam(StreamingService.SPOTIFY))
        assertFalse(RoomWording.isJam(StreamingService.NETFLIX))
    }

    @Test fun askLabels() {
        assertEquals("Ask to jam", RoomWording.askLabel(jam = true, requested = false))
        assertEquals("Asked to jam", RoomWording.askLabel(jam = true, requested = true))
        assertEquals("Ask to join", RoomWording.askLabel(jam = false, requested = false))
        assertEquals("Asked to join", RoomWording.askLabel(jam = false, requested = true))
    }

    @Test fun joinRequestBanner() {
        assertEquals("@riya wants to jam with you to Blinding Lights", RoomWording.joinRequestText(request(ActivityApp.SPOTIFY, "Blinding Lights")))
        assertEquals("@riya wants to jam with you on YouTube", RoomWording.joinRequestText(request(ActivityApp.YOUTUBE, " ")))
        assertEquals("@riya wants to join you watching Dune", RoomWording.joinRequestText(request(ActivityApp.NETFLIX, "Dune")))
        assertEquals("@riya wants to join you on Prime Video", RoomWording.joinRequestText(request(ActivityApp.PRIME_VIDEO, null)))
    }

    @Test fun inviteBanner() {
        assertEquals("@riya invited you to jam to Song A", RoomWording.inviteText(invite(ActivityApp.YOUTUBE_MUSIC, "Song A")))
        assertEquals("@riya invited you to jam on Spotify", RoomWording.inviteText(invite(ActivityApp.SPOTIFY, null)))
        assertEquals("@riya invited you to watch Dune", RoomWording.inviteText(invite(ActivityApp.NETFLIX, "Dune")))
        assertEquals("@riya invited you to watch together", RoomWording.inviteText(invite(null, null)))
    }

    @Test fun notices() {
        assertEquals("@riya is joining your jam", RoomWording.joiningNotice(request(ActivityApp.AMAZON_MUSIC, null)))
        assertEquals("@riya is joining your watch party", RoomWording.joiningNotice(request(ActivityApp.JIOHOTSTAR, null)))
        assertTrue(RoomWording.shareText("ABC123", jam = true).contains(" jam! Room code: ABC123"))
        assertTrue(RoomWording.shareText("ABC123", jam = false).contains(" watch party! Room code: ABC123"))
    }
}
