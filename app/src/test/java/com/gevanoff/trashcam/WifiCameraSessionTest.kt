package com.gevanoff.trashcam

import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.ViewModelStore
import org.junit.Assert.*
import org.junit.Test

class WifiCameraSessionTest {
    // Activity recreation gives the replacement owner the retained ViewModelStore.
    private fun session(store: ViewModelStore): WifiCameraSession =
        ViewModelProvider(store, ViewModelProvider.NewInstanceFactory())[WifiCameraSession::class.java]

    @Test fun `failure remains blocked when recreated activity resolves its session`() {
        val retainedStore = ViewModelStore()
        val original = session(retainedStore)
        original.attemptPolicy.failed()
        val recreated = session(retainedStore)
        assertSame(original, recreated)
        assertFalse(recreated.attemptPolicy.canAttempt(automatic = true, autoConnect = true))
        recreated.attemptPolicy.retry()
        assertTrue(recreated.attemptPolicy.canAttempt(automatic = true, autoConnect = true))
        retainedStore.clear()
    }

    @Test fun `finished activity does not suppress automatic connection in a new session`() {
        val oldStore = ViewModelStore()
        val previous = session(oldStore)
        previous.attemptPolicy.failed()
        oldStore.clear()
        val newStore = ViewModelStore()
        val fresh = session(newStore)
        assertNotSame(previous, fresh)
        assertTrue(fresh.attemptPolicy.canAttempt(automatic = true, autoConnect = true))
        newStore.clear()
    }
}
