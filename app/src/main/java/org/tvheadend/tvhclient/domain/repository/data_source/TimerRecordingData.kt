package org.tvheadend.tvhclient.domain.repository.data_source

import android.os.AsyncTask

import org.tvheadend.tvhclient.data.db.AppRoomDatabase
import org.tvheadend.tvhclient.domain.entity.TimerRecording

import java.util.ArrayList
import java.util.concurrent.ExecutionException
import androidx.lifecycle.LiveData
import timber.log.Timber

class TimerRecordingData(private val db: AppRoomDatabase) : DataSourceInterface<TimerRecording> {

    override fun addItem(item: TimerRecording) {
        AsyncTask.execute { db.timerRecordingDao.insert(item) }
    }

    override fun updateItem(item: TimerRecording) {
        AsyncTask.execute { db.timerRecordingDao.update(item) }
    }

    override fun removeItem(item: TimerRecording) {
        AsyncTask.execute { db.timerRecordingDao.delete(item) }
    }

    override fun getLiveDataItemCount(): LiveData<Int>? {
        return db.timerRecordingDao.recordingCount
    }

    override fun getLiveDataItems(): LiveData<List<TimerRecording>>? {
        return db.timerRecordingDao.loadAllRecordings()
    }

    override fun getLiveDataItemById(id: Any): LiveData<TimerRecording>? {
        return db.timerRecordingDao.loadRecordingById(id as String)
    }

    override fun getItemById(id: Any): TimerRecording? {
        try {
            return TimerRecordingByIdTask(db, id as String).execute().get()
        } catch (e: InterruptedException) {
            Timber.d("Loading timer recording by id task got interrupted", e)
        } catch (e: ExecutionException) {
            Timber.d("Loading timer recording by id task aborted", e)
        }

        return null
    }

    override fun getItems(): List<TimerRecording> {
        return ArrayList()
    }

    private class TimerRecordingByIdTask internal constructor(private val db: AppRoomDatabase, private val id: String) : AsyncTask<Void, Void, TimerRecording>() {

        override fun doInBackground(vararg voids: Void): TimerRecording {
            return db.timerRecordingDao.loadRecordingByIdSync(id)
        }
    }
}