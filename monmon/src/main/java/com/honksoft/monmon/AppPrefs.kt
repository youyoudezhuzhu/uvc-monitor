package com.honksoft.monmon

import android.content.Context
import androidx.datastore.preferences.core.booleanPreferencesKey
import androidx.datastore.preferences.core.intPreferencesKey
import androidx.datastore.preferences.preferencesDataStore

enum class PrefsPeakVisiblityType {
  OVERLAY,
  OFF,
  EDGES_ONLY
}

enum class PrefsPeakColorType {
  RED,
  GREEN,
  BLUE,
  YELLOW,
  WHITE
}

val Context.dataStore by preferencesDataStore(name = "user_settings")

object PreferenceKeys {
  val PEAK_THRESHOLD = intPreferencesKey("peak_threshold")
  val PEAK_VISIBILITY = intPreferencesKey("peak_visibility")
  val PEAK_COLOR = intPreferencesKey("peak_color")
  val REDUCED_RES = booleanPreferencesKey("reduced_res")
}