package com.arflix.tv

import androidx.test.core.app.ActivityScenario
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.filters.LargeTest
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
@LargeTest
class MainActivityLifecycleStabilityTest {

    @Test
    fun repeatedLaunchAndCloseDoesNotCrash() {
        repeat(10) {
            ActivityScenario.launch(MainActivity::class.java).use { scenario ->
                scenario.onActivity { /* smoke check */ }
            }
        }
    }
}
