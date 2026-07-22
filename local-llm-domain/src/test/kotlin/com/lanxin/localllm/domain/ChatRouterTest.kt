package com.lanxin.localllm.domain

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class ChatRouterTest {

    @Test
    fun preferLocal_usesLocalWhenReady() {
        val d = ChatRouter(ChatRoutePolicy.PREFER_LOCAL).decide(
            localUsable = true,
            cloudConfigured = true
        )
        assertEquals(ChatBackend.LOCAL, d.backend)
    }

    @Test
    fun preferLocal_fallsToCloud() {
        val d = ChatRouter(ChatRoutePolicy.PREFER_LOCAL).decide(
            localUsable = false,
            cloudConfigured = true
        )
        assertEquals(ChatBackend.CLOUD, d.backend)
    }

    @Test
    fun preferLocal_noBackend() {
        val d = ChatRouter(ChatRoutePolicy.PREFER_LOCAL).decide(
            localUsable = false,
            cloudConfigured = false
        )
        assertEquals(ChatBackend.NONE, d.backend)
    }

    @Test
    fun preferCloud_usesCloudThenLocalFallbackDecision() {
        val router = ChatRouter(ChatRoutePolicy.PREFER_CLOUD)
        val primary = router.decide(localUsable = true, cloudConfigured = true)
        assertEquals(ChatBackend.CLOUD, primary.backend)
        val fb = router.fallback(ChatBackend.CLOUD, localUsable = true, cloudConfigured = true)
        assertEquals(ChatBackend.LOCAL, fb!!.backend)
    }

    @Test
    fun localOnly_neverCloud() {
        val d = ChatRouter(ChatRoutePolicy.LOCAL_ONLY).decide(
            localUsable = false,
            cloudConfigured = true
        )
        assertEquals(ChatBackend.NONE, d.backend)
        assertNull(
            ChatRouter(ChatRoutePolicy.LOCAL_ONLY).fallback(
                ChatBackend.LOCAL,
                localUsable = false,
                cloudConfigured = true
            )
        )
    }

    @Test
    fun cloudOnly_requiresConfig() {
        val d = ChatRouter(ChatRoutePolicy.CLOUD_ONLY).decide(
            localUsable = true,
            cloudConfigured = false
        )
        assertEquals(ChatBackend.NONE, d.backend)
    }
}
