package com.telnyx.webrtc.sdk.verto.send

class SendingMessageBody(val id: String, val method: String, val params: ParamRequest, val jsonrpc: String = "2.0")
