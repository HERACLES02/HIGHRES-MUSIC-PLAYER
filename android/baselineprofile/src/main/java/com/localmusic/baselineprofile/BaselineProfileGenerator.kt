package com.localmusic.baselineprofile

import androidx.benchmark.macro.junit4.BaselineProfileRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Direction
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Records the classes and methods used from app start to the Library screen, while scrolling Library,
 * and while opening Now Playing. Needs the profileGen app to have a music folder picked and a song
 * played once, so the Library list and the mini player exist.
 */
@RunWith(AndroidJUnit4::class)
class BaselineProfileGenerator {
    @get:Rule val rule = BaselineProfileRule()

    @Test
    fun startupLibraryAndNowPlaying() = rule.collect(packageName = "com.localmusic.player.profilegen") {
        pressHome()
        startActivityAndWait()

        // The bottom bar shows labels; the landscape rail only has descriptions.
        device.wait(Until.hasObject(By.text("Library")), 20_000)
        (device.findObject(By.text("Library")) ?: device.findObject(By.desc("Library")))
            ?.click() ?: error("Library tab not found. Is the library open?")

        val list = device.wait(Until.findObject(By.res("libraryList")), 10_000)
            ?: error("Library list not found. Pick a music folder in the profilegen app first.")
        list.setGestureMargin(device.displayWidth / 5)
        repeat(3) { list.fling(Direction.DOWN); device.waitForIdle() }
        list.fling(Direction.UP)
        device.waitForIdle()

        device.wait(Until.findObject(By.res("miniPlayer")), 5_000)?.click()
            ?: error("Mini player not found. Play any song once in the profilegen app first.")
        device.wait(Until.hasObject(By.desc("Previous track")), 5_000)
        device.pressBack()
        device.waitForIdle()
    }
}
