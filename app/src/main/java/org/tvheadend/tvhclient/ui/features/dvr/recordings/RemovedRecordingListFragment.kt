package org.tvheadend.tvhclient.ui.features.dvr.recordings

import android.os.Bundle
import org.tvheadend.tvhclient.R
import timber.log.Timber

class RemovedRecordingListFragment : RecordingListFragment() {

    override fun onActivityCreated(savedInstanceState: Bundle?) {
        super.onActivityCreated(savedInstanceState)

        Timber.d("Observing recordings")
        recordingViewModel.removedRecordings.observe(viewLifecycleOwner,  { recordings ->
            Timber.d("View model returned ${recordings.size} recordings")
            addRecordingsAndUpdateUI(recordings)
        })
    }

    override fun getQueryHint(): String {
        return getString(R.string.search_removed_recordings)
    }

    override fun showStatusInToolbar() {
        context?.let {
            if (!baseViewModel.isSearchActive) {
                toolbarInterface.setTitle(getString(R.string.removed_recordings))
                toolbarInterface.setSubtitle(it.resources.getQuantityString(R.plurals.items, recyclerViewAdapter.itemCount, recyclerViewAdapter.itemCount))
            } else {
                toolbarInterface.setTitle(getString(R.string.search_results))
                toolbarInterface.setSubtitle(it.resources.getQuantityString(R.plurals.removed_recordings, recyclerViewAdapter.itemCount, recyclerViewAdapter.itemCount))
            }
        }
    }
}
