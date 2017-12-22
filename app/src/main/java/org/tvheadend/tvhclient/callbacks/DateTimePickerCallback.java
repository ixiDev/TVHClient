package org.tvheadend.tvhclient.callbacks;

public interface DateTimePickerCallback {

    void onTimeSelected(int hour, int minute, String tag);

    void onDateSelected(int year, int month, int day, String tag);
}
