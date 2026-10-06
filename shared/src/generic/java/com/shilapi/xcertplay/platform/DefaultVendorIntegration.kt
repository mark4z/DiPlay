package com.shilapi.xcertplay.platform

internal object DefaultVendorIntegration {
    fun create(): CarPlayVendorIntegration = NoVendorIntegration
}
