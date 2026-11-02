package com.syncit.core.lookup

import com.syncit.core.model.StreamingService
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrendingLinksTest {
    private fun ok(service: StreamingService, url: String?) = TrendingCatalog.isSafeLink(service, url)

    @Test fun acceptsEachServicesOwnFormat() {
        assertTrue(ok(StreamingService.NETFLIX, "https://www.netflix.com/watch/81234567"))
        assertTrue(ok(StreamingService.JIOHOTSTAR, "https://www.hotstar.com/in/movies/x/1271713346/watch"))
        assertTrue(ok(StreamingService.PRIME_VIDEO, "https://www.primevideo.com/detail/0ABCDEF123/"))
        assertTrue(ok(StreamingService.PRIME_VIDEO, "https://app.primevideo.com/detail/0X1Y2Z"))
        assertTrue(ok(StreamingService.PRIME_VIDEO, "https://app.primevideo.com/detail?gti=amzn1.dv.gti.32bf7a96-37d1-484f-abec-207dfcf09b99"))
        assertFalse(ok(StreamingService.PRIME_VIDEO, "https://app.primevideo.com/detail?gti=amzn1.dv.gti.32bf7a96-37d1-484f-abec-207dfcf09b99&x=evil"))
    }

    @Test fun rejectsOtherHostsServicesAndShapes() {
        assertFalse(ok(StreamingService.NETFLIX, null))
        assertFalse(ok(StreamingService.NETFLIX, "https://www.netflix.com/title/81234567"))
        assertFalse(ok(StreamingService.NETFLIX, "https://www.hotstar.com/in/movies/x/1271713346/watch"))
        assertFalse(ok(StreamingService.JIOHOTSTAR, "https://evil.com/in/movies/x/1271713346/watch"))
        assertFalse(ok(StreamingService.PRIME_VIDEO, "https://www.primevideo.com.evil.io/detail/0ABC/"))
        assertFalse(ok(StreamingService.SPOTIFY, "https://www.netflix.com/watch/1"))
    }
}
