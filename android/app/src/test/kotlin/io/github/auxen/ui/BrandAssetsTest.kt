package io.github.auxen.ui

import androidx.test.core.app.ApplicationProvider
import io.github.auxen.R
import org.junit.Assert.assertNotNull
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.RobolectricTestRunner

/** Brand drawables must inflate — catches malformed pathData or transform XML. */
@RunWith(RobolectricTestRunner::class)
class BrandAssetsTest {
    private val context = ApplicationProvider.getApplicationContext<android.content.Context>()

    @Test fun brandLogoDrawablesInflate() {
        assertNotNull(context.getDrawable(R.drawable.auxen_logo))
        assertNotNull(context.getDrawable(R.drawable.auxen_logo_on_light))
        assertNotNull(context.getDrawable(R.drawable.ic_launcher_foreground))
        assertNotNull(context.getDrawable(R.drawable.ic_launcher_monochrome))
        assertNotNull(context.getDrawable(R.drawable.splash_logo))
        assertNotNull(context.getDrawable(R.drawable.splash_logo_on_light))
    }

    @Test fun notificationIconInflates() {
        assertNotNull(context.getDrawable(R.drawable.ic_stat_auxen))
    }
}
