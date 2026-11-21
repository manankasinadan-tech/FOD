package com.example.ui

import androidx.compose.foundation.BorderStroke
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.Arrangement
import androidx.compose.foundation.layout.Box
import androidx.compose.foundation.layout.Column
import androidx.compose.foundation.layout.PaddingValues
import androidx.compose.foundation.layout.Row
import androidx.compose.foundation.layout.Spacer
import androidx.compose.foundation.layout.fillMaxSize
import androidx.compose.foundation.layout.fillMaxWidth
import androidx.compose.foundation.layout.height
import androidx.compose.foundation.layout.padding
import androidx.compose.foundation.layout.size
import androidx.compose.foundation.layout.width
import androidx.compose.foundation.layout.widthIn
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.CircleShape
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.CheckCircle
import androidx.compose.material.icons.filled.Error
import androidx.compose.material.icons.filled.Hub
import androidx.compose.material3.Icon
import androidx.compose.material3.MaterialTheme
import androidx.compose.material3.Surface
import androidx.compose.material3.Text
import androidx.compose.runtime.Composable
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.unit.dp
import com.example.engine.FodEngineState
import com.example.model.MechanismStageNode
import com.example.model.ReconstructionOutcome
import com.example.model.StageStatus
import com.example.ui.theme.AmberWarning
import com.example.ui.theme.CrimsonBreak
import com.example.ui.theme.CrimsonContainer
import com.example.ui.theme.CyanTelemetry
import com.example.ui.theme.EmeraldContainer
import com.example.ui.theme.EmeraldVerified
import com.example.ui.theme.SlateOutline
import com.example.ui.theme.SlateSurfaceElevated

@Composable
fun MechanismScreen(
    state: FodEngineState,
    onInspectStage: (MechanismStageNode) -> Unit,
    modifier: Modifier = Modifier
) {
    val rec = state.reconstruction

    LazyColumn(
        modifier = modifier.fillMaxSize(),
        contentPadding = PaddingValues(16.dp),
        verticalArrangement = Arrangement.spacedBy(14.dp),
        horizontalAlignment = Alignment.CenterHorizontally
    ) {
        item {
            ForensicCard(
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                borderColor = when (rec?.outcome) {
                    ReconstructionOutcome.ENROLEMENT_REUSSI -> EmeraldVerified.copy(alpha = 0.65f)
                    ReconstructionOutcome.ECHEC_DETECTE -> CrimsonBreak.copy(alpha = 0.7f)
                    else -> SlateOutline
                }
            ) {
                Column(verticalArrangement = Arrangement.spacedBy(10.dp)) {
                    Row(
                        verticalAlignment = Alignment.CenterVertically,
                        horizontalArrangement = Arrangement.spacedBy(8.dp)
                    ) {
                        Icon(
                            imageVector = when (rec?.outcome) {
                                ReconstructionOutcome.ENROLEMENT_REUSSI -> Icons.Filled.CheckCircle
                                ReconstructionOutcome.ECHEC_DETECTE -> Icons.Filled.Error
                                else -> Icons.Filled.Hub
                            },
                            contentDescription = null,
                            tint = when (rec?.outcome) {
                                ReconstructionOutcome.ENROLEMENT_REUSSI -> EmeraldVerified
                                ReconstructionOutcome.ECHEC_DETECTE -> CrimsonBreak
                                else -> CyanTelemetry
                            }
                        )
                        Text(
                            text = rec?.outcome?.label ?: "Reconstruction en attente",
                            style = MaterialTheme.typography.headlineSmall
                        )
                    }

                    Text(
                        text = rec?.summary ?: "En attente de données collectées pour cartographier le mécanisme FOD.",
                        style = MaterialTheme.typography.bodyMedium,
                        color = MaterialTheme.colorScheme.onSurfaceVariant
                    )

                    if (rec != null) {
                        Row(
                            modifier = Modifier.fillMaxWidth(),
                            horizontalArrangement = Arrangement.spacedBy(8.dp)
                        ) {
                            Surface(
                                color = EmeraldContainer,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "${rec.verifiedFactsCount} faits vérifiés",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = EmeraldVerified,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            Surface(
                                color = SlateSurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "${rec.observedCorrelationsCount} corrélations",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = CyanTelemetry,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                            Surface(
                                color = SlateSurfaceElevated,
                                shape = RoundedCornerShape(8.dp),
                                modifier = Modifier.weight(1f)
                            ) {
                                Text(
                                    text = "${rec.limitationsCount} limitations",
                                    style = MaterialTheme.typography.labelSmall,
                                    color = AmberWarning,
                                    modifier = Modifier.padding(8.dp)
                                )
                            }
                        }
                    }
                }
            }
        }

        // Première rupture démontrable en cas d'échec
        if (rec?.firstBreakDescription != null) {
            item {
                ForensicCard(
                    modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp),
                    borderColor = CrimsonBreak,
                    containerColor = CrimsonContainer.copy(alpha = 0.55f)
                ) {
                    Column(verticalArrangement = Arrangement.spacedBy(6.dp)) {
                        Text(
                            text = "PREMIÈRE RUPTURE DÉMONTRABLE",
                            style = MaterialTheme.typography.labelSmall,
                            color = CrimsonBreak,
                            fontWeight = FontWeight.Bold
                        )
                        Text(
                            text = rec.firstBreakDescription,
                            style = MaterialTheme.typography.titleMedium,
                            color = MaterialTheme.colorScheme.onSurface
                        )
                        if (!rec.firstBreakProof.isNullOrBlank()) {
                            Text(
                                text = rec.firstBreakProof,
                                style = MaterialTheme.typography.bodySmall,
                                color = CrimsonBreak
                            )
                        }
                    }
                }
            }
        }

        // Chaîne d'exécution causale par couche (tap pour voir les preuves brutes)
        item {
            Text(
                text = "Cartographie des couches observées (touchez une couche pour les preuves)",
                style = MaterialTheme.typography.titleSmall,
                color = MaterialTheme.colorScheme.onSurfaceVariant,
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
            )
        }

        val stages = rec?.stages.orEmpty()
        itemsIndexed(stages, key = { _, item -> item.layer.name }) { index, stage ->
            StagePipelineNodeCard(
                stage = stage,
                isLast = index == stages.lastIndex,
                onClick = { onInspectStage(stage) },
                modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)
            )
        }

        if (!rec?.causalExecutionPath.isNullOrEmpty()) {
            item {
                ForensicCard(modifier = Modifier.fillMaxWidth().widthIn(max = 840.dp)) {
                    Column(verticalArrangement = Arrangement.spacedBy(8.dp)) {
                        Text(
                            text = "Chemin d'exécution reconstruit",
                            style = MaterialTheme.typography.titleMedium
                        )
                        rec!!.causalExecutionPath.forEachIndexed { idx, step ->
                            Text(
                                text = "${idx + 1}. $step",
                                style = MaterialTheme.typography.bodySmall,
                                color = MaterialTheme.colorScheme.onSurface
                            )
                        }
                    }
                }
            }
        }
    }
}

@Composable
private fun StagePipelineNodeCard(
    stage: MechanismStageNode,
    isLast: Boolean,
    onClick: () -> Unit,
    modifier: Modifier = Modifier
) {
    val dotColor = when (stage.status) {
        StageStatus.VERIFIED_ACTIVE -> EmeraldVerified
        StageStatus.CORRELATED -> CyanTelemetry
        StageStatus.FIRST_BREAK -> CrimsonBreak
        StageStatus.UNOBSERVED_LIMITED -> AmberWarning
        StageStatus.MISSING_OR_REPLACED -> SlateOutline
    }

    Row(
        modifier = modifier,
        verticalAlignment = Alignment.Top
    ) {
        Column(
            horizontalAlignment = Alignment.CenterHorizontally,
            modifier = Modifier.padding(top = 18.dp, end = 12.dp)
        ) {
            Box(
                modifier = Modifier
                    .size(14.dp)
                    .clip(CircleShape)
                    .background(dotColor)
            )
            if (!isLast) {
                Box(
                    modifier = Modifier
                        .width(2.dp)
                        .height(82.dp)
                        .background(SlateOutline)
                )
            }
        }

        Surface(
            color = MaterialTheme.colorScheme.surface,
            shape = RoundedCornerShape(14.dp),
            border = BorderStroke(1.dp, dotColor.copy(alpha = 0.55f)),
            modifier = Modifier
                .weight(1f)
                .clickable(onClick = onClick)
        ) {
            Column(
                modifier = Modifier.padding(14.dp),
                verticalArrangement = Arrangement.spacedBy(6.dp)
            ) {
                Row(
                    modifier = Modifier.fillMaxWidth(),
                    horizontalArrangement = Arrangement.SpaceBetween,
                    verticalAlignment = Alignment.CenterVertically
                ) {
                    Text(
                        text = stage.layer.label,
                        style = MaterialTheme.typography.titleSmall,
                        modifier = Modifier.weight(1f)
                    )
                    Spacer(modifier = Modifier.width(8.dp))
                    StageStatusBadge(status = stage.status)
                }
                Text(
                    text = stage.factualSummary,
                    style = MaterialTheme.typography.bodyMedium,
                    color = MaterialTheme.colorScheme.onSurfaceVariant
                )
                if (stage.componentNames.isNotEmpty()) {
                    Text(
                        text = stage.componentNames.joinToString(" • "),
                        style = MaterialTheme.typography.bodySmall,
                        color = CyanTelemetry
                    )
                }
            }
        }
    }
}
