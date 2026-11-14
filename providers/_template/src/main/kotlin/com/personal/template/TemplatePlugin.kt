package com.personal.template

import com.lagradost.cloudstream3.BasePlugin
import com.lagradost.cloudstream3.plugins.CloudstreamPlugin

/**
 * TEMPLATE PLUGIN — rename alongside TemplateProvider when copying this directory.
 *
 * The @CloudstreamPlugin annotation is what tools/build.py looks for to determine the
 * value of `pluginClassName` in the .cs3 manifest, so it must stay on exactly one class.
 */
@CloudstreamPlugin
class TemplatePlugin : BasePlugin() {
    override fun load() {
        registerMainAPI(TemplateProvider())
    }
}
