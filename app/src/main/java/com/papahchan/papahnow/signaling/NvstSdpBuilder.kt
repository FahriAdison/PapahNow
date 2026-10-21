package com.papahchan.papahnow.signaling

/**
 * Builds the CLIENT-side nvstSdp for the SDP answer.
 *
 * Ported verbatim from NanaPlay's SdpTools.buildNvstSdp (the proven native
 * client flow). Critical: the client's nvstSdp must carry the LOCAL answer's
 * ICE ufrag/pwd and DTLS fingerprint — echoing the server's nvstSdp back
 * makes the server drop the connection.
 *
 * PoC simplifications vs NanaPlay: no HDR (8-bit), H264 only, no dynamic
 * network adjustment (NoAdjustment-equivalent values).
 */
object NvstSdpBuilder {

    private const val OFFICIAL_MIN_BITRATE_KBPS = 4000

    fun build(
        offerNvstSdp: String,
        width: Int,
        height: Int,
        fps: Int,
        maxBitrateMbps: Int,
        localAnswerSdp: String
    ): String {
        val ufrag = Regex("a=ice-ufrag:([^\\r\\n]+)").find(localAnswerSdp)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val pwd = Regex("a=ice-pwd:([^\\r\\n]+)").find(localAnswerSdp)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val fingerprint = Regex("a=fingerprint:sha-256 ([^\\r\\n]+)").find(localAnswerSdp)?.groupValues?.getOrNull(1)?.trim().orEmpty()
        val threshold = Regex("a=ri\\.partialReliableThresholdMs:(\\d+)").find(offerNvstSdp)?.groupValues?.getOrNull(1)?.toIntOrNull() ?: 30
        val bitDepth = 8
        val maxBitrate = maxOf(OFFICIAL_MIN_BITRATE_KBPS, maxBitrateMbps * 1000)
        val minBitrate = OFFICIAL_MIN_BITRATE_KBPS
        val initialBitrate = maxOf(minBitrate, maxBitrate / 4)
        val isHighFps = fps > 60
        val isAtLeast120Fps = fps >= 120
        val is90Fps = fps == 90
        val is120Fps = fps == 120
        val isAtLeast240Fps = fps >= 240
        val isAv1 = false
        val minTargetFrameTimeUs = ((1_000_000L * 95L) / (fps.coerceAtLeast(1) * 100L))
            .coerceAtLeast(1000L)
        return buildList {
            add("v=0")
            add("o=SdpTest test_id_13 14 IN IPv4 127.0.0.1")
            add("s=-")
            add("t=0 0")
            add("a=general.icePassword:$pwd")
            add("a=general.iceUserNameFragment:$ufrag")
            add("a=general.dtlsFingerprint:$fingerprint")
            add("m=video 0 RTP/AVP")
            add("a=msid:fbc-video-0")
            add("a=vqos.fec.rateDropWindow:10")
            add("a=vqos.fec.minRequiredFecPackets:2")
            add("a=vqos.fec.repairMinPercent:5")
            add("a=vqos.fec.repairPercent:5")
            add("a=vqos.fec.repairMaxPercent:35")
            add("a=vqos.bllFec.enable:0")
            add("a=vqos.dynamicStreamingMode:0")
            add("a=vqos.drc.enable:0")
            add("a=vqos.calculateAvgVideoStreamingBitrate:1")
            add("a=video.dx9EnableNv12:1")
            add("a=video.dx9EnableHdr:0")
            add("a=vqos.qpg.enable:1")
            add("a=vqos.resControl.qp.qpg.featureSetting:7")
            add("a=video.adaptiveQuantization.spatialAQSetting:7")
            add("a=video.adaptiveQuantization.temporalAQSetting:0")
            add("a=video.adaptiveQuantization.spatialAQStrength:12")
            add("a=video.adaptiveQuantization.qpThresholdAdjPercent:2")
            add("a=video.adaptiveQuantization.saqAdaptMinQpThresholdPercent:40")
            add("a=video.adaptiveQuantization.saqAdaptMaxQpThresholdPercent:100")
            add("a=video.adaptiveQuantization.saqAdaptDecayStrengthX100:250")
            add("a=video.adaptiveQuantization.perfAdjEnablement:1")
            add("a=video.framePacing.mode:2")
            add("a=video.framePacing.pid.minTargetFrameTimeUs:$minTargetFrameTimeUs")
            add("a=bwe.useOwdCongestionControl:1")
            add("a=video.enableRtpNack:1")
            add("a=vqos.bw.txRxLag.minFeedbackTxDeltaMs:200")
            add("a=vqos.drc.bitrateIirFilterFactor:18")
            add("a=video.packetSize:1140")
            add("a=packetPacing.version:3")
            add("a=packetPacing.mode:1")
            add("a=packetPacing.minNumPacketsPerGroup:15")
            add("a=packetPacing.enableAccurateSleep:1")
            add("a=packetPacing.enableSmoothTransition:1")
            add("a=packetPacing.allowFpsBasedToggle:1")
            add("a=vqos.relaxMaxBitrate.overrideAvgBitrateThresholdPercent:4")
            add("a=vqos.relaxMaxBitrate.customAvgBitrateThresholdPercent:65")
            add("a=vqos.relaxMaxBitrate.overrideAvgQpThresholdPercent:7")
            add("a=vqos.relaxMaxBitrate.customAvgQpThresholdPercent:51")
            add("a=vqos.relaxMaxBitrate.iirFilterFactor:120")
            add("a=vqos.qpDelta.qpDeltaMaxPercent:10")
            add("a=vqos.qpDelta.qpDeltaSurfaceAdjustmentStrengthPercent:70")
            add("a=vqos.qpDelta.qpDeltaVbvUsageFactorPercentH264:100")
            add("a=vqos.qpDelta.qpDeltaVbvUsageFactorPercentH265:100")
            add("a=vqos.qpDelta.qpDeltaVbvUsageFactorPercentAv1:100")
            add("a=vqos.qpDelta.qpDeltaMinPercent:60")
            add("a=vqos.qpDelta.qpDeltaIirFactor:60")
            add("a=vqos.qpDelta.qpDeltaThrottlePercent:100")
            if (isHighFps) {
                add("a=vqos.dfc.enable:1")
                add("a=vqos.dfc.decodeFpsAdjPercent:85")
                add("a=vqos.dfc.targetDownCooldownMs:250")
                add("a=vqos.dfc.dfcAlgoVersion:${if (isAtLeast120Fps) 2 else 1}")
                add("a=vqos.dfc.minTargetFps:${if (isAtLeast120Fps) 100 else 60}")
                add("a=vqos.resControl.dfc.useClientFpsPerf:0")
                add("a=vqos.dfc.adjustResAndFps:0")
                add("a=bwe.iirFilterFactor:8")
                add("a=video.encoderFeatureSetting:47")
                add("a=video.encoderPreset:6")
                val captureTuning = when {
                    is90Fps -> 9 to 11
                    is120Fps -> 6 to 9
                    isAtLeast240Fps -> 18 to 9
                    else -> null
                }
                captureTuning?.let { (grabTimeoutMs, decodeThresholdMs) ->
                    add("a=video.fbcDynamicFpsGrabTimeoutMs:$grabTimeoutMs")
                    add("a=vqos.resControl.cpmRtc.decodeTimeThresholdMs:$decodeThresholdMs")
                }
            } else {
                add("a=vqos.dfc.enable:0")
                add("a=vqos.dfc.adjustResAndFps:0")
            }
            if (isAtLeast240Fps) {
                add("a=video.enableNextCaptureMode:1")
                add("a=vqos.maxStreamFpsEstimate:$fps")
                add("a=video.videoSplitEncodeStripsPerFrame:3")
                add("a=video.updateSplitEncodeStateDynamically:1")
                add("a=vqos.rtcPreemptiveIdrSettings.minBurstNackSize:65535")
                add("a=vqos.rtcPreemptiveIdrSettings.minNackPacketCaptureAgeMs:65535")
            }
            add("a=vqos.adjustStreamingFpsDuringOutOfFocus:1")
            add("a=vqos.resControl.cpmRtc.ignoreOutOfFocusWindowState:1")
            add("a=vqos.resControl.perfHistory.rtcIgnoreOutOfFocusWindowState:1")
            val cpmEnabled = false
            val minResPercent = 100
            val resHoldonMs = 999999
            val qpMaxResAdj = 4
            add("a=vqos.resControl.cpmRtc.featureMask:${if (cpmEnabled) 1 else 0}")
            add("a=vqos.resControl.cpmRtc.enable:${if (cpmEnabled) 1 else 0}")
            add("a=vqos.resControl.cpmRtc.minResolutionPercent:$minResPercent")
            add("a=vqos.resControl.cpmRtc.resolutionChangeHoldonMs:$resHoldonMs")
            add("a=packetPacing.numGroups:${if (is120Fps) 3 else 5}")
            add("a=packetPacing.maxDelayUs:1000")
            add("a=packetPacing.minNumPacketsFrame:10")
            add("a=video.rtpNackQueueLength:1024")
            add("a=video.rtpNackQueueMaxPackets:512")
            add("a=video.rtpNackMaxPacketCount:25")
            add("a=vqos.drc.qpMaxResThresholdAdj:$qpMaxResAdj")
            add("a=vqos.grc.qpMaxResThresholdAdj:$qpMaxResAdj")
            add("a=vqos.drc.iirFilterFactor:100")
            add("a=video.clientViewportWd:$width")
            add("a=video.clientViewportHt:$height")
            add("a=video.maxFPS:$fps")
            add("a=video.initialBitrateKbps:$initialBitrate")
            add("a=video.initialPeakBitrateKbps:$initialBitrate")
            add("a=vqos.bw.maximumBitrateKbps:$maxBitrate")
            add("a=vqos.bw.minimumBitrateKbps:$minBitrate")
            add("a=vqos.bw.peakBitrateKbps:$maxBitrate")
            add("a=vqos.bw.serverPeakBitrateKbps:$maxBitrate")
            add("a=vqos.bw.enableBandwidthEstimation:1")
            add("a=vqos.bw.disableBitrateLimit:0")
            add("a=vqos.grc.maximumBitrateKbps:$maxBitrate")
            add("a=vqos.grc.enable:0")
            add("a=video.maxNumReferenceFrames:4")
            add("a=video.mapRtpTimestampsToFrames:1")
            add("a=video.encoderCscMode:3")
            add("a=video.dynamicRangeMode:0")
            add("a=video.bitDepth:$bitDepth")
            add("a=video.scalingFeature1:0")
            add("a=video.prefilterParams.prefilterModel:0")
            add("m=audio 0 RTP/AVP")
            add("a=msid:audio")
            add("m=mic 0 RTP/AVP")
            add("a=msid:mic")
            add("a=rtpmap:0 PCMU/8000")
            add("m=application 0 RTP/AVP")
            add("a=msid:input_1")
            add("a=ri.partialReliableThresholdMs:$threshold")
            add("a=ri.hidDeviceMask:4294967295")
            add("a=ri.enablePartiallyReliableTransferGamepad:15")
            add("a=ri.enablePartiallyReliableTransferHid:4294967295")
            add("")
        }.joinToString("\n")
    }
}
