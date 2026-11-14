package com.personal.serverone

import com.lagradost.cloudstream3.plugins.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

@CloudstreamPlugin
class ServerOnePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(ServerOneProvider())
    }
}
