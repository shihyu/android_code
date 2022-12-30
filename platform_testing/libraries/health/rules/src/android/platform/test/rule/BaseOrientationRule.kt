/*
 * Copyright (C) 2021 The Android Open Source Project
 *
 * Licensed under the Apache License, Version 2.0 (the "License");
 * you may not use this file except in compliance with the License.
 * You may obtain a copy of the License at
 *
 *      http://www.apache.org/licenses/LICENSE-2.0
 *
 * Unless required by applicable law or agreed to in writing, software
 * distributed under the License is distributed on an "AS IS" BASIS,
 * WITHOUT WARRANTIES OR CONDITIONS OF ANY KIND, either express or implied.
 * See the License for the specific language governing permissions and
 * limitations under the License.
 */
package android.platform.test.rule

import android.graphics.Rect
import android.platform.test.rule.Orientation.LANDSCAPE
import android.platform.test.rule.Orientation.PORTRAIT
import android.platform.test.rule.RotationUtils.clearOrientationOverride
import android.platform.test.rule.RotationUtils.setOrientationOverride
import android.platform.test.util.HealthTestingUtils.waitForNullDiag
import androidx.test.InstrumentationRegistry
import androidx.test.uiautomator.By
import androidx.test.uiautomator.UiDevice
import com.android.launcher3.tapl.LauncherInstrumentation
import org.junit.runner.Description

/** Locks the orientation in Landscape before starting the test, and goes back to natural after. */
internal class LandscapeOrientationRule : BaseOrientationRule(LANDSCAPE)

/** Locks the orientation in Portrait before starting the test, and goes back to natural after. */
internal class PortraitOrientationRule : BaseOrientationRule(PORTRAIT)

/**
 * Possible device orientations.
 *
 * See [Rect.orientation] for their definitions.
 */
enum class Orientation {
    LANDSCAPE,
    PORTRAIT,
}

private val Rect.orientation: Orientation
    get() =
        if (width() > height()) {
            LANDSCAPE
        } else {
            PORTRAIT
        }

/** Uses launcher rect to decide which rotation to apply to match [expectedOrientation]. */
sealed class BaseOrientationRule private constructor(private val expectedOrientation: Orientation) :
    TestWatcher() {

    override fun starting(description: Description) {
        setOrientationOverride(expectedOrientation)
    }

    override fun finished(description: Description) {
        clearOrientationOverride()
    }
}

/** Provides a way to set and clear a rotation override. */
object RotationUtils {
    private val device: UiDevice =
        UiDevice.getInstance(InstrumentationRegistry.getInstrumentation())

    private val launcher: LauncherInstrumentation
        get() = LauncherInstrumentation()

    /**
     * Sets device orientation to [expectedOrientation], according to [Rect.orientation] definition.
     *
     * Important: call [clearOrientationOverride] after the end of the test. If a single orientation
     * is needed for the entire test, please use the TestRule [OrientationRule] that automatically
     * takes care of cleaning the override. Those should be called only when the test needs to
     * change orientation in the middle.
     */
    fun setOrientationOverride(expectedOrientation: Orientation) {
        device.pressHome()
        launcher.setEnableRotation(true)
        if (launcherVisibleBounds?.orientation == expectedOrientation) {
            return
        }
        changeOrientation()
        waitForNullDiag {
            when (launcherVisibleBounds?.orientation) {
                expectedOrientation -> null // No error == success.
                null -> "Launcher is not found"
                else -> "Visible orientation is not ${expectedOrientation.name}"
            }
        }
    }

    private fun changeOrientation() {
        if (device.isNaturalOrientation) {
            device.setOrientationLeft()
        } else {
            device.setOrientationNatural()
        }
    }

    fun clearOrientationOverride() {
        device.setOrientationNatural()
        launcher.setEnableRotation(false)
        device.unfreezeRotation()
    }

    private val launcherVisibleBounds: Rect?
        get() {
            val launcher =
                device.findObject(By.res("android", "content").pkg(device.launcherPackageName))
            return launcher?.visibleBounds
        }
}
