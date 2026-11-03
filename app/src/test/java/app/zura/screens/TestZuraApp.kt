package app.zura.screens

import app.zura.AppGraph
import app.zura.ZuraApp
import app.zura.core.crypto.SoftwareKeyWrapper

/** JVM tests have no Android Keystore: install software keys before the app starts. */
class TestZuraApp : ZuraApp() {
    override fun onCreate() {
        AppGraph.keyOverride = SoftwareKeyWrapper()
        super.onCreate()
    }
}
