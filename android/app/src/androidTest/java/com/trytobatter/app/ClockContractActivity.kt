package com.trytobatter.app

import android.app.Activity
import android.content.Intent
import android.os.Bundle
import android.os.Handler
import android.os.Looper
import android.provider.AlarmClock

/** Receives Android clock intents; does not create, cancel, or ring real alarms. */
open class ClockContractActivity : Activity() {
    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        sendBroadcast(Intent("com.trytobatter.app.CLOCK_CONTRACT").setPackage("com.trytobatter.app")
            .putExtra("action",intent.action).putExtra("label",intent.getStringExtra(AlarmClock.EXTRA_MESSAGE))
            .putExtra("hour",intent.getIntExtra(AlarmClock.EXTRA_HOUR,-1))
            .putExtra("minute",intent.getIntExtra(AlarmClock.EXTRA_MINUTES,-1))
            .putExtra("search",intent.getStringExtra(AlarmClock.EXTRA_ALARM_SEARCH_MODE)))
        Handler(Looper.getMainLooper()).postDelayed({ finish() },450)
    }
}
class ClockSingleTaskContractActivity : ClockContractActivity()
