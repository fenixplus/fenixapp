package com.lagradost.cloudstream3

import android.app.Activity
import android.content.Context
import java.lang.ref.WeakReference

object AcraApplication {
    var context: Context? = null

    private var currentActivity: WeakReference<Activity>? = null

    fun setActivity(activity: Activity?) {
        currentActivity = if (activity != null) WeakReference(activity) else null
    }

    fun getActivity(): Activity? {
        return currentActivity?.get()
    }
}
