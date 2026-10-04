package app.calcpace.health

import android.content.Context
import android.content.Intent
import androidx.core.net.toUri
import androidx.health.connect.client.HealthConnectClient
import androidx.health.connect.client.HealthConnectFeatures
import androidx.health.connect.client.permission.HealthPermission
import androidx.health.connect.client.records.DistanceRecord
import androidx.health.connect.client.records.ElevationGainedRecord
import androidx.health.connect.client.records.ExerciseSessionRecord
import androidx.health.connect.client.records.HeartRateRecord

/** Whether Health Connect is there, what the app asks of it, and its client. */
object HealthConnect {
    /** Health Connect as an app of its own (Android 13 and older); built into the system after. */
    const val PROVIDER = "com.google.android.apps.healthdata"

    /** Without these two there is nothing to import (a run needs a distance). */
    val REQUIRED = setOf(
        HealthPermission.getReadPermission(ExerciseSessionRecord::class),
        HealthPermission.getReadPermission(DistanceRecord::class),
    )

    /** The athlete may untick these on Health Connect's screen; the import goes on without them. */
    val HEART_RATE = HealthPermission.getReadPermission(HeartRateRecord::class)
    val ELEVATION = HealthPermission.getReadPermission(ElevationGainedRecord::class)
    val OPTIONAL = setOf(HEART_RATE, ELEVATION)

    const val BACKGROUND = HealthPermission.PERMISSION_READ_HEALTH_DATA_IN_BACKGROUND

    fun status(context: Context): String = HcStatus.of(
        HealthConnectClient.getSdkStatus(context),
        HealthConnectClient.SDK_AVAILABLE,
        HealthConnectClient.SDK_UNAVAILABLE_PROVIDER_UPDATE_REQUIRED,
    )

    fun client(context: Context): HealthConnectClient? =
        if (status(context) == HcStatus.AVAILABLE) HealthConnectClient.getOrCreate(context) else null

    fun backgroundAvailable(client: HealthConnectClient): Boolean =
        client.features.getFeatureStatus(HealthConnectFeatures.FEATURE_READ_HEALTH_DATA_IN_BACKGROUND) ==
            HealthConnectFeatures.FEATURE_STATUS_AVAILABLE

    fun toRequest(client: HealthConnectClient): Set<String> =
        REQUIRED + OPTIONAL + (if (backgroundAvailable(client)) setOf(BACKGROUND) else emptySet())

    /** Background reads both exist on this phone and are allowed. */
    fun backgroundGranted(client: HealthConnectClient, granted: Set<String>): Boolean =
        BACKGROUND in granted && backgroundAvailable(client)

    /** Health Connect's page on Play, to install or update it (from the official sample). */
    fun installIntent(context: Context): Intent =
        Intent(Intent.ACTION_VIEW).apply {
            setPackage("com.android.vending")
            data = "market://details?id=$PROVIDER&url=healthconnect%3A%2F%2Fonboarding".toUri()
            putExtra("overlay", true)
            putExtra("callerId", context.packageName)
            addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
        }
}
