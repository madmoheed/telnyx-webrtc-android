package com.telnyx.webrtc.sdk.utilities

import java.nio.charset.StandardCharsets

    fun String.encodeBase64(): String {
        return String(
            android.util.Base64.encode(this.toByteArray(), android.util.Base64.DEFAULT),
            StandardCharsets.UTF_8
        )
    }

    fun String.decodeBase64(): String {
        return String(
            android.util.Base64.decode(this, android.util.Base64.DEFAULT),
            StandardCharsets.UTF_8
        )
    }
