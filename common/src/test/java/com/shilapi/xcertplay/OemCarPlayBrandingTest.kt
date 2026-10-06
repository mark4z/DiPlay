package com.shilapi.xcertplay

import android.content.Context
import android.graphics.drawable.BitmapDrawable
import android.os.Handler
import android.view.View
import android.view.ViewGroup
import android.widget.Button
import android.widget.ImageView
import android.widget.LinearLayout
import com.shilapi.xcertplay.airplay.AirPlayConfig
import com.shilapi.xcertplay.airplay.AirPlayIcon
import com.shilapi.xcertplay.airplay.AirPlayIdentity
import com.shilapi.xcertplay.airplay.AirPlayInfoPlist
import com.shilapi.xcertplay.airplay.BplistCodec
import com.shilapi.xcertplay.host.R
import java.awt.image.BufferedImage
import java.io.ByteArrayOutputStream
import java.util.concurrent.ExecutorService
import java.util.concurrent.atomic.AtomicBoolean
import javax.imageio.ImageIO
import org.junit.After
import org.junit.Assert.*
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import org.robolectric.Robolectric
import org.robolectric.RobolectricTestRunner
import org.robolectric.RuntimeEnvironment
import org.robolectric.annotation.Config
import org.robolectric.annotation.GraphicsMode
import org.robolectric.annotation.LooperMode
import org.robolectric.util.ReflectionHelpers

@RunWith(RobolectricTestRunner::class)
@Config(sdk = [29], qualifiers = "en")
@GraphicsMode(GraphicsMode.Mode.NATIVE)
@LooperMode(LooperMode.Mode.PAUSED)
class OemCarPlayBrandingTest {
    private val context get() = RuntimeEnvironment.getApplication()
    private val hosts = mutableListOf<CarPlayHostActivity>()

    @Before fun clearBranding() {
        context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).edit().clear().commit()
        AirPlayPersistence.clearCustomAirPlayIcon(context)
    }

    @After fun cleanUp() {
        hosts.forEach { host ->
            ReflectionHelpers.getField<AtomicBoolean>(host, "shuttingDown").set(true)
            ReflectionHelpers.getField<Handler>(host, "mainHandler").removeCallbacksAndMessages(null)
            ReflectionHelpers.getField<ExecutorService>(host, "teardownExecutor").shutdownNow()
            ReflectionHelpers.getField<ExecutorService>(host, "airPlayCommandExecutor").shutdownNow()
        }
        AirPlayPersistence.clearCustomAirPlayIcon(context)
        CarPlayBackgroundSession.clear()
    }

    @Test fun freshInfoUsesTeslaAndTheOriginalReceiverIdentityWithoutSavingAnOverride() {
        val config = config(host())
        val info = BplistCodec.decode(BplistCodec.encode(AirPlayInfoPlist.build(config))) as Map<*, *>
        assertEquals("Tesla", info["oemIconLabel"])
        assertEquals(true, info["oemIconVisible"])
        val icon = (info["oemIcons"] as List<*>).single() as Map<*, *>
        assertEquals(192, (icon["widthPixels"] as Number).toInt())
        assertEquals(192, (icon["heightPixels"] as Number).toInt())
        assertEquals(true, icon["prerendered"])
        assertArrayEquals(packagedIcon(), icon["imageData"] as ByteArray)
        assertEquals("DiPlay", info["name"])
        assertEquals("DiPlay", info["manufacturer"])
        assertEquals("DiPlay", info["model"])
        assertFalse(context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE).contains("oem_label"))
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
    }

    @Test fun savedLabelAndImageSurviveHostRecreationAndUnrelatedSettingsChanges() {
        val custom = customIcon()
        AirPlayPersistence.saveOemLabel(context, "My car")
        AirPlayPersistence.saveCustomAirPlayIcon(context, custom)
        val first = config(host())
        assertEquals("My car", first.oemLabel)
        assertArrayEquals(custom, first.icons.single().data)

        AirPlayPersistence.saveDisplayScalePercent(context, 85)
        val recreated = config(host())
        assertEquals("My car", recreated.oemLabel)
        assertArrayEquals(custom, recreated.icons.single().data)
        assertArrayEquals(custom, AirPlayPersistence.loadCustomAirPlayIconFile(context)!!.readBytes())
    }

    @Test fun aPreviouslySavedBydLabelRemainsAnExplicitUserOverride() {
        AirPlayPersistence.saveOemLabel(context, "BYD")
        assertEquals("BYD", config(host()).oemLabel)
        assertEquals("BYD", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun blankSavedLabelRetainsTheExistingFallbackWithoutRewritingPreferences() {
        AirPlayPersistence.saveOemLabel(context, "  ")
        assertEquals("Tesla", config(host()).oemLabel)
        assertEquals("  ", context.getSharedPreferences("xcertplay_airplay", Context.MODE_PRIVATE)
            .getString("oem_label", null))
    }

    @Test fun originalDefaultImageButtonRestoresTeslaWithoutChangingTheSavedName() {
        AirPlayPersistence.saveOemLabel(context, "My car")
        AirPlayPersistence.saveCustomAirPlayIcon(context, customIcon())
        val activity = home()
        val controls = carButtonControls(activity)
        controls.filterIsInstance<Button>().single { it.text == activity.getString(R.string.default_icon) }
            .performClick()
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
        assertArrayEquals(packagedIcon(), loadIcon(host()).data)
    }

    @Test fun invalidSavedImageRetainsTheOriginalFallbackBehavior() {
        AirPlayPersistence.saveCustomAirPlayIcon(context, byteArrayOf(1, 2, 3))
        AirPlayPersistence.saveOemLabel(context, "My car")
        assertArrayEquals(packagedIcon(), loadIcon(host()).data)
        assertNull(AirPlayPersistence.loadCustomAirPlayIconFile(context))
        assertEquals("My car", AirPlayPersistence.loadOemLabel(context))
    }

    @Test fun originalHomeSettingsKeepThePickerNameControlAndMatchingDefaultPreview() {
        val activity = home()
        val controls = carButtonControls(activity)
        assertEquals(1, controls.filterIsInstance<Button>().count { it.text == activity.getString(R.string.choose_image) })
        assertEquals(1, controls.filterIsInstance<Button>().count {
            it.text == "${activity.getString(R.string.car_button_name)} · Tesla"
        })
        val bitmap = (controls.filterIsInstance<ImageView>().single().drawable as BitmapDrawable).bitmap
        assertEquals(0xffcc0000.toInt(), bitmap.getPixel(0, 0))
        assertEquals(0xffffffff.toInt(), bitmap.getPixel(bitmap.width / 2, bitmap.height / 2))
    }

    @Test @Config(qualifiers = "en-xxxhdpi")
    fun packagedLogoKeepsItsRawDimensionsAndOpaqueRedAndWhitePixels() {
        val icon = loadIcon(host())
        assertEquals(192, icon.widthPixels)
        assertEquals(192, icon.heightPixels)
        assertArrayEquals(byteArrayOf(-119, 80, 78, 71, 13, 10, 26, 10), icon.data.copyOf(8))
        val image = ImageIO.read(icon.data.inputStream())
        assertEquals(192, image.width)
        assertEquals(192, image.height)
        assertFalse(image.colorModel.hasAlpha())
        assertEquals(0xffcc0000.toInt(), image.getRGB(0, 0))
        assertEquals(0xffffffff.toInt(), image.getRGB(96, 96))
        var whitePixels = 0
        for (y in 0 until image.height) for (x in 0 until image.width) {
            assertEquals(255, image.getRGB(x, y) ushr 24)
            if (image.getRGB(x, y) == 0xffffffff.toInt()) whitePixels++
        }
        assertTrue("Logo must not be empty or fill the background", whitePixels in 3000..12000)
    }

    private fun host() = Robolectric.buildActivity(CarPlayHostActivity::class.java).get().also {
        hosts.add(it)
        ReflectionHelpers.setField(it, "airPlayIdentity", AirPlayIdentity(
            ByteArray(32), ByteArray(32) { index -> index.toByte() }, "synthetic-test-identity",
        ))
        ReflectionHelpers.callInstanceMethod<Unit>(it, "loadPersistedSettings")
    }

    private fun config(host: CarPlayHostActivity): AirPlayConfig {
        val sizeClass = Class.forName("com.shilapi.xcertplay.CarPlayHostActivity\$DisplaySize")
        val size = sizeClass.getDeclaredConstructor(Int::class.javaPrimitiveType, Int::class.javaPrimitiveType)
            .apply { isAccessible = true }.newInstance(1280, 720)
        return CarPlayHostActivity::class.java.getDeclaredMethod("createAirPlayConfig", sizeClass)
            .apply { isAccessible = true }.invoke(host, size) as AirPlayConfig
    }

    private fun loadIcon(host: CarPlayHostActivity): AirPlayIcon =
        ReflectionHelpers.callInstanceMethod(host, "loadAirPlayIcon")

    private fun packagedIcon() = context.resources.openRawResource(R.raw.ic_car_home).use { it.readBytes() }

    private fun customIcon(): ByteArray = ByteArrayOutputStream().use { output ->
        ImageIO.write(BufferedImage(32, 32, BufferedImage.TYPE_INT_RGB), "png", output)
        output.toByteArray()
    }

    private fun home() = Robolectric.buildActivity(DiPlayActivity::class.java).get().apply {
        setTheme(android.R.style.Theme_Material_NoActionBar)
    }

    private fun carButtonControls(activity: DiPlayActivity): List<View> {
        val parent = LinearLayout(activity)
        DiPlayActivity::class.java.getDeclaredMethod("carButtonControls", LinearLayout::class.java)
            .apply { isAccessible = true }.invoke(activity, parent)
        return descendants(parent).toList()
    }

    private fun descendants(view: View): Sequence<View> = sequence {
        yield(view)
        if (view is ViewGroup) for (index in 0 until view.childCount) yieldAll(descendants(view.getChildAt(index)))
    }
}
