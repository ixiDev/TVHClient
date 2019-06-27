package org.tvheadend.tvhclient.ui.features

import android.app.ActivityManager
import android.app.SearchManager
import android.content.Context
import android.content.Intent
import android.content.IntentFilter
import android.content.pm.PackageManager
import android.database.Cursor
import android.os.Bundle
import android.os.Handler
import android.view.KeyEvent
import android.view.Menu
import android.view.MenuItem
import android.view.View
import android.widget.ProgressBar
import androidx.appcompat.widget.SearchView
import androidx.appcompat.widget.Toolbar
import androidx.fragment.app.Fragment
import androidx.lifecycle.Observer
import androidx.lifecycle.ViewModelProviders
import androidx.localbroadcastmanager.content.LocalBroadcastManager
import androidx.preference.PreferenceManager
import com.google.android.gms.cast.framework.*
import org.tvheadend.tvhclient.R
import org.tvheadend.tvhclient.data.service.HtspService
import org.tvheadend.tvhclient.data.service.SyncStateReceiver
import org.tvheadend.tvhclient.ui.base.BaseActivity
import org.tvheadend.tvhclient.ui.common.NetworkStatus
import org.tvheadend.tvhclient.ui.common.getCastContext
import org.tvheadend.tvhclient.ui.common.getCastSession
import org.tvheadend.tvhclient.ui.common.showSnackbarMessage
import org.tvheadend.tvhclient.ui.features.channels.ChannelListFragment
import org.tvheadend.tvhclient.ui.features.download.DownloadPermissionGrantedInterface
import org.tvheadend.tvhclient.ui.features.dvr.recordings.*
import org.tvheadend.tvhclient.ui.features.dvr.series_recordings.SeriesRecordingDetailsFragment
import org.tvheadend.tvhclient.ui.features.dvr.series_recordings.SeriesRecordingListFragment
import org.tvheadend.tvhclient.ui.features.dvr.timer_recordings.TimerRecordingDetailsFragment
import org.tvheadend.tvhclient.ui.features.dvr.timer_recordings.TimerRecordingListFragment
import org.tvheadend.tvhclient.ui.features.epg.ProgramGuideFragment
import org.tvheadend.tvhclient.ui.features.information.StatusFragment
import org.tvheadend.tvhclient.ui.features.information.StatusViewModel
import org.tvheadend.tvhclient.ui.features.information.WebViewFragment
import org.tvheadend.tvhclient.ui.features.navigation.NavigationDrawer
import org.tvheadend.tvhclient.ui.features.navigation.NavigationDrawer.Companion.MENU_SETTINGS
import org.tvheadend.tvhclient.ui.features.navigation.NavigationViewModel
import org.tvheadend.tvhclient.ui.features.notification.showOrCancelNotificationDiskSpaceIsLow
import org.tvheadend.tvhclient.ui.features.notification.showOrCancelNotificationProgramIsCurrentlyBeingRecorded
import org.tvheadend.tvhclient.ui.features.playback.external.CastSessionManagerListener
import org.tvheadend.tvhclient.ui.features.programs.ProgramDetailsFragment
import org.tvheadend.tvhclient.ui.features.programs.ProgramListFragment
import org.tvheadend.tvhclient.ui.features.search.SearchRequestInterface
import org.tvheadend.tvhclient.ui.features.settings.SettingsActivity
import org.tvheadend.tvhclient.ui.features.unlocker.UnlockerFragment
import org.tvheadend.tvhclient.util.extensions.gone
import org.tvheadend.tvhclient.util.extensions.sendSnackbarMessage
import org.tvheadend.tvhclient.util.extensions.visible
import org.tvheadend.tvhclient.util.extensions.visibleOrGone
import org.tvheadend.tvhclient.util.getThemeId
import timber.log.Timber

// TODO make the notification ids a constant in the not...utils file

class MainActivity : BaseActivity(), SearchView.OnQueryTextListener, SearchView.OnSuggestionListener, SyncStateReceiver.Listener {

    private lateinit var navigationViewModel: NavigationViewModel
    private lateinit var statusViewModel: StatusViewModel

    private lateinit var syncProgress: ProgressBar

    private var searchMenuItem: MenuItem? = null
    private var searchView: SearchView? = null
    private var mediaRouteMenuItem: MenuItem? = null
    private var introductoryOverlay: IntroductoryOverlay? = null
    private var castSession: CastSession? = null
    private var castContext: CastContext? = null
    private var castStateListener: CastStateListener? = null
    private var castSessionManagerListener: SessionManagerListener<CastSession>? = null

    private lateinit var navigationDrawer: NavigationDrawer
    private lateinit var syncStateReceiver: SyncStateReceiver

    private var isUnlocked: Boolean = false
    private var isDualPane: Boolean = false

    private var searchQuery: String = ""
    private lateinit var queryTextSubmitTask: Runnable
    private val queryTextSubmitHandler = Handler()

    private lateinit var miniController: View

    override fun onCreate(savedInstanceState: Bundle?) {
        setTheme(getThemeId(this))
        super.onCreate(savedInstanceState)
        setContentView(R.layout.main_activity)

        Timber.d("Initializing")

        navigationViewModel = ViewModelProviders.of(this).get(NavigationViewModel::class.java)
        statusViewModel = ViewModelProviders.of(this).get(StatusViewModel::class.java)

        syncProgress = findViewById(R.id.sync_progress)
        syncStateReceiver = SyncStateReceiver(this)
        isDualPane = findViewById<View>(R.id.details) != null

        miniController = findViewById<View>(R.id.cast_mini_controller)
        miniController.gone()

        val toolbar = findViewById<Toolbar>(R.id.toolbar)
        setSupportActionBar(toolbar)
        val actionBar = supportActionBar
        if (actionBar != null) {
            actionBar.setDisplayHomeAsUpEnabled(true)
            actionBar.setHomeButtonEnabled(true)
        }

        navigationDrawer = NavigationDrawer(this, savedInstanceState, toolbar, navigationViewModel, statusViewModel)

        searchQuery = savedInstanceState?.getString(SearchManager.QUERY) ?: ""

        supportFragmentManager.addOnBackStackChangedListener {
            navigationDrawer.handleMenuSelection(supportFragmentManager.findFragmentById(R.id.main))
        }

        castContext = getCastContext(this)
        if (castContext != null) {
            Timber.d("Casting is available")
            castSessionManagerListener = CastSessionManagerListener(this, castSession)
            castStateListener = CastStateListener { newState ->
                Timber.d("Cast state changed to $newState")
                if (newState != CastState.NO_DEVICES_AVAILABLE) {
                    showIntroductoryOverlay()
                }
            }
        } else {
            Timber.d("Casting is not available, casting will no be enabled")
        }

        // Calls the method in the fragments that will initiate the actual search
        // Disable search as you type in the epg because the results
        // will be shown in a separate fragment program list.
        queryTextSubmitTask = Runnable {
            val fragment = supportFragmentManager.findFragmentById(R.id.main)
            if (fragment is SearchRequestInterface
                    && fragment.isVisible
                    && fragment !is ProgramGuideFragment) {
                fragment.onSearchRequested(searchQuery)
            }
        }

        // Observe any changes in the network availability. If the app is in the background
        // and is resumed and the network is still available the lambda function is not
        // called and nothing will be done.
        baseViewModel.networkStatus.observe(this, Observer { networkStatus ->
            Timber.d("Network status changed to $networkStatus")
            connectToServer(networkStatus)
            if (networkStatus == NetworkStatus.NETWORK_IS_DOWN) {
                sendSnackbarMessage(R.string.network_not_available)
            }
            invalidateOptionsMenu()
        })

        navigationViewModel.navigationMenuId.observe(this, Observer { id ->
            handleDrawerItemSelected(id)
        })
        statusViewModel.showRunningRecordingCount.observe(this, Observer { show ->
            showOrCancelNotificationProgramIsCurrentlyBeingRecorded(this, statusViewModel.runningRecordingCount, show)
        })
        statusViewModel.showLowStorageSpace.observe(this, Observer { show ->
            showOrCancelNotificationDiskSpaceIsLow(this, statusViewModel.availableStorageSpace, show)
        })
        baseViewModel.showSnackbar.observe(this, Observer { intent ->
            showSnackbarMessage(this, intent)
        })
        baseViewModel.isUnlocked.observe(this, Observer { unlocked ->
            Timber.d("Received live data, unlocked changed to $unlocked")
            isUnlocked = unlocked
            invalidateOptionsMenu()

            miniController.visibleOrGone(isUnlocked && sharedPreferences.getBoolean("casting_minicontroller_enabled", resources.getBoolean(R.bool.pref_default_casting_minicontroller_enabled)))
        })

        Timber.d("Done initializing")
    }

    override fun onSaveInstanceState(outState: Bundle) {
        var out = outState
        // add the values which need to be saved from the drawer and header to the bundle
        out = navigationDrawer.saveInstanceState(out)
        out.putString(SearchManager.QUERY, searchQuery)
        super.onSaveInstanceState(out)
    }

    override fun onStart() {
        super.onStart()
        LocalBroadcastManager.getInstance(this).registerReceiver(syncStateReceiver, IntentFilter(SyncStateReceiver.ACTION))
    }

    override fun onStop() {
        super.onStop()
        LocalBroadcastManager.getInstance(this).unregisterReceiver(syncStateReceiver)
    }

    override fun dispatchKeyEvent(event: KeyEvent): Boolean {
        castContext?.let {
            return it.onDispatchVolumeKeyEventBeforeJellyBean(event) || super.dispatchKeyEvent(event)
        } ?: run {
            return super.dispatchKeyEvent(event)
        }
    }

    public override fun onResume() {
        super.onResume()
        castSession = getCastSession(this)
    }

    public override fun onPause() {
        castContext?.let {
            try {
                it.removeCastStateListener(castStateListener)
                it.sessionManager.removeSessionManagerListener(castSessionManagerListener, CastSession::class.java)
            } catch (e: IllegalStateException) {
                Timber.e(e, "Could not remove cast state listener or get cast session manager")
            }
        }
        queryTextSubmitHandler.removeCallbacks(queryTextSubmitTask)
        super.onPause()
    }

    override fun onCreateOptionsMenu(menu: Menu): Boolean {
        super.onCreateOptionsMenu(menu)
        menuInflater.inflate(R.menu.main_options_menu, menu)

        mediaRouteMenuItem = menu.findItem(R.id.media_route_menu_item)
        try {
            CastButtonFactory.setUpMediaRouteButton(applicationContext, menu, R.id.media_route_menu_item)
        } catch (e: Exception) {
            Timber.e(e, "Could not setup media route button")
        }

        val searchManager = getSystemService(Context.SEARCH_SERVICE) as SearchManager
        searchMenuItem = menu.findItem(R.id.menu_search)
        searchView = searchMenuItem?.actionView as SearchView

        searchView?.let {
            it.setSearchableInfo(searchManager.getSearchableInfo(componentName))
            it.setIconifiedByDefault(true)
            it.setOnQueryTextListener(this)
            it.setOnSuggestionListener(this)

            val fragment = supportFragmentManager.findFragmentById(R.id.main)
            if (fragment is SearchRequestInterface && fragment.isVisible) {
                it.queryHint = fragment.getQueryHint()
            }
        }
        return true
    }

    private fun showIntroductoryOverlay() {
        introductoryOverlay?.remove()

        mediaRouteMenuItem?.let {
            if (it.isVisible) {
                Handler().post {
                    introductoryOverlay = IntroductoryOverlay.Builder(
                            this@MainActivity, mediaRouteMenuItem)
                            .setTitleText(getString(R.string.intro_overlay_text))
                            .setOverlayColor(R.color.primary)
                            .setSingleTime()
                            .setOnOverlayDismissedListener { introductoryOverlay = null }
                            .build()
                    introductoryOverlay?.show()
                }
            }
        }
    }

    override fun onRequestPermissionsResult(requestCode: Int, permissions: Array<String>, grantResults: IntArray) {
        super.onRequestPermissionsResult(requestCode, permissions, grantResults)
        if (grantResults.isNotEmpty()
                && grantResults[0] == PackageManager.PERMISSION_GRANTED
                && permissions.isNotEmpty()
                && permissions[0] == "android.permission.WRITE_EXTERNAL_STORAGE") {
            Timber.d("Storage permission granted")
            val fragment = supportFragmentManager.findFragmentById(if (isDualPane) R.id.details else R.id.main)
            if (fragment is DownloadPermissionGrantedInterface) {
                fragment.downloadRecording()
            }
        } else {
            Timber.d("Storage permission could not be granted")
        }
    }

    /**
     * Called when a menu item from the navigation drawer was selected. It loads
     * and shows the correct fragment or fragments depending on the selected
     * menu item.
     *
     * @param position Selected position within the menu array
     */
    private fun handleDrawerItemSelected(position: Int) {
        Timber.d("Navigation menu item selected at $position, previous position was ${navigationViewModel.previousNavigationMenuId}")
        val fragment: Fragment?
        var addFragmentToBackStack = sharedPreferences.getBoolean("navigation_history_enabled",
                resources.getBoolean(R.bool.pref_default_navigation_history_enabled))

        // Get the already created fragment when the device orientation changes. In this
        // case the saved instance is not null. This avoids recreating fragments after
        // every orientation change which would reset any saved states in these fragments.
        if (position != navigationViewModel.previousNavigationMenuId) {
            Timber.d("New navigation menu $position was selected, getting new fragment")
            fragment = getFragmentFromSelection(position)
        } else {
            Timber.d("Same navigation menu was selected, trying to get existing fragment")
            fragment = supportFragmentManager.findFragmentById(R.id.main)
            addFragmentToBackStack = false
        }

        navigationViewModel.previousNavigationMenuId = position

        // A new or existing main fragment shall be shown. So save the menu position so we
        // know which one was selected. Additionally remove any old details fragment in case
        // dual pane mode is active to prevent showing wrong details data.
        // Finally show the new main fragment and add it to the back stack
        // only if it is a new fragment and not an existing one.
        if (fragment != null) {
            if (isDualPane) {
                val detailsFragment = supportFragmentManager.findFragmentById(R.id.details)
                if (detailsFragment != null) {
                    supportFragmentManager.beginTransaction().remove(detailsFragment).commit()
                }
            }
            supportFragmentManager.beginTransaction().replace(R.id.main, fragment).let {
                if (addFragmentToBackStack) it.addToBackStack(null)
                it.commit()
            }
        } else {
            if (position == MENU_SETTINGS) {
                startActivity(Intent(this, SettingsActivity::class.java))
            }
        }
    }

    override fun onPrepareOptionsMenu(menu: Menu): Boolean {
        super.onPrepareOptionsMenu(menu)

        when (navigationViewModel.navigationMenuId.value) {
            NavigationDrawer.MENU_STATUS, NavigationDrawer.MENU_UNLOCKER, NavigationDrawer.MENU_HELP -> {
                menu.findItem(R.id.media_route_menu_item)?.isVisible = false
                menu.findItem(R.id.menu_search).isVisible = false
                menu.findItem(R.id.menu_reconnect_to_server).isVisible = false
                menu.findItem(R.id.menu_send_wake_on_lan_packet)?.isVisible = false
            }
            else -> {
                menu.findItem(R.id.media_route_menu_item)?.isVisible = isUnlocked
                menu.findItem(R.id.menu_send_wake_on_lan_packet)?.isVisible = isUnlocked && baseViewModel.connection.isWolEnabled
            }
        }
        return true
    }

    override fun onQueryTextSubmit(query: String): Boolean {
        queryTextSubmitHandler.removeCallbacks(queryTextSubmitTask)
        searchMenuItem?.collapseActionView()
        val fragment = supportFragmentManager.findFragmentById(R.id.main)
        if (fragment is SearchRequestInterface && fragment.isVisible) {
            fragment.onSearchRequested(query)
        }
        return true
    }

    override fun onQueryTextChange(newText: String): Boolean {
        searchQuery = newText
        if (newText.length >= 3) {
            Timber.d("Search query is ${newText.length} characters long, starting timer to start searching")
            queryTextSubmitHandler.removeCallbacks(queryTextSubmitTask)
            queryTextSubmitHandler.postDelayed(queryTextSubmitTask, 2000)
        }
        return true
    }

    override fun onSuggestionSelect(position: Int): Boolean {
        return false
    }

    override fun onSuggestionClick(position: Int): Boolean {
        searchMenuItem?.collapseActionView()
        // Set the search query and return true so that the onQueryTextSubmit
        // is called. This is required to pass additional data to the search activity
        val cursor = searchView?.suggestionsAdapter?.getItem(position) as Cursor?
        cursor?.let {
            val suggestion = cursor.getString(cursor.getColumnIndex(SearchManager.SUGGEST_COLUMN_TEXT_1))
            searchView?.setQuery(suggestion, true)
        }
        return true
    }

    override fun onSyncStateChanged(state: SyncStateReceiver.State, message: String, details: String) {
        when (state) {
            SyncStateReceiver.State.CLOSED, SyncStateReceiver.State.FAILED -> {
                Timber.d("Connection failed or closed")
                sendSnackbarMessage(message)
                baseViewModel.setNetworkIsAvailable(false)
            }

            SyncStateReceiver.State.CONNECTING -> {
                Timber.d("Connecting")
                sendSnackbarMessage(message)
            }

            SyncStateReceiver.State.CONNECTED -> {
                Timber.d("Connected")
                sendSnackbarMessage(message)
            }

            SyncStateReceiver.State.SYNC_STARTED -> {
                Timber.d("Sync started, showing progress bar")
                syncProgress.visible()
                sendSnackbarMessage(message)
            }

            SyncStateReceiver.State.SYNC_IN_PROGRESS -> {
                Timber.d("Sync in progress, updating progress bar")
                syncProgress.visible()
            }

            SyncStateReceiver.State.SYNC_DONE -> {
                Timber.d("Sync done, hiding progress bar")
                syncProgress.gone()
                sendSnackbarMessage(message)
            }
            else -> {
            }
        }
    }

    private fun connectToServer(status: NetworkStatus) {
        val activityManager = getSystemService(Context.ACTIVITY_SERVICE) as ActivityManager
        val runningAppProcessInfo = activityManager.runningAppProcesses?.get(0)
        val intent = Intent(this, HtspService::class.java)

        if (runningAppProcessInfo != null
                && runningAppProcessInfo.importance <= ActivityManager.RunningAppProcessInfo.IMPORTANCE_FOREGROUND) {

            if (status == NetworkStatus.NETWORK_IS_UP) {
                Timber.d("Starting server because network is up again")
                intent.action = "connect"
                startService(intent)
            }
            if (status == NetworkStatus.NETWORK_IS_STILL_UP) {
                Timber.d("Reconnecting to server because network is still up")
                intent.action = "reconnect"
                startService(intent)
            }
            if (status == NetworkStatus.NETWORK_IS_DOWN) {
                Timber.d("Stopping service because network is down")
                stopService(intent)
            }
        }
    }

    override fun onBackPressed() {
        val navigationHistoryEnabled = PreferenceManager.getDefaultSharedPreferences(this).getBoolean("navigation_history_enabled", resources.getBoolean(R.bool.pref_default_navigation_history_enabled))
        if (!navigationHistoryEnabled) {
            val fragment = supportFragmentManager.findFragmentById(R.id.main)
            if (fragment is ProgramListFragment
                    || fragment is ProgramDetailsFragment
                    || fragment is RecordingDetailsFragment
                    || fragment is SeriesRecordingDetailsFragment
                    || fragment is TimerRecordingDetailsFragment) {
                // Do not finish the activity in case any of these fragments
                // are visible which were called from the channel list fragment.
                clearSearchResultsOrPopBackStack()
            } else {
                finish()
            }
        } else {
            if (supportFragmentManager.backStackEntryCount <= 1) {
                finish()
            } else {
                // The last fragment on the stack is visible
                clearSearchResultsOrPopBackStack()
            }
        }
    }

    /**
     * Pops the back stack to go back to the previous fragment or
     * in case a search was active, clears the search results.
     * After that a new back press can finish the activity.
     */
    private fun clearSearchResultsOrPopBackStack() {
        val fragment = supportFragmentManager.findFragmentById(R.id.main)
        if (fragment is SearchRequestInterface && fragment.isVisible) {
            if (!fragment.onSearchResultsCleared()) {
                super.onBackPressed()
            }
        } else {
            super.onBackPressed()
        }
    }

    /**
     * Creates and returns a new fragment that is associated with the given menu
     */
    private fun getFragmentFromSelection(position: Int): Fragment? {
        var fragment: Fragment? = null
        val bundle = Bundle()
        when (position) {
            NavigationDrawer.MENU_CHANNELS -> fragment = ChannelListFragment()
            NavigationDrawer.MENU_PROGRAM_GUIDE -> fragment = ProgramGuideFragment()
            NavigationDrawer.MENU_COMPLETED_RECORDINGS -> fragment = CompletedRecordingListFragment()
            NavigationDrawer.MENU_SCHEDULED_RECORDINGS -> fragment = ScheduledRecordingListFragment()
            NavigationDrawer.MENU_SERIES_RECORDINGS -> fragment = SeriesRecordingListFragment()
            NavigationDrawer.MENU_TIMER_RECORDINGS -> fragment = TimerRecordingListFragment()
            NavigationDrawer.MENU_FAILED_RECORDINGS -> fragment = FailedRecordingListFragment()
            NavigationDrawer.MENU_REMOVED_RECORDINGS -> fragment = RemovedRecordingListFragment()
            NavigationDrawer.MENU_STATUS -> fragment = StatusFragment()
            NavigationDrawer.MENU_UNLOCKER -> {
                fragment = UnlockerFragment()
                bundle.putString("website", "features")
                fragment.arguments = bundle
            }
            NavigationDrawer.MENU_HELP -> {
                fragment = WebViewFragment()
                bundle.putString("website", "help_and_support")
                fragment.arguments = bundle
            }
        }
        return fragment
    }
}
