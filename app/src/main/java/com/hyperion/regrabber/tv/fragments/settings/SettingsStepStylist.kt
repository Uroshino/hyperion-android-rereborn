package com.hyperion.regrabber.tv.fragments.settings

import androidx.leanback.widget.GuidanceStylist
import com.hyperion.regrabber.R

class SettingsStepStylist : GuidanceStylist() {
    override fun onProvideLayoutId(): Int {
        return R.layout.settings_guidance
    }
}