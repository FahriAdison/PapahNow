package com.papahchan.papahnow.webrtc

import android.content.Context
import android.util.AttributeSet
import android.util.Log
import org.webrtc.EglBase
import org.webrtc.RendererCommon
import org.webrtc.SurfaceViewRenderer
import org.webrtc.VideoTrack

/**
 * Wrapper for org.webrtc.SurfaceViewRenderer to display incoming GFN cloud stream.
 */
class StreamRenderer @JvmOverloads constructor(
    context: Context,
    attrs: AttributeSet? = null
) : SurfaceViewRenderer(context, attrs) {

    companion object {
        private const val TAG = "StreamRenderer"
    }

    private var currentVideoTrack: VideoTrack? = null
    private var isInitialized = false

    fun initRenderer(eglContext: EglBase.Context, rendererEvents: RendererCommon.RendererEvents? = null) {
        if (isInitialized) return
        Log.i(TAG, "Initializing StreamRenderer with EGL context")
        init(eglContext, rendererEvents)
        setScalingType(RendererCommon.ScalingType.SCALE_ASPECT_FIT)
        setEnableHardwareScaler(true)
        setMirror(false)
        isInitialized = true
    }

    fun attachVideoTrack(videoTrack: VideoTrack) {
        if (currentVideoTrack == videoTrack) return
        detachVideoTrack()
        Log.i(TAG, "Attaching VideoTrack (${videoTrack.id()}) to StreamRenderer")
        currentVideoTrack = videoTrack
        videoTrack.setEnabled(true)
        videoTrack.addSink(this)
    }

    fun detachVideoTrack() {
        currentVideoTrack?.let { track ->
            Log.i(TAG, "Detaching VideoTrack (${track.id()}) from StreamRenderer")
            track.removeSink(this)
        }
        currentVideoTrack = null
    }

    fun cleanUp() {
        detachVideoTrack()
        if (isInitialized) {
            try {
                release()
            } catch (e: Exception) {
                Log.w(TAG, "Error releasing SurfaceViewRenderer", e)
            }
            isInitialized = false
        }
    }
}
