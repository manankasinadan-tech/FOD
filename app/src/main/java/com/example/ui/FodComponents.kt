package com.example.ui

import androidx.compose.animation.core.LinearEasing
import androidx.compose.animation.core.RepeatMode
import androidx.compose.animation.core.animateFloat
import androidx.compose.animation.core.infiniteRepeatable
import androidx.compose.animation.core.rememberInfiniteTransition
import androidx.compose.animation.core.tween
import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.Canvas
import androidx.compose.foundation.horizontalScroll
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.ModalBottomSheet
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.runtime.getValue
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Brush
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.PathEffect
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.graphics.vector.ImageVector
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextOverflow
import androidx.compose.ui.unit.dp
import com.example.model.EnginePhase
import com.example.model.EvidenceLevel
import com.example.model.StageStatus
import com.example.ui.theme.AmberContainer
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonBreak
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.CyanTelemetryContainer
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurface
import com.example.ui.theme.SlateSurfaceHigh

@Composable
fun FodTelemetryCanvas(
    phase: EnginePhase,
    modifier: Modifier = Modifier
) {
    val transition = rememberInfiniteTransition(label = "fod_radar")
    val pulse by transition.animateFloat(
        initialValue = 0f,
        targetValue = 1f,
        animationSpec = infiniteRepeatable(
            animation = tween(durationMillis = if (phase == EnginePhase.INVESTIGATING) 1600 else 3200, easing = LinearEasing),
            repeatMode = RepeatMode.Restart
        ),
        label = "pulse_ring"
    )

    val accent = when (phase) {
        EnginePhase.RECONSTRUCTED -> EmeraldVerified
        EnginePhase.INVESTIGATING -> CyanTelemetry
        EnginePhase.PAUSED -> AmberWarning
        else -> CyanTelemetry
    }

    Canvas(modifier = modifier) {
        val cx = size.width / 2f
        val cy = size.height / 2f
        val maxR = size.minDimension * 0.45f

        drawCircle(
            brush = Brush.radialGradient(
                colors = listOf(accent.copy(alpha = 0.22f), Color.Transparent),
                center = Offset(cx, cy),
                radius = maxR * 1.15f
            ),
            radius = maxR * 1.15f,
            center = Offset(cx, cy)
        )

        // Anneaux concentriques fixes (empreinte / capteur FOD)
        listOf(0.32f, 0.56f, 0.80f).forEach { factor ->
            drawCircle(
                color = accent.copy(alpha = 0.28f),
                radius = maxR * factor,
                center = Offset(cx, cy),
                style = Stroke(
                    width = 2f,
                    pathEffect = PathEffect.dashPathEffect(floatArrayOf(10f, 8f), 0f)
                )
            )
        }

        // Onde active uniquement si surveillance ou investigation
        if (phase != EnginePhase.PAUSED) {
            val animRadius = maxR * (0.25f + pulse * 0.72f)
            val animAlpha = (1f - pulse).coerceIn(0f, 1f) * 0.65f
            drawCircle(
                color = accent.copy(alpha = animAlpha),
                radius = animRadius,
                center = Offset(cx, cy),
                style = Stroke(width = 3f)
            )
        }

        // Réticule de précision oscilloscope
        drawLine(
            color = accent.copy(alpha = 0.35f),
            start = Offset(cx - maxR * 0.95f, cy),
            end = Offset(cx + maxR * 0.95f, cy),
            strokeWidth = 1.5f
        )
        drawLine(
            color = accent.copy(alpha = 0.35f),
            start = Offset(cx, cy - maxR * 0.95f),
            end = Offset(cx, cy + maxR * 0.95f),
            strokeWidth = 1.5f
        )

        drawCircle(
            color = accent,
            radius = 6f,
            center = Offset(cx, cy)
        )
    }
}

@Composable
fun EvidenceBadge(
    level: EvidenceLevel,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (level) {
        EvidenceLevel.FAIT_VERIFIE -> EmeraldContainer to EmeraldVerified
        EvidenceLevel.CORRELATION_OBSERVEE -> CyanTelemetryContainer to CyanTelemetry
        EvidenceLevel.NON_OBSERVE_LIMITATION -> AmberContainer to AmberWarning
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Text(
            text = level.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun StageStatusBadge(
    status: StageStatus,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when (status) {
        StageStatus.VERIFIED_ACTIVE -> EmeraldContainer to EmeraldVerified
        StageStatus.CORRELATED -> CyanTelemetryContainer to CyanTelemetry
        StageStatus.FIRST_BREAK -> CrimsonContainer to CrimsonBreak
        StageStatus.UNOBSERVED_LIMITED -> AmberContainer to AmberWarning
        StageStatus.MISSING_OR_REPLACED -> SlateSurfaceHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        color = bg,
        shape = RoundedCornerShape(6.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.45f)),
        modifier = modifier
    ) {
        Text(
            text = status.label,
            style = MaterialTheme.typography.labelSmall,
            color = fg,
            modifier = Modifier.padding(horizontal = 8.dp, vertical = 3.dp)
        )
    }
}

@Composable
fun CapabilityPill(
    label: String,
    icon: ImageVector,
    active: Boolean,
    warningWhenInactive: Boolean = false,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val (bg, fg) = when {
        active -> EmeraldContainer to EmeraldVerified
        warningWhenInactive -> AmberContainer to AmberWarning
        else -> SlateSurfaceHigh to MaterialTheme.colorScheme.onSurfaceVariant
    }
    Surface(
        onClick = onClick,
        color = bg,
        shape = RoundedCornerShape(10.dp),
        border = BorderStroke(1.dp, fg.copy(alpha = 0.4f)),
        modifier = modifier
    ) {
        Row(
            verticalAlignment = Alignment.CenterVertically,
            modifier = Modifier.padding(horizontal = 10.dp, vertical = 8.dp)
        ) {
            Icon(
                imageVector = icon,
                contentDescription = label,
                tint = fg,
                modifier = Modifier.size(16.dp)
            )
            Spacer(modifier = Modifier.width(6.dp))
            Text(
                text = label,
                style = MaterialTheme.typography.labelMedium,
                color = fg,
                maxLines = 1,
                overflow = TextOverflow.Ellipsis
            )
        }
    }
}

@Composable
fun ForensicCard(
    modifier: Modifier = Modifier,
    borderColor: Color = SlateOutline,
    containerColor: Color = SlateSurface,
    content: @Composable () -> Unit
) {
    Surface(
        color = containerColor,
        shape = RoundedCornerShape(16.dp),
        border = BorderStroke(1.dp, borderColor),
        modifier = modifier
    ) {
        Box(modifier = Modifier.padding(16.dp)) {
            content()
        }
    }
}

data class InspectedProof(
    val title: String,
    val subtitle: String,
    val level: EvidenceLevel,
    val rawLines: List<String>
)

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun ProofDetailBottomSheet(
    proof: InspectedProof,
    onDismiss: () -> Unit
) {
    ModalBottomSheet(
        onDismissRequest = onDismiss,
        containerColor = SlateSurface
    ) {
        Column(
            modifier = Modifier
                .fillMaxWidth()
                .padding(horizontal = 20.dp)
                .padding(bottom = 28.dp)
                .verticalScroll(rememberScrollState()),
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            EvidenceBadge(level = proof.level)
            Text(
                text = proof.title,
                style = MaterialTheme.typography.headlineSmall,
                color = MaterialTheme.colorScheme.onSurface
            )
            if (proof.subtitle.isNotBlank()) {
                Text(
                    text = proof.subtitle,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
            }
            Text(
                text = "Preuves brutes observées",
                style = MaterialTheme.typography.titleSmall,
                fontWeight = FontWeight.SemiBold,
                color = CyanTelemetry
            )
            Surface(
                color = MaterialTheme.colorScheme.surfaceContainerLowest,
                shape = RoundedCornerShape(12.dp),
                border = BorderStroke(1.dp, SlateOutline),
                modifier = Modifier.fillMaxWidth()
            ) {
                Column(
                    modifier = Modifier
                        .padding(12.dp)
                        .horizontalScroll(rememberScrollState()),
                    verticalArrangement = Arrangement.spacedBy(6.dp)
                ) {
                    if (proof.rawLines.isEmpty()) {
                        Text(
                            text = "Aucune donnée brute complémentaire.",
                            style = MaterialTheme.typography.bodySmall,
                            color = MaterialTheme.colorScheme.onSurfaceVariant
                        )
                    } else {
                        proof.rawLines.forEach { line ->
                            Text(
                                text = line,
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(8.dp))
        }
    }
}
