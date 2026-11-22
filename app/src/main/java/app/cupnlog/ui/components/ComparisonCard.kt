package app.cupnlog.ui.components

import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.aspectRatio
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.OutlinedCard
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import app.cupnlog.data.CoffeeEntry
import app.cupnlog.data.CupRating
import app.cupnlog.data.RatingAverages
import app.cupnlog.data.RatingField
import app.cupnlog.data.isRated
import app.cupnlog.data.scoreFor

/**
 * Rater-aware comparison for a done session's detail screen (phase 4). Per
 * cup: a bold average of the manual Overall scores, one box per rater with its
 * score (or a quiet dash), then the viewer's own six attribute chips and notes.
 * Built rater-aware from day one so a future transport slots in without a
 * redesign.
 *
 * Blind sessions that have not been revealed pass [cupLabel] / [cupColor] that
 * render positions only ("Cup N", a neutral swatch) — never a name, label or
 * colour from the plan. Revealed (and non-blind) rows use the default identity.
 */
@Composable
fun ComparisonCard(
    coffees: List<CoffeeEntry>,
    ratingsByCup: Map<Int, List<CupRating>>,
    /** (raterId, displayName) in a stable order. */
    raterOrder: List<Pair<String, String>>,
    myId: String,
    title: String = "Group comparison",
    cupLabel: ((Int) -> String)? = null,
    cupColor: ((Int) -> Long)? = null,
) {
    OutlinedCard(modifier = Modifier.fillMaxWidth()) {
        Column(modifier = Modifier.fillMaxWidth().padding(16.dp)) {
            Text(
                title,
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.SemiBold,
            )
            Spacer(modifier = Modifier.height(12.dp))
            coffees.forEachIndexed { index, coffee ->
                CupComparisonRow(
                    label = cupLabel?.invoke(index) ?: coffee.displayName,
                    colorArgb = cupColor?.invoke(index) ?: coffee.colorArgb,
                    insideText = cupLabel?.let { "" } ?: coffee.displayLabel,
                    entries = ratingsByCup[index].orEmpty(),
                    raterOrder = raterOrder,
                    myId = myId,
                )
                if (index != coffees.size - 1) {
                    Spacer(modifier = Modifier.height(16.dp))
                }
            }
        }
    }
}

@Composable
private fun CupComparisonRow(
    label: String,
    colorArgb: Long,
    insideText: String,
    entries: List<CupRating>,
    raterOrder: List<Pair<String, String>>,
    myId: String,
) {
    val average = RatingAverages.averageOverall(entries)
    val mine = entries.firstOrNull { it.raterId == myId }
    Column(modifier = Modifier.fillMaxWidth()) {
        Row(verticalAlignment = Alignment.CenterVertically) {
            SwatchCircle(
                colorArgb = colorArgb,
                insideText = insideText,
                size = 40.dp,
            )
            Spacer(modifier = Modifier.width(12.dp))
            Text(
                label,
                style = MaterialTheme.typography.bodyLarge,
                fontWeight = FontWeight.SemiBold,
                modifier = Modifier.weight(1f),
            )
        }
        Spacer(modifier = Modifier.height(8.dp))
        // Average first, then one overall box per rater (scrollable if many).
        Row(
            modifier = Modifier
                .fillMaxWidth()
                .horizontalScroll(rememberScrollState()),
            verticalAlignment = Alignment.CenterVertically,
            horizontalArrangement = Arrangement.spacedBy(6.dp),
        ) {
            Surface(
                shape = RoundedCornerShape(10.dp),
                color = MaterialTheme.colorScheme.primaryContainer,
                contentColor = MaterialTheme.colorScheme.onPrimaryContainer,
                modifier = Modifier.width(72.dp),
            ) {
                Column(
                    modifier = Modifier
                        .fillMaxWidth()
                        .padding(horizontal = 10.dp, vertical = 6.dp),
                    horizontalAlignment = Alignment.CenterHorizontally,
                ) {
                    Text(
                        RatingAverages.formatAverage(average),
                        style = MaterialTheme.typography.titleMedium,
                        fontWeight = FontWeight.Bold,
                    )
                    Text("Average", style = MaterialTheme.typography.labelSmall)
                }
            }
            raterOrder.forEach { (raterId, raterName) ->
                val rating = entries.firstOrNull { it.raterId == raterId }
                OverallBox(
                    name = raterName.ifBlank { "Rater" },
                    score = rating?.overall,
                    isMine = raterId == myId,
                )
            }
        }
        Spacer(modifier = Modifier.height(8.dp))
        if (mine != null && mine.isRated) {
            Row(
                modifier = Modifier.fillMaxWidth(),
                horizontalArrangement = Arrangement.spacedBy(4.dp),
            ) {
                RatingField.entries
                    .filter { it != RatingField.OVERALL }
                    .forEach { field ->
                        MiniChip(
                            label = field.label,
                            score = mine.scoreFor(field),
                            modifier = Modifier.weight(1f),
                        )
                    }
            }
            if (mine.notes.isNotBlank()) {
                Spacer(modifier = Modifier.height(6.dp))
                Text(mine.notes, style = MaterialTheme.typography.bodySmall)
            }
        } else {
            Text(
                "No rating",
                style = MaterialTheme.typography.bodySmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
            )
        }
    }
}

/** One rater's Overall box (score or a quiet dash) with the rater's name. */
@Composable
private fun OverallBox(name: String, score: Int?, isMine: Boolean) {
    Surface(
        shape = RoundedCornerShape(10.dp),
        color = if (isMine) {
            MaterialTheme.colorScheme.secondaryContainer
        } else {
            MaterialTheme.colorScheme.surfaceVariant
        },
        contentColor = if (isMine) {
            MaterialTheme.colorScheme.onSecondaryContainer
        } else {
            MaterialTheme.colorScheme.onSurfaceVariant
        },
        modifier = Modifier.width(64.dp),
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(vertical = 6.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
        ) {
            Text(
                score?.toString() ?: "\u2014",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
            )
            Text(
                name,
                style = MaterialTheme.typography.labelSmall,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}

/** One attribute chip: the score (or a quiet dash) with its label underneath. */
@Composable
private fun MiniChip(label: String, score: Int?, modifier: Modifier = Modifier) {
    Surface(
        shape = RoundedCornerShape(8.dp),
        color = MaterialTheme.colorScheme.surfaceVariant,
        contentColor = MaterialTheme.colorScheme.onSurfaceVariant,
        modifier = modifier.aspectRatio(1f),
    ) {
        Column(
            modifier = Modifier.fillMaxSize(),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.Center,
        ) {
            Text(
                score?.toString() ?: "\u2014",
                style = MaterialTheme.typography.titleMedium,
                fontWeight = FontWeight.Bold,
                color = MaterialTheme.colorScheme.onSurface,
            )
            Text(
                label,
                fontSize = 9.sp,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis,
            )
        }
    }
}