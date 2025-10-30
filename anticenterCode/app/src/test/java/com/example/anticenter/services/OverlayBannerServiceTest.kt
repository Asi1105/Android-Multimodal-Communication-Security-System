package com.example.anticenter.services

import android.content.Intent
import android.os.Build
import android.view.View
import androidx.test.core.app.ApplicationProvider
import com.google.common.truth.Truth.assertThat
import java.util.concurrent.TimeUnit
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.annotation.Config
import org.robolectric.annotation.LooperMode
import org.robolectric.shadows.ShadowSettings
import org.robolectric.shadows.ShadowLooper

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [33])
@LooperMode(LooperMode.Mode.PAUSED)
class OverlayBannerServiceTest {

    @Before
    fun grantOverlayPermission() {
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.M) {
            ShadowSettings.setCanDrawOverlays(true)
        }
    }

    @Test
    fun actionShowCreatesOverlayImmediately() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val service = Robolectric.setupService(OverlayBannerService::class.java)

        val showIntent = Intent(context, OverlayBannerService::class.java).apply {
            action = OverlayBannerService.ACTION_SHOW
            putExtra(OverlayBannerService.EXTRA_TITLE, "Fraud Warning")
            putExtra(OverlayBannerService.EXTRA_MESSAGE, "Suspicious transfer detected.")
        }

        service.onStartCommand(showIntent, 0, 0)

        val overlayView = overlayViewOf(service)
        assertThat(overlayView).isNotNull()
        assertThat(overlayView?.parent).isNotNull()

        service.onDestroy()
    }

    @Test
    fun actionHideRemovesOverlay() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val service = Robolectric.setupService(OverlayBannerService::class.java)

        val showIntent = Intent(context, OverlayBannerService::class.java).apply {
            action = OverlayBannerService.ACTION_SHOW
            putExtra(OverlayBannerService.EXTRA_TITLE, "Fraud Warning")
            putExtra(OverlayBannerService.EXTRA_MESSAGE, "Check the details carefully.")
        }
        service.onStartCommand(showIntent, 0, 0)
        assertThat(overlayViewOf(service)).isNotNull()

        val hideIntent = Intent(context, OverlayBannerService::class.java).apply {
            action = OverlayBannerService.ACTION_HIDE
        }
        service.onStartCommand(hideIntent, 0, 0)

        assertThat(overlayViewOf(service)).isNull()

        service.onDestroy()
    }

    @Test
    fun scheduledShowWithDelayPostsOverlayLater() {
        val context = ApplicationProvider.getApplicationContext<android.content.Context>()
        val service = Robolectric.setupService(OverlayBannerService::class.java)

        val delayedIntent = Intent(context, OverlayBannerService::class.java).apply {
            action = OverlayBannerService.ACTION_SHOW
            putExtra(OverlayBannerService.EXTRA_TITLE, "Delayed Warning")
            putExtra(OverlayBannerService.EXTRA_MESSAGE, "This should appear after a delay.")
            putExtra(OverlayBannerService.EXTRA_DELAY_MS, 75L)
        }
        service.onStartCommand(delayedIntent, 0, 0)

        // Before advancing the main looper, overlay should not yet exist
        assertThat(overlayViewOf(service)).isNull()

    ShadowLooper.idleMainLooper(100, TimeUnit.MILLISECONDS)

        assertThat(overlayViewOf(service)).isNotNull()

        service.onDestroy()
    }

    private fun overlayViewOf(service: OverlayBannerService): View? {
        val field = OverlayBannerService::class.java.getDeclaredField("overlayView")
        field.isAccessible = true
        return field.get(service) as? View
    }
}
