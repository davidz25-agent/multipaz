package org.multipaz.mdoc.transport

/**
 * Confidence level associated with a Bluetooth Channel Sounding distance measurement.
 */
enum class BleChannelSoundingConfidence {
    /** Low confidence in the distance estimate. */
    LOW,

    /** Medium confidence in the distance estimate. */
    MEDIUM,

    /** High confidence in the distance estimate. */
    HIGH,
}

/**
 * A single distance measurement produced by a Bluetooth Channel Sounding (CS) ranging session.
 *
 * @property distanceMeters estimated distance to the peer device in meters.
 * @property confidence optional confidence level reported by the ranging stack, or `null` if not
 *   available.
 */
data class BleChannelSoundingResult(
    val distanceMeters: Double,
    val confidence: BleChannelSoundingConfidence? = null,
)
