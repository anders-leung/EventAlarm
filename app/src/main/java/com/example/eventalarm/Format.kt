package com.example.eventalarm

import android.content.Context
import android.text.format.DateUtils

object Format {

    fun dateTime(context: Context, millis: Long): String =
        DateUtils.formatDateTime(
            context,
            millis,
            DateUtils.FORMAT_SHOW_WEEKDAY or DateUtils.FORMAT_SHOW_DATE or
                DateUtils.FORMAT_SHOW_TIME or DateUtils.FORMAT_ABBREV_ALL
        )

    fun time(context: Context, millis: Long): String =
        DateUtils.formatDateTime(context, millis, DateUtils.FORMAT_SHOW_TIME)
}
