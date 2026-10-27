package dev.avillagran.ot3android.ui.home

import android.app.Application
import androidx.test.ext.junit.runners.AndroidJUnit4
import org.junit.runner.RunWith
import org.robolectric.annotation.Config

@RunWith(AndroidJUnit4::class)
@Config(application = Application::class, qualifiers = "w411dp-h891dp-port-xhdpi")
class ThreadActionsComposeTest : ThreadActionsComposeContract()
