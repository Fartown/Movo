package io.github.mangi.eta.agent.voice.asr

import android.app.Application
import android.os.Looper
import io.github.mangi.eta.R
import io.github.mangi.eta.data.model.DoubaoSpeechCredentials
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
class EtaAsrSessionFactoryTest {
    @Test
    fun missingCredentialsReportAnErrorEvenWhenSystemRecognitionIsAvailable() {
        ShadowSpeechRecognizer.setIsOnDeviceRecognitionAvailable(true)
        val context = RuntimeEnvironment.getApplication()
        val controller = EtaDictationController {
            EtaAsrSessionFactory.create(context, DoubaoSpeechCredentials())
        }
        val events = mutableListOf<String>()
        controller.start(object : EtaAsrEngine.Listener {
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
            assertTrue(EtaAsrSessionFactory.create(context, credentials) is DoubaoBidirectionalAsrEngine)
        }
    }
}
