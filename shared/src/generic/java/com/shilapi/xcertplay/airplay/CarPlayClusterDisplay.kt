package com.shilapi.xcertplay.airplay

/** Standard protocol content URLs only. Device-specific display calibration is excluded. */
object CarPlayClusterDisplay {
    const val MAP_URL = "maps:/car/instrumentcluster/map"

    enum class Content(val url: String) {
        MAP(MAP_URL),
        TURN_CARD("maps:/car/instrumentcluster/instructioncard"),
        INSTRUMENTS("maps:/car/instrumentcluster"),
        MAP_WITH_CUSTOM_CARD(MAP_URL),
    }
}
