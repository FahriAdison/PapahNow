package com.papahchan.papahnow.signaling

import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import kotlinx.serialization.json.intOrNull
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive

val jsonConfig = Json {
    ignoreUnknownKeys = true
    isLenient = true
    encodeDefaults = true
    coerceInputValues = true
}

/**
 * Top-level message envelope received from or sent to GFN WebSocket signaling.
 */
@Serializable
data class SignalingEnvelope(
    @SerialName("ack") val ack: Int? = null,
    @SerialName("ackid") val ackIdLower: Int? = null,
    @SerialName("ackId") val ackIdCamel: Int? = null,
    @SerialName("hb") val hb: Int? = null,
    @SerialName("peer_info") val peerInfo: PeerInfo? = null,
    @SerialName("peer_msg") val peerMsg: PeerMessage? = null,
    @SerialName("error") val error: String? = null
) {
    val ackId: Int?
        get() = ackIdLower ?: ackIdCamel
}

@Serializable
data class PeerInfo(
    @SerialName("id") val id: Int,
    @SerialName("name") val name: String
)

@Serializable
data class PeerMessage(
    @SerialName("from") val from: Int,
    @SerialName("to") val to: Int,
    @SerialName("msg") val msg: JsonElement
)

/**
 * Client-initiated ack message.
 */
@Serializable
data class ClientAck(
    @SerialName("ack") val ack: Int
)

/**
 * Client-initiated peer message wrapper with ackId.
 */
@Serializable
data class ClientPeerMessageWrapper(
    @SerialName("peer_msg") val peerMsg: PeerMessagePayload,
    @SerialName("ackid") val ackid: Int
)

@Serializable
data class PeerMessagePayload(
    @SerialName("from") val from: Int,
    @SerialName("to") val to: Int,
    @SerialName("msg") val msg: String
)

/**
 * Parsed payload inside peer_msg.msg (Offer or Answer).
 */
@Serializable
data class SdpPayload(
    @SerialName("type") val type: String, // "offer" or "answer"
    @SerialName("sdp") val sdp: String,
    @SerialName("nvstSdp") val nvstSdp: String? = null
)

/**
 * Parsed payload inside peer_msg.msg for ICE candidates.
 */
@Serializable
data class IceCandidatePayload(
    @SerialName("sdpMLineIndex") val sdpMLineIndex: Int = 0,
    @SerialName("sdpMid") val sdpMid: String = "",
    @SerialName("candidate") val candidate: String
)

/**
 * Helper to parse inner peer message from either a String primitive or an object JsonElement.
 */
sealed class InnerPeerMessage {
    data class Offer(val sdp: String, val nvstSdp: String?) : InnerPeerMessage()
    data class Answer(val sdp: String, val nvstSdp: String?) : InnerPeerMessage()
    data class IceCandidate(val sdpMid: String, val sdpMLineIndex: Int, val candidate: String) : InnerPeerMessage()
    data class Unknown(val raw: String) : InnerPeerMessage()

    companion object {
        fun fromJsonElement(element: JsonElement): InnerPeerMessage {
            val jsonObject: JsonObject = when (element) {
                is JsonObject -> element
                is JsonPrimitive -> {
                    val content = element.contentOrNull ?: return Unknown(element.toString())
                    try {
                        jsonConfig.parseToJsonElement(content).jsonObject
                    } catch (_: Exception) {
                        return Unknown(content)
                    }
                }
                else -> return Unknown(element.toString())
            }

            val type = jsonObject["type"]?.jsonPrimitive?.contentOrNull
            if (type == "offer") {
                val sdp = jsonObject["sdp"]?.jsonPrimitive?.contentOrNull ?: ""
                val nvst = jsonObject["nvstSdp"]?.jsonPrimitive?.contentOrNull
                return Offer(sdp = sdp, nvstSdp = nvst)
            } else if (type == "answer") {
                val sdp = jsonObject["sdp"]?.jsonPrimitive?.contentOrNull ?: ""
                val nvst = jsonObject["nvstSdp"]?.jsonPrimitive?.contentOrNull
                return Answer(sdp = sdp, nvstSdp = nvst)
            }

            val candidate = jsonObject["candidate"]?.jsonPrimitive?.contentOrNull
            if (candidate != null) {
                val sdpMid = jsonObject["sdpMid"]?.jsonPrimitive?.contentOrNull ?: ""
                val sdpMLineIndex = jsonObject["sdpMLineIndex"]?.jsonPrimitive?.intOrNull ?: 0
                return IceCandidate(sdpMid = sdpMid, sdpMLineIndex = sdpMLineIndex, candidate = candidate)
            }

            return Unknown(jsonObject.toString())
        }
    }
}
