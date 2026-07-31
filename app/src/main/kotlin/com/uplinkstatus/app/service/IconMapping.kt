package com.uplinkstatus.app.service

import androidx.annotation.DrawableRes
import com.uplinkstatus.app.R
import com.uplinkstatus.core.tracer.BarPosition

/**
 * Maps each [BarPosition] to its corresponding status-bar vector drawable (moved into
 * `res/drawable` from `assets/media/icons/` for this stage — see the six `ic_scan_*`
 * resources). [disabledIconRes] is the sixth frame: "all bars dim," shown for
 * [com.uplinkstatus.core.visibility.UplinkVisibility.DISABLED] — never for `HIDDEN`,
 * which per spec is the absence of the icon entirely, not a seventh frame.
 */
@DrawableRes
fun iconResFor(position: BarPosition): Int = when (position) {
    BarPosition.BAR_1 -> R.drawable.ic_scan_1
    BarPosition.BAR_2 -> R.drawable.ic_scan_2
    BarPosition.BAR_3 -> R.drawable.ic_scan_3
    BarPosition.BAR_4 -> R.drawable.ic_scan_4
    BarPosition.BAR_5 -> R.drawable.ic_scan_5
}

@DrawableRes
val disabledIconRes: Int = R.drawable.ic_scan_disabled
