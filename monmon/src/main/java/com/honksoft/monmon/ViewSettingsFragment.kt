package com.honksoft.monmon

import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.view.ViewGroup
import androidx.datastore.preferences.core.edit
import androidx.fragment.app.DialogFragment
import androidx.fragment.app.Fragment
import androidx.lifecycle.lifecycleScope
import com.google.android.material.slider.Slider
import com.honksoft.monmon.databinding.FragmentViewSettingsBinding
import kotlinx.coroutines.launch

/**
 * A simple [Fragment] subclass.
 * Use the [ViewSettingsFragment.newInstance] factory method to
 * create an instance of this fragment.
 */
class ViewSettingsFragment : DialogFragment() {
  private var _binding: FragmentViewSettingsBinding? = null
  private val binding get() = _binding!!

  override fun onCreate(savedInstanceState: Bundle?) {
    super.onCreate(savedInstanceState)
    setStyle(STYLE_NORMAL, R.style.TransparentDialog)
  }

  override fun onCreateView(
    inflater: LayoutInflater, container: ViewGroup?,
    savedInstanceState: Bundle?
  ): View? {
    // Inflate the layout for this fragment
    _binding = FragmentViewSettingsBinding.inflate(inflater, container, false)
    return binding.root
  }

  override fun onViewCreated(view: View, savedInstanceState: Bundle?) {
    super.onViewCreated(view, savedInstanceState)
    lifecycleScope.launch {
      requireContext().dataStore.data.collect { prefs ->
        val peakThreshold = prefs[PreferenceKeys.PEAK_THRESHOLD] ?: 40
        val peakVisibility =
          prefs[PreferenceKeys.PEAK_VISIBILITY] ?: PrefsPeakVisiblityType.OVERLAY.ordinal
        val peakColor = prefs[PreferenceKeys.PEAK_COLOR] ?: PrefsPeakColorType.RED.ordinal
        val reduceRes = prefs[PreferenceKeys.REDUCED_RES] ?: false

        binding.thresholdSlider.value = peakThreshold.toFloat()

        when (PrefsPeakVisiblityType.entries[peakVisibility]) {
          PrefsPeakVisiblityType.OVERLAY -> binding.peakVisOverlay.isChecked = true
          PrefsPeakVisiblityType.OFF -> binding.peakVisOff.isChecked = true
          PrefsPeakVisiblityType.EDGES_ONLY -> binding.peakVisEdges.isChecked = true
        }

        when (PrefsPeakColorType.entries[peakColor]) {
          PrefsPeakColorType.RED -> binding.peakColorRed.isChecked = true
          PrefsPeakColorType.GREEN -> binding.peakColorGreen.isChecked = true
          PrefsPeakColorType.BLUE -> binding.peakColorBlue.isChecked = true
          PrefsPeakColorType.YELLOW -> binding.peakColorYellow.isChecked = true
          PrefsPeakColorType.WHITE -> binding.peakColorWhite.isChecked = true
        }

        binding.reduceRes.isChecked = reduceRes
      }
    }

    // Add change listeners to save
    binding.thresholdSlider.addOnSliderTouchListener(object : Slider.OnSliderTouchListener {
      override fun onStartTrackingTouch(slider: Slider) {
      }

      override fun onStopTrackingTouch(slider: Slider) {
        // SAVE ONLY NOW
        lifecycleScope.launch {
          requireContext().dataStore.edit { prefs ->
            prefs[PreferenceKeys.PEAK_THRESHOLD] = slider.value.toInt()
          }
        }
      }
    })

    binding.radioGroupPeakVis.setOnCheckedChangeListener { group, checkedId ->
      val selected = when (checkedId) {
        R.id.peak_vis_overlay -> PrefsPeakVisiblityType.OVERLAY
        R.id.peak_vis_off -> PrefsPeakVisiblityType.OFF
        R.id.peak_vis_edges -> PrefsPeakVisiblityType.EDGES_ONLY
        else -> PrefsPeakVisiblityType.OVERLAY
      }

      lifecycleScope.launch {
        requireContext().dataStore.edit { prefs ->
          prefs[PreferenceKeys.PEAK_VISIBILITY] = selected.ordinal
        }
      }
    }

    binding.radioGroupPeakColor.setOnCheckedChangeListener { group, checkedId ->
      val selected = when (checkedId) {
        R.id.peak_color_red -> PrefsPeakColorType.RED
        R.id.peak_color_green -> PrefsPeakColorType.GREEN
        R.id.peak_color_blue -> PrefsPeakColorType.BLUE
        R.id.peak_color_yellow -> PrefsPeakColorType.YELLOW
        R.id.peak_color_white -> PrefsPeakColorType.WHITE
        else -> PrefsPeakColorType.RED
      }
      lifecycleScope.launch {
        requireContext().dataStore.edit { prefs ->
          prefs[PreferenceKeys.PEAK_COLOR] = selected.ordinal
        }
      }
    }

    binding.reduceRes.setOnCheckedChangeListener { _, isChecked ->
      lifecycleScope.launch {
        requireContext().dataStore.edit { prefs ->
          prefs[PreferenceKeys.REDUCED_RES] = isChecked
        }
      }
    }
  }

  override fun onDestroyView() {
    super.onDestroyView()
    // 4. Clean up the binding to prevent memory leaks
    _binding = null
  }

  companion object {
    /**
     * Use this factory method to create a new instance of
     * this fragment using the provided parameters.
     *
     * @param param1 Parameter 1.
     * @param param2 Parameter 2.
     * @return A new instance of fragment ViewSettingsFragment.
     */
    // TODO: Rename and change types and number of parameters
    @JvmStatic
    fun newInstance(param1: String, param2: String) =
      ViewSettingsFragment().apply {
      }
  }
}