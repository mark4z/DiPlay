package com.shilapi.xcertplay.hud

import com.shilapi.xcertplay.iap2.state.Iap2RouteChange
import com.shilapi.xcertplay.iap2.state.Iap2RouteManeuver
import com.shilapi.xcertplay.iap2.state.Iap2RouteState

internal data class BydHudGuidance(
    val distanceMeters: Int,
    /** Native HUD arrow (field 28). */
    val maneuver: Int,
    /** Gaode maneuver code: selects the HUD icon (field 8). */
    val gaode: Int = 0,
    val road: String = "",
    val arrivalEpochSeconds: Long? = null,
)

internal typealias BydAppleManeuver = Iap2RouteManeuver
internal typealias BydHudRouteChange = Iap2RouteChange

/** Adapts neutral iAP2 route state to the BYD windshield HUD's arrow and Gaode codes. */
internal class BydHudRouteState(
    private val nanoTime: () -> Long = System::nanoTime,
    staleRouteNs: Long = Iap2RouteState.STALE_ROUTE_NS,
    emptyListHideNs: Long = Iap2RouteState.EMPTY_LIST_HIDE_NS,
    keepAcrossNoRoute: Boolean = false,
) {
    private val route = Iap2RouteState(
        nanoTime = { nanoTime() },
        staleRouteNs = staleRouteNs,
        emptyListHideNs = emptyListHideNs,
        keepAcrossNoRoute = keepAcrossNoRoute,
    )

    fun accept(messageId: Int, payload: ByteArray): BydHudRouteChange = route.accept(messageId, payload)

    fun current(): BydHudGuidance? {
        val maneuver = route.current() ?: return null
        val gaode = BydManeuverCodes.gaode(maneuver.type, maneuver.drivingSide)
        return BydHudGuidance(
            distanceMeters = maneuver.distanceMeters,
            maneuver = BydManeuverCodes.hudArrow(gaode),
            gaode = gaode,
            road = maneuver.road,
            arrivalEpochSeconds = maneuver.arrivalEpochSeconds,
        )
    }

    /** Next maneuver as Apple sent it, for outputs with a richer icon set than the HUD. */
    fun currentApple(): BydAppleManeuver? = route.current()

    fun clear(): Boolean = route.clear()

    companion object {
        const val ROUTE_GUIDANCE_UPDATE = Iap2RouteState.ROUTE_GUIDANCE_UPDATE
        const val ROUTE_GUIDANCE_MANEUVER_UPDATE = Iap2RouteState.ROUTE_GUIDANCE_MANEUVER_UPDATE
    }
}
