package com.papahchan.papahnow.signaling

data class IceServerConfig(
    val urls: List<String>,
    val username: String?,
    val credential: String?
)
