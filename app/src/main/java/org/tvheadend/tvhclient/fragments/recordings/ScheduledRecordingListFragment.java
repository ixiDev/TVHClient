package org.tvheadend.tvhclient.fragments.recordings;

import android.os.Bundle;
import android.view.Menu;

import org.tvheadend.tvhclient.Constants;
import org.tvheadend.tvhclient.DataStorage;
import org.tvheadend.tvhclient.R;
import org.tvheadend.tvhclient.TVHClientApplication;
import org.tvheadend.tvhclient.htsp.HTSListener;
import org.tvheadend.tvhclient.model.Recording;

import java.util.Map;

public class ScheduledRecordingListFragment extends RecordingListFragment implements HTSListener {

    @Override
    public void onActivityCreated(Bundle savedInstanceState) {
        super.onActivityCreated(savedInstanceState);
        toolbarInterface.setActionBarTitle(getString(R.string.scheduled_recordings));
    }

    @Override
    public void onResume() {
        super.onResume();
        TVHClientApplication.getInstance().addListener(this);
        setListShown(!DataStorage.getInstance().isLoading());

        if (!DataStorage.getInstance().isLoading()) {
            populateList();
            // In dual-pane mode the list of programs of the selected
            // channel will be shown additionally in the details view
            if (isDualPane && adapter.getCount() > 0) {
                showRecordingDetails(selectedListPosition);
            }
        }
    }

    @Override
    public void onPause() {
        super.onPause();
        TVHClientApplication.getInstance().removeListener(this);
    }

    @Override
    public void onPrepareOptionsMenu(Menu menu) {
        super.onPrepareOptionsMenu(menu);
        menu.findItem(R.id.menu_add).setVisible(isUnlocked);
    }

    @Override
    protected void populateList() {
        // Clear the list and add the recordings
        adapter.clear();
        Map<Integer, Recording> map = DataStorage.getInstance().getRecordingsFromArray();
        for (Recording recording : map.values()) {
            if (recording.isScheduled()) {
                adapter.add(recording);
            }
        }
        super.populateList();
    }

    @Override
    public void onMessage(String action, final Object obj) {
        if (action.equals(Constants.ACTION_LOADING)) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    boolean loading = (Boolean) obj;
                    setListShown(!loading);
                    if (!loading) {
                        populateList();
                    }
                }
            });
        } else if (action.equals("dvrEntryAdd")
                || action.equals("dvrEntryUpdate")
                || action.equals("dvrEntryDelete")) {
            activity.runOnUiThread(new Runnable() {
                public void run() {
                    Recording recording = (Recording) obj;
                    if (recording.isScheduled() || recording.isRecording()) {
                        handleAdapterChanges(action, recording);
                    }
                }
            });
        }
    }
}
