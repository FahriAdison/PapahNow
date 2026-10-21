package com.papahchan.papahnow.webrtc

import android.content.Context
import android.util.Log
import com.papahchan.papahnow.signaling.IceServerConfig
import com.papahchan.papahnow.signaling.NvstSdpBuilder
import org.webrtc.DataChannel
import org.webrtc.DefaultVideoDecoderFactory
import org.webrtc.DefaultVideoEncoderFactory
import org.webrtc.EglBase
import org.webrtc.IceCandidate
import org.webrtc.MediaConstraints
import org.webrtc.MediaStream
import org.webrtc.MediaStreamTrack
import org.webrtc.PeerConnection
import org.webrtc.PeerConnectionFactory
import org.webrtc.RtpReceiver
import org.webrtc.RtpTransceiver
import org.webrtc.SdpObserver
import org.webrtc.SessionDescription
import org.webrtc.VideoTrack

class GfnPeerConnection(
    private val context: Context,
    private val listener: Listener
) {
    interface Listener {
        fun onSendLocalAnswer(sdp: String, nvstSdp: String?)
        fun onSendLocalIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String)
        fun onVideoTrackReceived(videoTrack: VideoTrack)
        fun onIceConnectionStateChange(state: PeerConnection.IceConnectionState)
        fun onConnectionStateChange(state: PeerConnection.PeerConnectionState)
        fun onError(description: String)
    }

    companion object {
        private const val TAG = "GfnPeerConnection"
        private var isFactoryInitialized = false

        // Native-client fallback used when the session response publishes no ICE servers.
        private val FALLBACK_ICE_SERVERS = listOf(
            "stun:s1.stun.gamestream.nvidia.com:19308",
            "stun:stun.l.google.com:19302",
            "stun:stun1.l.google.com:19302"
        )
    }

    val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var cachedNvstSdp: String? = null

    // Input/stats channels negotiated before the remote offer, matching the native
    // client. Kept as fields so a repeated offer never creates duplicates.
    private var inputChannelV1: DataChannel? = null
    private var partialChannel: DataChannel? = null
    private var statsChannel: DataChannel? = null

    // ICE diagnostics: surfaced in the UI banner when signaling/ICE fails.
    @Volatile
    private var localIceCandidateCount = 0
    @Volatile
    private var remoteIceCandidateCount = 0
    @Volatile
    private var lastIceConnectionState: PeerConnection.IceConnectionState? = null
    @Volatile
    private var lastPeerConnectionState: PeerConnection.PeerConnectionState? = null

    init {
        initializeFactory()
    }

    private fun initializeFactory() {
        if (!isFactoryInitialized) {
            val initOptions = PeerConnectionFactory.InitializationOptions.builder(context)
                .setEnableInternalTracer(true)
                .createInitializationOptions()
            PeerConnectionFactory.initialize(initOptions)
            isFactoryInitialized = true
        }

        val encoderFactory = DefaultVideoEncoderFactory(eglBase.eglBaseContext, true, true)
        val decoderFactory = DefaultVideoDecoderFactory(eglBase.eglBaseContext)

        peerConnectionFactory = PeerConnectionFactory.builder()
            .setVideoEncoderFactory(encoderFactory)
            .setVideoDecoderFactory(decoderFactory)
            .createPeerConnectionFactory()

        Log.i(TAG, "PeerConnectionFactory initialized with hardware decoder/encoder")
    }

    fun createPeerConnection(iceServers: List<IceServerConfig> = emptyList()) {
        localIceCandidateCount = 0
        remoteIceCandidateCount = 0
        lastIceConnectionState = null
        lastPeerConnectionState = null
        inputChannelV1 = null
        partialChannel = null
        statsChannel = null

        val rtcIceServers = if (iceServers.isEmpty()) {
            FALLBACK_ICE_SERVERS.map { url ->
                PeerConnection.IceServer.builder(url).createIceServer()
            }
        } else {
            iceServers.map { config ->
                PeerConnection.IceServer.builder(config.urls)
                    .apply {
                        config.username?.let { setUsername(it) }
                        config.credential?.let { setPassword(it) }
                    }
                    .createIceServer()
            }
        }
        Log.i(
            TAG,
            "ICE servers: ${rtcIceServers.size} " +
                (if (iceServers.isEmpty()) "(fallback STUN)" else "(from session)")
        )

        val rtcConfig = PeerConnection.RTCConfiguration(rtcIceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                lastIceConnectionState = state
                Log.i(TAG, "onIceConnectionChange: $state")
                listener.onIceConnectionStateChange(state)
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                lastIceConnectionState = newState
                Log.d(TAG, "onStandardizedIceConnectionChange: $newState")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
                lastPeerConnectionState = newState
                Log.i(TAG, "onConnectionChange: $newState")
                listener.onConnectionStateChange(newState)
            }

            override fun onIceConnectionReceivingChange(receiving: Boolean) {
                Log.d(TAG, "onIceConnectionReceivingChange: $receiving")
            }

            override fun onIceGatheringChange(state: PeerConnection.IceGatheringState) {
                Log.d(TAG, "onIceGatheringChange: $state")
            }

            override fun onIceCandidate(candidate: IceCandidate) {
                if (candidate.sdp.contains(" tcp ", ignoreCase = true)) {
                    Log.i(TAG, "Skipping TCP ICE candidate: ${candidate.sdpMid}[${candidate.sdpMLineIndex}]")
                    return
                }
                localIceCandidateCount++
                Log.i(TAG, "Local ICE candidate discovered: ${candidate.sdpMid}[${candidate.sdpMLineIndex}] => ${candidate.sdp}")
                listener.onSendLocalIceCandidate(
                    candidate.sdpMid ?: "",
                    candidate.sdpMLineIndex,
                    candidate.sdp
                )
            }

            override fun onIceCandidatesRemoved(candidates: Array<out IceCandidate>?) {
                Log.d(TAG, "onIceCandidatesRemoved: ${candidates?.size}")
            }

            override fun onAddStream(stream: MediaStream) {
                Log.i(TAG, "onAddStream: ${stream.id}, videoTracks=${stream.videoTracks.size}")
                if (stream.videoTracks.isNotEmpty()) {
                    val track = stream.videoTracks[0]
                    track.setEnabled(true)
                    listener.onVideoTrackReceived(track)
                }
            }

            override fun onRemoveStream(stream: MediaStream) {
                Log.d(TAG, "onRemoveStream: ${stream.id}")
            }

            override fun onDataChannel(dataChannel: DataChannel) {
                Log.i(TAG, "onDataChannel: label=${dataChannel.label()}, id=${dataChannel.id()}")
            }

            override fun onRenegotiationNeeded() {
                Log.d(TAG, "onRenegotiationNeeded")
            }

            override fun onAddTrack(receiver: RtpReceiver, mediaStreams: Array<out MediaStream>) {
                val track = receiver.track()
                Log.i(TAG, "onAddTrack: kind=${track?.kind()}, id=${track?.id()}")
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    listener.onVideoTrackReceived(track)
                }
            }

            override fun onTrack(transceiver: RtpTransceiver) {
                val track = transceiver.receiver.track()
                Log.i(TAG, "onTrack: kind=${track?.kind()}, id=${track?.id()}")
                if (track is VideoTrack) {
                    track.setEnabled(true)
                    listener.onVideoTrackReceived(track)
                }
            }
        })

        // Pre-add receive transceivers for Unified Plan
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_VIDEO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )
        peerConnection?.addTransceiver(
            MediaStreamTrack.MediaType.MEDIA_TYPE_AUDIO,
            RtpTransceiver.RtpTransceiverInit(RtpTransceiver.RtpTransceiverDirection.RECV_ONLY)
        )

        Log.i(TAG, "PeerConnection created successfully")
    }

    /**
     * Creates the three native input/stats data channels before the remote offer is
     * applied, so the SDP answer carries the required m=application section. Existing
     * channels are reused.
     */
    private fun ensureInputDataChannels() {
        val pc = peerConnection ?: return

        if (inputChannelV1 == null) {
            inputChannelV1 = pc.createDataChannel(
                "input_channel_v1",
                DataChannel.Init().apply { ordered = true }
            )
            Log.i(TAG, "Created data channel input_channel_v1")
        }

        if (partialChannel == null) {
            val threshold = Regex("a=ri\\.partialReliableThresholdMs:(\\d+)")
                .find(cachedNvstSdp.orEmpty())
                ?.groupValues?.getOrNull(1)
                ?.toIntOrNull() ?: 30
            partialChannel = pc.createDataChannel(
                "input_channel_partially_reliable",
                DataChannel.Init().apply {
                    ordered = false
                    maxRetransmitTimeMs = threshold
                }
            )
            Log.i(TAG, "Created data channel input_channel_partially_reliable (threshold=${threshold}ms)")
        }

        if (statsChannel == null) {
            statsChannel = pc.createDataChannel(
                "stats_channel",
                DataChannel.Init().apply {
                    ordered = false
                    maxRetransmits = 0
                }
            )
            Log.i(TAG, "Created data channel stats_channel")
        }
    }

    /**
     * Set the remote SDP offer as received from GFN.
     * CRITICAL: Do NOT modify or filter the SDP string; keep all attributes untouched.
     */
    fun handleRemoteOffer(sdp: String, nvstSdp: String?) {
        cachedNvstSdp = nvstSdp
        // Must happen before setRemoteDescription: the answer's m=application is
        // negotiated from these locally-created channels.
        ensureInputDataChannels()
        Log.i(TAG, "Setting remote SDP offer (length=${sdp.length})")
        val sessionDescription = SessionDescription(SessionDescription.Type.OFFER, sdp)

        peerConnection?.setRemoteDescription(object : SimpleSdpObserver("setRemoteDescription") {
            override fun onSetSuccess() {
                super.onSetSuccess()
                Log.i(TAG, "Remote description set successfully, now creating answer...")
                createAnswer()
            }

            override fun onSetFailure(error: String?) {
                super.onSetFailure(error)
                listener.onError("Failed to setRemoteDescription: $error")
            }
        }, sessionDescription)
    }

    private fun createAnswer() {
        val constraints = MediaConstraints().apply {
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveVideo", "true"))
            mandatory.add(MediaConstraints.KeyValuePair("OfferToReceiveAudio", "true"))
        }

        peerConnection?.createAnswer(object : SimpleSdpObserver("createAnswer") {
            override fun onCreateSuccess(desc: SessionDescription) {
                super.onCreateSuccess(desc)
                Log.i(TAG, "Local SDP answer created, setting local description...")
                setLocalDescription(desc)
            }

            override fun onCreateFailure(error: String?) {
                super.onCreateFailure(error)
                listener.onError("Failed to createAnswer: $error")
            }
        }, constraints)
    }

    private fun setLocalDescription(desc: SessionDescription) {
        peerConnection?.setLocalDescription(object : SimpleSdpObserver("setLocalDescription") {
            override fun onSetSuccess() {
                super.onSetSuccess()
                Log.i(TAG, "Local description set successfully. Sending answer to signaling!")
                val clientNvstSdp = NvstSdpBuilder.build(cachedNvstSdp.orEmpty(), 1280, 720, 60, 15, desc.description)
                listener.onSendLocalAnswer(desc.description, clientNvstSdp)
            }

            override fun onSetFailure(error: String?) {
                super.onSetFailure(error)
                listener.onError("Failed to setLocalDescription: $error")
            }
        }, desc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        remoteIceCandidateCount++
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
        Log.d(TAG, "Added remote ICE candidate: $sdpMid[$sdpMLineIndex]")
    }

    /** Counts and last known states for the error banner. */
    fun iceDiagnosticSummary(): String =
        "ICE diagnostics: localCandidatesSent=$localIceCandidateCount, " +
            "remoteCandidatesReceived=$remoteIceCandidateCount, " +
            "lastIceState=${lastIceConnectionState?.name ?: "n/a"}, " +
            "lastPeerConnectionState=${lastPeerConnectionState?.name ?: "n/a"}"

    fun dispose() {
        Log.i(TAG, "Disposing PeerConnection resources...")
        try {
            peerConnection?.close()
            peerConnection?.dispose()
            peerConnection = null

            peerConnectionFactory?.dispose()
            peerConnectionFactory = null

            eglBase.release()
        } catch (e: Exception) {
            Log.w(TAG, "Error disposing peer connection", e)
        }
    }

    private open class SimpleSdpObserver(private val tag: String) : SdpObserver {
        override fun onCreateSuccess(desc: SessionDescription) {
            Log.d(TAG, "$tag: onCreateSuccess")
        }

        override fun onSetSuccess() {
            Log.d(TAG, "$tag: onSetSuccess")
        }

        override fun onCreateFailure(error: String?) {
            Log.e(TAG, "$tag: onCreateFailure: $error")
        }

        override fun onSetFailure(error: String?) {
            Log.e(TAG, "$tag: onSetFailure: $error")
        }
    }
}
