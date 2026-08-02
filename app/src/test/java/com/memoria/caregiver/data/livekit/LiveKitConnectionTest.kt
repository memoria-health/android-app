package com.memoria.caregiver.data.livekit

import org.junit.Assert.assertEquals
import org.junit.Test

class CameraConnectivityTest {

    private val identity = "cam_1a2b3c4d"

    @Test
    fun `a row with no identity is unpaired`() {
        assertEquals(
            CameraConnectivity.UNPAIRED,
            cameraConnectivity(null, LiveKitConnectionState.Connected, hasVideoTrack = true),
        )
    }

    @Test
    fun `unpaired beats every other signal`() {
        // A placeholder row holds a pairing code and nothing else. It can never
        // be live no matter what the room says.
        assertEquals(
            CameraConnectivity.UNPAIRED,
            cameraConnectivity(null, LiveKitConnectionState.Disconnected, hasVideoTrack = false),
        )
    }

    @Test
    fun `a paired camera reads room-down whenever the room is not connected`() {
        val notConnected = listOf(
            LiveKitConnectionState.Disconnected,
            LiveKitConnectionState.Connecting,
            LiveKitConnectionState.Reconnecting,
            LiveKitConnectionState.Failed("boom"),
        )
        notConnected.forEach { state ->
            assertEquals(
                "state=$state",
                CameraConnectivity.ROOM_DOWN,
                cameraConnectivity(identity, state, hasVideoTrack = true),
            )
        }
    }

    @Test
    fun `a subscribed video track is what makes a camera live`() {
        assertEquals(
            CameraConnectivity.LIVE,
            cameraConnectivity(identity, LiveKitConnectionState.Connected, hasVideoTrack = true),
        )
    }

    @Test
    fun `a connected room with no track for this camera reads offline`() {
        assertEquals(
            CameraConnectivity.OFFLINE,
            cameraConnectivity(identity, LiveKitConnectionState.Connected, hasVideoTrack = false),
        )
    }

    @Test
    fun `joining is declared but never returned`() {
        // PARITY.md §11: port the enum as-is, do not invent a joining branch.
        val everyOutcome = listOf<CameraConnectivity>(
            cameraConnectivity(null, LiveKitConnectionState.Connected, true),
            cameraConnectivity(identity, LiveKitConnectionState.Disconnected, true),
            cameraConnectivity(identity, LiveKitConnectionState.Connected, true),
            cameraConnectivity(identity, LiveKitConnectionState.Connected, false),
        )
        assertEquals(emptyList<CameraConnectivity>(), everyOutcome.filter { it == CameraConnectivity.JOINING })
    }
}

class ConnectionStateReducerTest {

    @Test
    fun `each signal maps to its state`() {
        val from = LiveKitConnectionState.Disconnected
        assertEquals(LiveKitConnectionState.Connecting, nextConnectionState(from, ConnectionSignal.CONNECTING))
        assertEquals(LiveKitConnectionState.Connected, nextConnectionState(from, ConnectionSignal.CONNECTED))
        assertEquals(LiveKitConnectionState.Reconnecting, nextConnectionState(from, ConnectionSignal.RECONNECTING))
        assertEquals(LiveKitConnectionState.Disconnected, nextConnectionState(from, ConnectionSignal.DISCONNECTED))
    }

    @Test
    fun `a disconnect does not clobber a terminal failure`() {
        // Every failed connect is followed by a disconnect transition. Without
        // this rule the reason the caregiver needs to read is replaced by a
        // bare "disconnected" milliseconds after it appears.
        val failed = LiveKitConnectionState.Failed("token expired")
        assertEquals(failed, nextConnectionState(failed, ConnectionSignal.DISCONNECTED))
    }

    @Test
    fun `a failure is not sticky against a real reconnect`() {
        val failed = LiveKitConnectionState.Failed("token expired")
        assertEquals(LiveKitConnectionState.Connecting, nextConnectionState(failed, ConnectionSignal.CONNECTING))
        assertEquals(LiveKitConnectionState.Connected, nextConnectionState(failed, ConnectionSignal.CONNECTED))
    }

    @Test
    fun `the failure reason survives the fold intact`() {
        val failed = LiveKitConnectionState.Failed("Not connected to a LiveKit room.")
        val next = nextConnectionState(failed, ConnectionSignal.DISCONNECTED)
        assertEquals("Not connected to a LiveKit room.", (next as LiveKitConnectionState.Failed).reason)
    }
}
