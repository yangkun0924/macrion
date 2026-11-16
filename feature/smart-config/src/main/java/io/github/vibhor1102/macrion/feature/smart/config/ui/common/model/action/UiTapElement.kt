/* Copyright (C) 2026 Macrion contributors. Licensed under the GNU GPL v3 or later. */
package io.github.vibhor1102.macrion.feature.smart.config.ui.common.model.action

import android.content.Context
import androidx.annotation.DrawableRes
import io.github.vibhor1102.macrion.core.domain.model.action.TapElement
import io.github.vibhor1102.macrion.feature.smart.config.R

@DrawableRes
internal fun getTapElementIconRes(): Int = R.drawable.ic_action_tap_element

internal fun TapElement.getDescription(context: Context, inError: Boolean): String {
    if (inError) return context.getString(R.string.item_error_action_invalid_generic)

    val typeLabel = context.getString(
        when (selectorType) {
            TapElement.SelectorType.TEXT_EXACT -> R.string.item_tap_element_selector_text_exact
            TapElement.SelectorType.TEXT_CONTAINS -> R.string.item_tap_element_selector_text_contains
            TapElement.SelectorType.RESOURCE_ID -> R.string.item_tap_element_selector_resource_id
            TapElement.SelectorType.CONTENT_DESCRIPTION -> R.string.item_tap_element_selector_content_desc
        }
    )
    return context.getString(R.string.item_tap_element_details, typeLabel, selectorValue)
}
