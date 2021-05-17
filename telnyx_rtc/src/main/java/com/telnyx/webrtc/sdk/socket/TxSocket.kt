/*
 * Copyright © 2021 Telnyx LLC. All rights reserved.
 */

package com.telnyx.webrtc.sdk.socket

import com.bugsnag.android.Bugsnag
import com.bugsnag.android.Severity
import com.google.gson.Gson
import com.google.gson.JsonObject
import com.telnyx.webrtc.sdk.TelnyxClient
import com.telnyx.webrtc.sdk.model.SocketError.*
import com.telnyx.webrtc.sdk.model.SocketMethod.*
import io.ktor.client.*
import io.ktor.client.engine.android.*
import io.ktor.client.features.json.*
import io.ktor.client.features.websocket.*
import io.ktor.http.*
import io.ktor.util.*
import kotlinx.coroutines.*
import kotlinx.coroutines.channels.ConflatedBroadcastChannel
import io.ktor.client.engine.cio.*
import io.ktor.client.engine.okhttp.*
import io.ktor.client.features.*
import io.ktor.http.cio.*
import io.ktor.http.cio.websocket.*
import timber.log.Timber
import java.util.*

/**
 * The socket connection that will send and receive messages related to calls.
 * This class will trigger the TxSocketListener methods which can be observed to make use of the application
 *
 * @see TxSocketListener
 *
 * @param host_address the host address for the websocket to connect to
 * @param port the port that the websocket connection should use
 */
class TxSocket(
    internal val host_address: String,
    internal val port: Int
) : CoroutineScope {

    private var job: Job = SupervisorJob()
    private val gson = Gson()

    override var coroutineContext = Dispatchers.IO + job

    internal var ongoingCall = false

    private val client = HttpClient(CIO) {
        engine {
            requestTimeout = 50000
            endpoint.connectTimeout = 100000
            endpoint.connectAttempts = 30
            endpoint.keepAliveTime = 100000
        }
        install(WebSockets)
        install(JsonFeature) {
            serializer = GsonSerializer()
        }
    }

    private val sendChannel = ConflatedBroadcastChannel<String>()

    /**
     * Connects to the socket with the provided Host Address and Port which were used to create an instance of TxSocket
     * @param listener, the [TelnyxClient] used to create an instance of TxSocket that contains our relevant listener methods via the [TxSocketListener] interface
     * @see [TxSocketListener]
     */
    fun connect(listener: TelnyxClient) = launch {
        try {
            client.wss(
                host = host_address,
                port = port
            ) {
                outgoing.invokeOnClose {
                    val message = it?.message
                    Timber.tag("VERTO").d("The outgoing channel was closed $message")
                    destroy()
                }
                listener.onConnectionEstablished()
                Timber.tag("VERTO").d("Connection established")
                val sendData = sendChannel.openSubscription()
                try {
                    while (true) {
                        sendData.poll()?.let {
                            Timber.tag("VERTO")
                                .d("[%s] Sending [%s]", this@TxSocket.javaClass.simpleName, it)
                            outgoing.send(Frame.Text(it))
                        }
                        incoming.poll()?.let { frame ->
                            if (frame is Frame.Text) {
                                val data = frame.readText()
                                Timber.tag("VERTO").d(
                                    "[%s] Receiving [%s]",
                                    this@TxSocket.javaClass.simpleName,
                                    data
                                )
                                val jsonObject = gson.fromJson(data, JsonObject::class.java)
                                withContext(Dispatchers.Main) {
                                    when {
                                        jsonObject.has("result") -> {
                                            if (jsonObject.get("result").asJsonObject.has("message")) {
                                                val result = jsonObject.get("result")
                                                val message =
                                                    result.asJsonObject.get("message").asString
                                                if (message == "logged in") {
                                                    listener.onLoginSuccessful(jsonObject)
                                                }
                                            }
                                        }
                                        jsonObject.has("method") -> {
                                            Timber.tag("VERTO").d(
                                                "[%s] Received Method [%s]",
                                                this@TxSocket.javaClass.simpleName,
                                                jsonObject.get("method").asString
                                            )
                                            when (jsonObject.get("method").asString) {
                                                INVITE.methodName -> {
                                                    listener.onOfferReceived(jsonObject)
                                                }
                                                ANSWER.methodName -> {
                                                    listener.onAnswerReceived(jsonObject)
                                                }
                                                MEDIA.methodName -> {
                                                    listener.onMediaReceived(jsonObject)
                                                }
                                                BYE.methodName -> {
                                                    val params =
                                                        jsonObject.getAsJsonObject("params")
                                                    val callId =
                                                        UUID.fromString(params.get("callID").asString)
                                                    listener.onByeReceived(callId)
                                                }
                                                INVITE.methodName -> {
                                                    listener.onOfferReceived(jsonObject)
                                                }
                                            }
                                        }
                                        jsonObject.has("error") -> {
                                            val errorCode =
                                                jsonObject.get("error").asJsonObject.get("code").asInt
                                            Timber.tag("VERTO").d(
                                                "[%s] Received Error From Telnyx [%s]",
                                                this@TxSocket.javaClass.simpleName,
                                                jsonObject.get("error").asJsonObject.get("message")
                                                    .toString()
                                            )
                                            when (errorCode) {
                                                CREDENTIAL_ERROR.errorCode -> {
                                                    listener.onErrorReceived(jsonObject)
                                                }
                                                TOKEN_ERROR.errorCode -> {
                                                    listener.onErrorReceived(jsonObject)
                                                }
                                            }
                                        }
                                    }
                                }
                            }
                        }
                    }
                } catch (exception: Throwable) {
                    Timber.d(exception)
                    Bugsnag.notify(exception) { event ->
                        // Add extra information
                        event.addMetadata("availableMemory", "", Runtime.getRuntime().freeMemory())
                        //This is not an issue, the coroutine has just been cancelled
                        event.severity = Severity.INFO
                        true
                    }
                }
            }
        } catch (cause: Throwable) {
            Timber.d(cause)
            Bugsnag.notify(cause) { event ->
                // Add extra information
                event.addMetadata("availableMemory", "", Runtime.getRuntime().freeMemory())
                //This is not an issue, the coroutine has just been cancelled
                event.severity = Severity.INFO
                true
            }

        }
    }

    /**
     * Sets the ongoingCall boolean value to true
     */
    internal fun callOngoing() {
        ongoingCall = true
    }

    /**
     * Sets the ongoingCall boolean value to false
     */
    internal fun callNotOngoing() {
        ongoingCall = false
    }

    /**
     * Sends data to our [sendChannel], broadcasting the message to the subscription within our websocket connection which will then be sent to the Telnyx Socket connection
     * @param dataObject, the data to be send to our subscriber
     */
    internal fun send(dataObject: Any?) = runBlocking {
        sendChannel.send(gson.toJson(dataObject))
    }

    /**
     * Closes our websocket connection and cancels our coroutine job
     */
    internal fun destroy() {
        client.close()
        job.cancel()
    }
}