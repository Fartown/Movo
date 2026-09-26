package io.github.fartown.movo.agent.voice.asr

import android.app.Application
import android.os.Looper
import io.github.fartown.movo.R
import io.github.fartown.movo.data.model.DoubaoSpeechCredentials
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.Shadows.shadowOf
import org.robolectric.annotation.Config
import org.robolectric.shadows.ShadowSpeechRecognizer

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [36], application = Application::class)
class MovoAsrSessionFactoryTest {
    @Test
    fun missingCredentialsReportAnErrorEvenWhenSystemRecognitionIsAvailable() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val context = RuntimeEnvironment.getApplication()
        val controller = MovoDictationController {
            MovoAsrSessionFactory.create(context, DoubaoSpeechCredentials())
        }
        val events = mutableListOf<String>()
        controller.start(object : MovoAsrEngine.Listener {
            override fun onPartial(text: String) { events += "partial" }
            override fun onFinal(text: String) { events += "final" }
            override fun onError(message: String) { events += message }
            override fun onEnded() { events += "ended" }
        })
        shadowOf(Looper.getMainLooper()).idle()

        assertEquals(listOf(context.getString(R.string.voice_doubao_credentials_required), "ended"), events)
        assertFalse(controller.isRunning())
        assertNull(ShadowSpeechRecognizer.getLatestSpeechRecognizer())
    }

    @Test
    fun bothCredentialModesUseDoubao() {
        val context = RuntimeEnvironment.getApplication()
        for (credentials in listOf(
            DoubaoSpeechCredentials(apiKey = "test-key"),
            DoubaoSpeechCredentials(appKey = "test-app", accessKey = "test-access"),
        )) {
            assertTrue(MovoAsrSessionFactory.create(context, credentials) is DoubaoBidirectionalAsrEngine)
        }
    }
}
