package app.cupnlog.data

import android.content.Context
import android.os.Build
import com.google.android.gms.nearby.Nearby
import com.google.android.gms.nearby.connection.AdvertisingOptions
import com.google.android.gms.nearby.connection.ConnectionInfo
import com.google.android.gms.nearby.connection.ConnectionLifecycleCallback
import com.google.android.gms.nearby.connection.ConnectionResolution
import com.google.android.gms.nearby.connection.ConnectionsStatusCodes
import com.google.android.gms.nearby.connection.DiscoveredEndpointInfo
import com.google.android.gms.nearby.connection.DiscoveryOptions
import com.google.android.gms.nearby.connection.EndpointDiscoveryCallback
import com.google.android.gms.nearby.connection.Payload
import com.google.android.gms.nearby.connection.PayloadCallback
import com.google.android.gms.nearby.connection.PayloadTransferUpdate
import com.google.android.gms.nearby.connection.Strategy
import java.util.concurrent.ConcurrentHashMap

/**
 * One-shot local wireless exchange for phase 4, wrapping Nearby Connections.
 *
 * Replaces the two-QR collect-then-broadcast dance with a single exchange at
 * session end. **Continuous/live sync is explicitly out of scope**: a guest's
 * ratings stay hidden until the session's end. The payload is the existing
 * [ShareCodec] output (a few hundred bytes to a couple KB of JSON), well under
 * Nearby's 32 KB BYTES limit, so there is no chunking.
 *
 * Two symmetric roles share one connection lifecycle:
 *  - Host: [startHosting] advertises; each guest connects and [acceptConnection]
 *    runs automatically; the host [broadcast]s the merged results back over the
 *    still-open connections.
 *  - Guest: [startDiscovery] finds the host, auto-requests one connection, and
 *    [send]s its ratings; it then stays connected to receive the broadcast.
 *
 * The QR path stays the always-available fallback (camera-less / permission-free
 * / non-GMS devices); this class is just the primary transport for the same
 * confirmed-preview flow. Nothing here auto-applies anything — received payloads
 * are handed to the view model, which routes them through the same confirm
 * preview as an incoming QR.
 */
class NearbyExchange(context: Context) {

    /** Receives connection + payload events. All callbacks may arrive on a binder thread. */
    interface Listener {
        /** A peer is connected (host: a guest joined; guest: connected to the host). */
        fun onEndpointConnected(endpointId: String, name: String) {}
        fun onEndpointDisconnected(endpointId: String) {}
        /** A BYTES payload arrived from a connected peer. */
        fun onPayload(endpointId: String, bytes: ByteArray) {}
        /** Advertising / discovery / connection failed; show the QR fallback. */
        fun onFailure(message: String) {}
    }

    private enum class Role { NONE, HOST, GUEST }

    private val appContext = context.applicationContext
    private val client = Nearby.getConnectionsClient(appContext)

    private var role = Role.NONE
    private var listener: Listener? = null
    private var advertising = false
    private var discovering = false
    private var connecting = false

    /** endpointId → advertised display name, remembered from connection init. */
    private val endpointNames = ConcurrentHashMap<String, String>()

    /** Currently connected endpoints (both roles). */
    private val endpoints = ConcurrentHashMap<String, String>()

    // ------------------------------------------------------------------
    // Host
    // ------------------------------------------------------------------

    /** Advertises this device as a host so guests can connect and send ratings. */
    fun startHosting(displayName: String, listener: Listener) {
        stop()
        this.listener = listener
        role = Role.HOST
        advertising = true
        val name = displayName.ifBlank { "CofCup" }.take(HOST_NAME_MAX)
        client.startAdvertising(
            name,
            SERVICE_ID,
            connectionLifecycleCallback,
            AdvertisingOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnFailureListener { e ->
            advertising = false
            listener.onFailure(e.message ?: "Couldn't start sharing.")
        }
    }

    /** Sends a payload to every connected guest (the merged results). */
    fun broadcast(bytes: ByteArray) {
        endpoints.keys.forEach { endpointId -> send(endpointId, bytes) }
    }

    // ------------------------------------------------------------------
    // Guest
    // ------------------------------------------------------------------

    /**
     * Searches for a host and connects to the first one found. On
     * `onEndpointConnected`, call [send] with the ratings payload.
     */
    fun startDiscovery(listener: Listener) {
        stop()
        this.listener = listener
        role = Role.GUEST
        discovering = true
        client.startDiscovery(
            SERVICE_ID,
            endpointDiscoveryCallback,
            DiscoveryOptions.Builder().setStrategy(Strategy.P2P_POINT_TO_POINT).build(),
        ).addOnFailureListener { e ->
            discovering = false
            listener.onFailure(e.message ?: "Couldn't look for the host.")
        }
    }

    /** Sends a BYTES payload to a connected endpoint. No-op over the size cap. */
    fun send(endpointId: String, bytes: ByteArray) {
        if (bytes.size > MAX_BYTES) {
            listener?.onFailure("That payload is too large to send.")
            return
        }
        client.sendPayload(endpointId, Payload.fromBytes(bytes))
    }

    // ------------------------------------------------------------------
    // Shared lifecycle
    // ------------------------------------------------------------------

    private val connectionLifecycleCallback = object : ConnectionLifecycleCallback() {
        /**
         * Both sides accept here: the advertiser completes the incoming
         * request, and the initiator registers its [PayloadCallback] this way
         * (there is no payload-callback parameter on `requestConnection`).
         * Skipping it on the guest would leave the host's merged-results
         * broadcast with nowhere to arrive.
         */
        override fun onConnectionInitiated(endpointId: String, connectionInfo: ConnectionInfo) {
            endpointNames[endpointId] = connectionInfo.endpointName
            client.acceptConnection(endpointId, payloadCallback)
        }

        override fun onConnectionResult(endpointId: String, result: ConnectionResolution) {
            if (result.status.statusCode == ConnectionsStatusCodes.STATUS_OK) {
                val name = endpointNames[endpointId].orEmpty()
                endpoints[endpointId] = name
                connecting = false
                listener?.onEndpointConnected(endpointId, name)
            } else {
                connecting = false
                listener?.onFailure("Couldn't connect.")
            }
        }

        override fun onDisconnected(endpointId: String) {
            endpoints.remove(endpointId)
            listener?.onEndpointDisconnected(endpointId)
        }
    }

    private val endpointDiscoveryCallback = object : EndpointDiscoveryCallback() {
        override fun onEndpointFound(endpointId: String, info: DiscoveredEndpointInfo) {
            // One-shot: connect to the first host found, then stop discovering.
            if (role == Role.GUEST && !connecting && endpoints.isEmpty()) {
                connecting = true
                client.requestConnection(
                    info.endpointName,
                    endpointId,
                    connectionLifecycleCallback,
                ).addOnFailureListener { e ->
                    connecting = false
                    listener?.onFailure(e.message ?: "Couldn't reach the host.")
                }
            }
        }

        override fun onEndpointLost(endpointId: String) {}
    }

    private val payloadCallback = object : PayloadCallback() {
        override fun onPayloadReceived(endpointId: String, payload: Payload) {
            if (payload.type == Payload.Type.BYTES) {
                payload.asBytes()?.let { bytes -> listener?.onPayload(endpointId, bytes) }
            }
        }

        override fun onPayloadTransferUpdate(endpointId: String, update: PayloadTransferUpdate) {}
    }

    /** Tears everything down (leaving the screen, or before switching role). */
    fun stop() {
        listener = null
        if (advertising) {
            client.stopAdvertising()
            advertising = false
        }
        if (discovering) {
            client.stopDiscovery()
            discovering = false
        }
        if (endpoints.isNotEmpty()) {
            client.stopAllEndpoints()
            endpoints.clear()
        }
        endpointNames.clear()
        connecting = false
        role = Role.NONE
    }

    companion object {
        /**
         * Nearby's BYTES payload cap (32 KB). Our payloads are far smaller; the
         * guard just refuses anything pathological.
         */
        const val MAX_BYTES = 32 * 1024

        /** Identifies CofCup peers on the same Nearby service. */
        private const val SERVICE_ID = "app.cupnlog.CUPPING"

        private const val HOST_NAME_MAX = 24
    }
}

/**
 * The dangerous runtime permissions Nearby Connections needs on this device,
 * for a `RequestMultiplePermissions` launcher. Normal (auto-granted)
 * permissions like BLUETOOTH/ACCESS_WIFI_STATE are declared in the manifest
 * but not requested here. The QR fallback works regardless of what's granted.
 */
fun nearbyRuntimePermissions(): List<String> {
    val sdk = Build.VERSION.SDK_INT
    val perms = mutableListOf<String>()
    // Android 12+ replaced the pre-12 Bluetooth permissions.
    if (sdk >= Build.VERSION_CODES.S) {
        perms += "android.permission.BLUETOOTH_SCAN"
        perms += "android.permission.BLUETOOTH_CONNECT"
        perms += "android.permission.BLUETOOTH_ADVERTISE"
    }
    // Discovery needs location on Android 6–12.
    if (sdk <= Build.VERSION_CODES.S_V2) perms += "android.permission.ACCESS_COARSE_LOCATION"
    if (sdk in Build.VERSION_CODES.Q..Build.VERSION_CODES.S_V2) {
        perms += "android.permission.ACCESS_FINE_LOCATION"
    }
    // Android 13+ Nearby Wi-Fi devices.
    if (sdk >= Build.VERSION_CODES.TIRAMISU) perms += "android.permission.NEARBY_WIFI_DEVICES"
    // Android 17 (SDK 37) local-network access.
    if (sdk >= 37) perms += "android.permission.ACCESS_LOCAL_NETWORK"
    return perms
}