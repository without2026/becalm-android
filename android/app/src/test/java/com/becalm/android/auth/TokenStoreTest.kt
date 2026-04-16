package com.becalm.android.auth

import android.content.SharedPreferences
import io.mockk.every
import io.mockk.mockk
import io.mockk.verify
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test

// spec: AUTH-001 — tokens stored in EncryptedSharedPreferences
// spec: AUTH-004 — update tokens on refresh
// spec: AUTH-005 — clear tokens on logout

class TokenStoreTest {

    private lateinit var prefs: SharedPreferences
    private lateinit var editor: SharedPreferences.Editor
    private lateinit var tokenStore: TokenStore

    @Before
    fun setUp() {
        editor = mockk(relaxed = true)
        every { editor.putString(any(), any()) } returns editor
        every { editor.remove(any()) } returns editor
        every { editor.apply() } returns Unit

        prefs = mockk {
            every { edit() } returns editor
        }
        tokenStore = TokenStore(prefs)
    }

    // spec: AUTH-001
    @Test
    fun `saveTokens stores access and refresh token`() {
        tokenStore.saveTokens("access123", "refresh456", "user789")
        verify { editor.putString("access_token", "access123") }
        verify { editor.putString("refresh_token", "refresh456") }
        verify { editor.putString("user_id", "user789") }
        verify { editor.apply() }
    }

    // spec: AUTH-001
    @Test
    fun `getBearerHeader returns Bearer prefixed access token`() {
        every { prefs.getString("access_token", null) } returns "mytoken"
        assertEquals("Bearer mytoken", tokenStore.getBearerHeader())
    }

    // spec: AUTH-001
    @Test
    fun `getBearerHeader returns null when no token`() {
        every { prefs.getString("access_token", null) } returns null
        assertNull(tokenStore.getBearerHeader())
    }

    // spec: AUTH-004
    @Test
    fun `updateTokens replaces access and refresh tokens`() {
        tokenStore.updateTokens("newAccess", "newRefresh")
        verify { editor.putString("access_token", "newAccess") }
        verify { editor.putString("refresh_token", "newRefresh") }
        verify { editor.apply() }
    }

    // spec: AUTH-005
    @Test
    fun `clearTokens removes all token keys`() {
        tokenStore.clearTokens()
        verify { editor.remove("access_token") }
        verify { editor.remove("refresh_token") }
        verify { editor.remove("user_id") }
        verify { editor.apply() }
    }

    // spec: AUTH-001
    @Test
    fun `isLoggedIn returns true when access token exists`() {
        every { prefs.getString("access_token", null) } returns "sometoken"
        assertTrue(tokenStore.isLoggedIn())
    }

    // spec: AUTH-005
    @Test
    fun `isLoggedIn returns false after clearTokens`() {
        every { prefs.getString("access_token", null) } returns null
        assertFalse(tokenStore.isLoggedIn())
    }
}
