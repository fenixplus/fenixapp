package com.arvio.benchmark

import androidx.benchmark.macro.CompilationMode
import androidx.benchmark.macro.FrameTimingMetric
import androidx.benchmark.macro.StartupMode
import androidx.benchmark.macro.StartupTimingMetric
import androidx.benchmark.macro.junit4.MacrobenchmarkRule
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import androidx.test.uiautomator.By
import androidx.test.uiautomator.Until
import org.junit.Rule
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class StartupBenchmarks {

    @get:Rule
    val benchmarkRule = MacrobenchmarkRule()

    @Test
    fun coldStartup() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(StartupTimingMetric(), FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.COLD,
            iterations = 5,
            setupBlock = {
                pressHome()
            }
        ) {
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 8_000)
            if (device.wait(Until.hasObject(By.textContains("watching")), 1_200)) {
                device.pressDPadCenter()
                device.waitForIdle(1_200)
            }
        }
    }

    @Test
    fun homeNavigationJank() {
        benchmarkRule.measureRepeated(
            packageName = TARGET_PACKAGE,
            metrics = listOf(FrameTimingMetric()),
            compilationMode = CompilationMode.Partial(),
            startupMode = StartupMode.WARM,
            iterations = 5,
            setupBlock = {
                pressHome()
            }
        ) {
            startActivityAndWait()
            device.wait(Until.hasObject(By.pkg(TARGET_PACKAGE).depth(0)), 8_000)

            if (device.wait(Until.hasObject(By.textContains("watching")), 1_200)) {
                device.pressDPadCenter()
                device.waitForIdle(1_000)
            }

            device.waitForIdle(1_000)
            repeat(5) {
                device.pressDPadRight()
                device.waitForIdle(120)
            }
            repeat(3) {
                device.pressDPadDown()
                device.waitForIdle(180)
            }
            repeat(3) {
                device.pressDPadUp()
                device.waitForIdle(180)
            }
        }
    }

    companion object {
        private const val TARGET_PACKAGE = "com.fenix.plus"
    }
}
