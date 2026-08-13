package com.anastasiia.appblocker.core

import android.content.Context
import androidx.datastore.preferences.preferencesDataStore

val Context.blockerDataStore by preferencesDataStore(name = "blocker_state")
