package io.github.mangi.eta.ui.screens.voice

import io.github.mangi.eta.data.model.DoubaoSpeechCredentials

/** Explicit selection prevents an old Api-Key from silently overriding App-Key credentials. */
internal fun voiceCredentialDraft(
    useApiKey: Boolean,
    apiKey: String,
    appKey: String,
    accessKey: String,
    resourceId: String,
    endpoint: String = DoubaoSpeechCredentials.DEFAULT_ENDPOINT,
): DoubaoSpeechCredentials = DoubaoSpeechCredentials(
    apiKey = if (useApiKey) apiKey else "",
    appKey = if (useApiKey) "" else appKey,
    accessKey = if (useApiKey) "" else accessKey,
    resourceId = resourceId,
    endpoint = endpoint,
).normalized()
