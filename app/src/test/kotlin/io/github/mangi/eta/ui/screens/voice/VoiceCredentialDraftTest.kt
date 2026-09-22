package io.github.mangi.eta.ui.screens.voice

import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
import org.junit.Assert.*
import org.junit.Test

class VoiceCredentialDraftTest {
    @Test fun selectingAppAccessCannotKeepAnApiKeyThatWouldOverrideTheSelection() {
        val draft = voiceCredentialDraft(false, "old-api", " app ", " access ", "")
        assertEquals(DoubaoSpeechCredentials.AuthMode.AppAccessKey, draft.authMode())
        assertEquals("", draft.apiKey)
        assertEquals("app", draft.appKey)
        assertEquals("access", draft.accessKey)
        assertEquals(DoubaoSpeechCredentials.DEFAULT_RESOURCE_ID, draft.resourceId)
    }

    @Test fun selectingApiKeyClearsUnusedLegacyAuthentication() {
        val draft = voiceCredentialDraft(true, " api ", "old-app", "old-access", "custom-resource")
        assertEquals(DoubaoSpeechCredentials.AuthMode.ApiKey, draft.authMode())
        assertEquals("", draft.appKey)
        assertEquals("", draft.accessKey)
        assertEquals("custom-resource", draft.resourceId)
    }

    @Test fun blankSelectedCredentialDoesNotFallBackToAnotherMode() {
        val draft = voiceCredentialDraft(true, "", "old-app", "old-access", "")
        assertFalse(draft.hasUsableAuth())
    }
}
