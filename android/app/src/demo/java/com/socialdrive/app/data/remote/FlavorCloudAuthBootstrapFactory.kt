package com.socialdrive.app.data.remote

import android.content.Context

object FlavorCloudAuthBootstrapFactory {
    fun create(context: Context): CloudAuthBootstrap = CloudAuthBootstrap { }
}
