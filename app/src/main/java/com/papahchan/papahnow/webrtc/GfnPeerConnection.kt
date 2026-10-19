package com.papahchan.papahnow.webrtc

import android.content.Context
import android.util.Log
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
    }

    val eglBase: EglBase = EglBase.create()
    private var peerConnectionFactory: PeerConnectionFactory? = null
    private var peerConnection: PeerConnection? = null
    private var cachedNvstSdp: String? = null

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

    fun createPeerConnection(customStunUrl: String? = null) {
        val iceServers = mutableListOf<PeerConnection.IceServer>()
        
        // Priority STUN server from GFN docs
        val gfnStun = customStunUrl ?: "stun:80.250.97.39:5004"
        iceServers.add(PeerConnection.IceServer.builder(gfnStun).createIceServer())
        // Standard fallback STUN
        iceServers.add(PeerConnection.IceServer.builder("stun:stun.l.google.com:19302").createIceServer())

        val rtcConfig = PeerConnection.RTCConfiguration(iceServers).apply {
            sdpSemantics = PeerConnection.SdpSemantics.UNIFIED_PLAN
            bundlePolicy = PeerConnection.BundlePolicy.MAXBUNDLE
            rtcpMuxPolicy = PeerConnection.RtcpMuxPolicy.REQUIRE
            continualGatheringPolicy = PeerConnection.ContinualGatheringPolicy.GATHER_CONTINUALLY
            enableDtlsSrtp = true
        }

        peerConnection = peerConnectionFactory?.createPeerConnection(rtcConfig, object : PeerConnection.Observer {
            override fun onSignalingChange(state: PeerConnection.SignalingState) {
                Log.d(TAG, "onSignalingChange: $state")
            }

            override fun onIceConnectionChange(state: PeerConnection.IceConnectionState) {
                Log.i(TAG, "onIceConnectionChange: $state")
                listener.onIceConnectionStateChange(state)
            }

            override fun onStandardizedIceConnectionChange(newState: PeerConnection.IceConnectionState) {
                Log.d(TAG, "onStandardizedIceConnectionChange: $newState")
            }

            override fun onConnectionChange(newState: PeerConnection.PeerConnectionState) {
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
     * Set the remote SDP offer as received from GFN.
     * CRITICAL: Do NOT modify or filter the SDP string; keep all attributes untouched.
     */
    fun handleRemoteOffer(sdp: String, nvstSdp: String?) {
        cachedNvstSdp = nvstSdp
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
                listener.onSendLocalAnswer(desc.description, cachedNvstSdp)
            }

            override fun onSetFailure(error: String?) {
                super.onSetFailure(error)
                listener.onError("Failed to setLocalDescription: $error")
            }
        }, desc)
    }

    fun addRemoteIceCandidate(sdpMid: String, sdpMLineIndex: Int, candidate: String) {
        val iceCandidate = IceCandidate(sdpMid, sdpMLineIndex, candidate)
        peerConnection?.addIceCandidate(iceCandidate)
        Log.d(TAG, "Added remote ICE candidate: $sdpMid[$sdpMLineIndex]")
    }

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
