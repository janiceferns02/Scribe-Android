// SPDX-License-Identifier: AGPL-3.0-or-later

package be.scri.ui.common.appcomponents

import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.TextStyle
import androidx.compose.ui.text.font.FontWeight
import be.scri.ui.theme.ScribeTypography

@Composable
fun PageTitle(
    pageTitle: String,
    modifier: Modifier = Modifier,
) {
    Text(
        text = pageTitle,
        fontSize = ScribeTypography.headlineLarge.fontSize,
        style =
            TextStyle.Default.copy(
                fontStyle = ScribeTypography.headlineMedium.fontStyle,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onBackground,
            ),
        modifier = modifier,
    )
}
