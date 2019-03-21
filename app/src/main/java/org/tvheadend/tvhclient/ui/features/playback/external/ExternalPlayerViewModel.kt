package org.tvheadend.tvhclient.ui.features.playback.external

import android.app.Application
import android.content.Context
import android.content.SharedPreferences
import android.os.Bundle
import android.text.TextUtils
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.MutableLiveData
import org.tvheadend.tvhclient.MainApplication
import org.tvheadend.tvhclient.R
import org.tvheadend.tvhclient.data.repository.AppRepository
import org.tvheadend.tvhclient.data.service.htsp.HtspConnection
import org.tvheadend.tvhclient.data.service.htsp.HtspConnectionStateListener
import org.tvheadend.tvhclient.data.service.htsp.HtspMessage
import org.tvheadend.tvhclient.data.service.htsp.HtspResponseListener
import org.tvheadend.tvhclient.domain.entity.Channel
import org.tvheadend.tvhclient.domain.entity.Connection
import org.tvheadend.tvhclient.domain.entity.Recording
import org.tvheadend.tvhclient.domain.entity.ServerStatus
import timber.log.Timber
import java.util.concurrent.ExecutionException
import java.util.concurrent.Executors
import java.util.concurrent.ScheduledExecutorService
import javax.inject.Inject

class ExternalPlayerViewModel(application: Application) : AndroidViewModel(application), HtspConnectionStateListener {

    @Inject
    lateinit var context: Context
    @Inject
    lateinit var sharedPreferences: SharedPreferences
    @Inject
    lateinit var appRepository: AppRepository

    // Connection related
    private val execService: ScheduledExecutorService = Executors.newScheduledThreadPool(10)
    private val htspConnection: HtspConnection

    var connection: Connection
    var serverStatus: ServerStatus? = null
    var channel: Channel? = null
    var recording: Recording? = null
    private var path = ""
    private var ticket = ""

    // Observable fields
    var isTicketReceived: MutableLiveData<Boolean> = MutableLiveData()
    var isConnected: MutableLiveData<Boolean> = MutableLiveData()

    init {
        Timber.d("Initializing view model")
        MainApplication.getComponent().inject(this)

        connection = appRepository.connectionData.activeItem
        serverStatus = appRepository.serverStatusData.activeItem

        Timber.d("Starting connection")
        val connectionTimeout = Integer.valueOf(sharedPreferences.getString("connection_timeout", application.resources.getString(R.string.pref_default_connection_timeout))!!) * 1000
        htspConnection = HtspConnection(
                connection.username ?: "",
                connection.password ?: "",
                connection.hostname ?: "",
                connection.port,
                connectionTimeout,
                this, null)

        execService.execute {
            htspConnection.openConnection()
            htspConnection.authenticate()
        }
    }

    override fun onConnectionStateChange(state: HtspConnection.ConnectionState) {
        when (state) {
            HtspConnection.ConnectionState.FAILED..HtspConnection.ConnectionState.FAILED_EXCEPTION_OPENING_SOCKET -> {
                Timber.d("Connection failed")
                isConnected.postValue(false)
            }
            else -> {
                Timber.d("Connected, initializing or idle")
            }
        }
    }

    override fun onAuthenticationStateChange(state: HtspConnection.AuthenticationState) {
        when (state) {
            HtspConnection.AuthenticationState.FAILED,
            HtspConnection.AuthenticationState.FAILED_BAD_CREDENTIALS -> {
                Timber.d("Authorization failed")
                isConnected.postValue(false)
            }
            HtspConnection.AuthenticationState.AUTHENTICATED -> {
                Timber.d("Authenticated, starting player")
                isConnected.postValue(true)
            }
            else -> {
                Timber.d("Initializing or authenticating")
            }
        }
    }

    override fun onCleared() {
        super.onCleared()
        Timber.d("Clearing view model")
        execService.shutdown()
        htspConnection.closeConnection()
    }

    fun requestTicketFromServer(bundle: Bundle?) {
        val channelId = bundle?.getInt("channelId") ?: 0
        val dvrId = bundle?.getInt("dvrId") ?: 0

        val request = HtspMessage()
        request["method"] = "getTicket"
        if (channelId > 0) {
            channel = appRepository.channelData.getItemById(channelId)
            request["channelId"] = channelId
        }
        if (dvrId > 0) {
            recording = appRepository.recordingData.getItemById(dvrId)
            request["dvrId"] = dvrId
        }
        htspConnection.sendMessage(request, object : HtspResponseListener {
            override fun handleResponse(response: HtspMessage) {
                Timber.d("Received response for ticket request")
                path = response.getString("path", "")
                ticket = response.getString("ticket", "")
                isTicketReceived.postValue(true)
            }
        })
    }

    fun getServerUrl(convertHostnameToAddress: Boolean = false): String {
        // Convert the hostname to the IP address only when required.
        // This is usually required when a channel or recording shall
        // be played on a chromecast
        var hostname = connection.hostname
        if (convertHostnameToAddress && !TextUtils.isEmpty(connection.hostname)) {
            Timber.d("Convert hostname ${connection.hostname} to IP address")
            try {
                hostname = ConvertHostnameToAddressTask(connection.hostname ?: "").execute().get()
            } catch (e: InterruptedException) {
                Timber.d("Could not execute task to get ip address from ${connection.hostname}", e)
            } catch (e: ExecutionException) {
                Timber.d("Could not execute task to get ip address from ${connection.hostname}", e)
            }
        } else {
            Timber.d("Hostname ${connection.hostname} to IP address conversion not required")
        }

        var baseUrl = "http://$hostname"
        if (connection.streamingPort != 80 && connection.streamingPort != 443) {
            baseUrl = "http://$hostname:${connection.streamingPort}"
        }

        if (!TextUtils.isEmpty(serverStatus?.webroot)) {
            baseUrl += serverStatus?.webroot
        }
        return baseUrl
    }

    fun getPlaybackUrl(convertHostname: Boolean = false, profileId: Int = 0): String {
        // If the server status is null, then use the default id of zero which will
        // return a null server profile. In this case use the default profile 'pass'
        val defaultProfile = appRepository.serverProfileData.getItemById(serverStatus?.httpPlaybackServerProfileId
                ?: 0)
        val defaultProfileName = defaultProfile?.name ?: "pass"

        // Get the playback profile for the given id. In case no profile is returned, use the default name
        val serverProfile = appRepository.serverProfileData.getItemById(profileId)
        return "${getServerUrl(convertHostname)}$path?ticket=$ticket&profile=${serverProfile?.name
                ?: defaultProfileName}"
    }
}
