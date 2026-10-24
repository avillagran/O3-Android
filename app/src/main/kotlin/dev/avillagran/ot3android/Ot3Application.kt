package dev.avillagran.ot3android

import android.app.Application

class Ot3Application : Application() {
    lateinit var graph: AppGraph
        private set

    override fun onCreate() {
        super.onCreate()
        graph = AppGraph(this)
    }
}
