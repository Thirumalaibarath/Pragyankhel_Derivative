package com.example.highspeedcamera.ui.screens

import android.net.Uri
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.lazy.LazyColumn
import androidx.compose.foundation.lazy.LazyRow
import androidx.compose.foundation.lazy.itemsIndexed
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.ArrowBack
import androidx.compose.material.icons.filled.PlayArrow
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.draw.clip
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import androidx.lifecycle.viewmodel.compose.viewModel
import com.example.highspeedcamera.cv.AnalysisState
import com.example.highspeedcamera.cv.AnalysisSummary
import com.example.highspeedcamera.cv.DropReason
import com.example.highspeedcamera.cv.FrameResult
import com.example.highspeedcamera.cv.FrameStatus
import com.example.highspeedcamera.cv.VideoAnalysisViewModel
import com.example.highspeedcamera.cv.summary

// ── Brand colours ─────────────────────────────────────────────────────────────

private val BrandPurple  = Color(0xFF6931FF)
private val CardPurple   = Color(0xFF5425CC)     // slightly darker for card bg
private val NormalColor  = Color(0xFF4CAF50)     // green
private val DropColor    = Color(0xFFE53935)     // red
private val MergeColor   = Color(0xFFFF9800)     // orange

@Composable
fun VideoAnalysisScreen(
    onBack: () -> Unit,
    vm: VideoAnalysisViewModel = viewModel()
) {
    val context = LocalContext.current
    val state   by vm.state.collectAsState()
    var pickedUri by remember { mutableStateOf<Uri?>(null) }

    val videoPicker = rememberLauncherForActivityResult(
        contract = ActivityResultContracts.GetContent()
    ) { uri: Uri? -> pickedUri = uri }

    Scaffold(
        topBar = {
            @OptIn(ExperimentalMaterial3Api::class)
            TopAppBar(
                title = {
                    Text(
                        "Video Analyser",
                        color      = Color.White,
                        fontWeight = FontWeight.Bold
                    )
                },
                navigationIcon = {
                    IconButton(onClick = { vm.reset(); onBack() }) {
                        Icon(Icons.Default.ArrowBack, contentDescription = "Back",
                            tint = Color.White)
                    }
                },
                colors = TopAppBarDefaults.topAppBarColors(
                    containerColor = BrandPurple
                )
            )
        },
        containerColor = BrandPurple
    ) { padding ->

        LazyColumn(
            modifier = Modifier
                .fillMaxSize()
                .background(BrandPurple)
                .padding(padding)
                .windowInsetsPadding(WindowInsets.navigationBars),
            contentPadding  = PaddingValues(16.dp),
            verticalArrangement = Arrangement.spacedBy(16.dp)
        ) {

            // ── File picker card ──────────────────────────────────────────────
            if (state !is AnalysisState.Processing) {
                item {
                    FileSelectorCard(
                        pickedUri   = pickedUri,
                        onPickClick = { videoPicker.launch("video/*") },
                        onReset     = { pickedUri = null; vm.reset() }
                    )
                }

                // "Run Analysis" button
                item {
                    Button(
                        onClick  = { pickedUri?.let { vm.analyse(context, it) } },
                        enabled  = pickedUri != null && state !is AnalysisState.Processing,
                        modifier = Modifier.fillMaxWidth().height(54.dp),
                        colors   = ButtonDefaults.buttonColors(
                            containerColor = Color.White,
                            contentColor   = BrandPurple,
                            disabledContainerColor = Color.White.copy(alpha = 0.3f),
                            disabledContentColor   = Color.White.copy(alpha = 0.6f)
                        ),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.PlayArrow, contentDescription = null)
                        Spacer(Modifier.width(8.dp))
                        Text("Run Analysis", fontWeight = FontWeight.Bold, fontSize = 16.sp)
                    }
                }
            }

            // ── Processing indicator ──────────────────────────────────────────
            if (state is AnalysisState.Processing) {
                item {
                    Column(
                        modifier            = Modifier.fillMaxWidth().padding(vertical = 48.dp),
                        horizontalAlignment = Alignment.CenterHorizontally,
                        verticalArrangement = Arrangement.spacedBy(20.dp)
                    ) {
                        CircularProgressIndicator(color = Color.White, strokeWidth = 4.dp)
                        Text("Analysing frames…", color = Color.White,
                            style = MaterialTheme.typography.bodyLarge)
                        Text(
                            "This may take a moment depending on video length.",
                            color     = Color.White.copy(alpha = 0.7f),
                            style     = MaterialTheme.typography.bodySmall,
                            textAlign = TextAlign.Center
                        )
                    }
                }
            }

            // ── Error state ───────────────────────────────────────────────────
            if (state is AnalysisState.Error) {
                item {
                    ErrorCard(
                        message = (state as AnalysisState.Error).message,
                        onRetry = { vm.reset() }
                    )
                }
            }

            // ── Results ───────────────────────────────────────────────────────
            if (state is AnalysisState.Done) {
                val results = (state as AnalysisState.Done).results
                val summary = results.summary()

                item { SummaryRow(summary) }

                item {
                    Text(
                        "Frame Timeline",
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp
                    )
                    Spacer(Modifier.height(6.dp))
                    FrameTimeline(results)
                }

                item {
                    Text(
                        "Frame Details",
                        color      = Color.White,
                        fontWeight = FontWeight.SemiBold,
                        fontSize   = 13.sp
                    )
                }

                itemsIndexed(results) { _, frame ->
                    FrameRow(frame)
                }
            }
        }
    }
}

// ── Sub-composables ───────────────────────────────────────────────────────────

@Composable
private fun FileSelectorCard(
    pickedUri  : Uri?,
    onPickClick: () -> Unit,
    onReset    : () -> Unit
) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = CardPurple)
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text(
                "Detect Frame Drops & Merges",
                color      = Color.White,
                fontWeight = FontWeight.Bold,
                fontSize   = 18.sp
            )
            Text(
                "Upload a video to automatically classify each frame as\nNormal · Frame Drop · Frame Merge",
                color     = Color.White.copy(alpha = 0.75f),
                fontSize  = 13.sp,
                textAlign = TextAlign.Center,
                lineHeight = 18.sp
            )
            HorizontalDivider(color = Color.White.copy(alpha = 0.2f))

            if (pickedUri == null) {
                OutlinedButton(
                    onClick = onPickClick,
                    modifier = Modifier.fillMaxWidth(),
                    colors   = ButtonDefaults.outlinedButtonColors(contentColor = Color.White),
                    border   = androidx.compose.foundation.BorderStroke(1.dp, Color.White.copy(alpha = 0.5f)),
                    shape    = RoundedCornerShape(10.dp)
                ) {
                    Text("📂  Choose Video from Gallery")
                }
            } else {
                Row(
                    modifier            = Modifier.fillMaxWidth(),
                    verticalAlignment   = Alignment.CenterVertically,
                    horizontalArrangement = Arrangement.SpaceBetween
                ) {
                    Column(modifier = Modifier.weight(1f)) {
                        Text("Video selected ✓", color = NormalColor, fontWeight = FontWeight.SemiBold, fontSize = 13.sp)
                        Text(
                            pickedUri.lastPathSegment ?: pickedUri.toString(),
                            color    = Color.White.copy(alpha = 0.7f),
                            fontSize = 11.sp,
                            maxLines = 1
                        )
                    }
                    TextButton(onClick = onReset) {
                        Text("Change", color = Color.White.copy(alpha = 0.7f), fontSize = 12.sp)
                    }
                }
            }
        }
    }
}

@Composable
private fun SummaryRow(s: AnalysisSummary) {
    Row(
        modifier            = Modifier.fillMaxWidth(),
        horizontalArrangement = Arrangement.spacedBy(10.dp)
    ) {
        SummaryChip("Total",  s.total.toString(),  Color.White,       modifier = Modifier.weight(1f))
        SummaryChip("Normal", s.normal.toString(), NormalColor,       modifier = Modifier.weight(1f))
        SummaryChip("Drops",  s.drops.toString(),  DropColor,         modifier = Modifier.weight(1f))
        SummaryChip("Merges", s.merges.toString(), MergeColor,        modifier = Modifier.weight(1f))
    }
}

@Composable
private fun SummaryChip(label: String, value: String, valueColor: Color, modifier: Modifier = Modifier) {
    Card(
        modifier = modifier,
        shape    = RoundedCornerShape(12.dp),
        colors   = CardDefaults.cardColors(containerColor = CardPurple)
    ) {
        Column(
            modifier            = Modifier.padding(vertical = 12.dp, horizontal = 8.dp).fillMaxWidth(),
            horizontalAlignment = Alignment.CenterHorizontally
        ) {
            Text(value, color = valueColor, fontWeight = FontWeight.ExtraBold, fontSize = 22.sp)
            Text(label, color = Color.White.copy(alpha = 0.75f), fontSize = 11.sp)
        }
    }
}

@Composable
private fun FrameTimeline(results: List<FrameResult>) {
    val blockW = 6.dp
    LazyRow(
        modifier            = Modifier
            .fillMaxWidth()
            .height(28.dp)
            .clip(RoundedCornerShape(6.dp))
            .background(CardPurple),
        contentPadding      = PaddingValues(2.dp),
        horizontalArrangement = Arrangement.spacedBy(1.dp)
    ) {
        itemsIndexed(results) { _, frame ->
            Box(
                modifier = Modifier
                    .width(blockW)
                    .fillMaxHeight()
                    .background(frame.status.color())
            )
        }
    }
    Spacer(Modifier.height(6.dp))
    Row(horizontalArrangement = Arrangement.spacedBy(14.dp)) {
        LegendDot("Normal",      NormalColor)
        LegendDot("Frame Drop",  DropColor)
        LegendDot("Frame Merge", MergeColor)
    }
}

@Composable
private fun LegendDot(label: String, color: Color) {
    Row(verticalAlignment = Alignment.CenterVertically, horizontalArrangement = Arrangement.spacedBy(4.dp)) {
        Box(modifier = Modifier.size(8.dp).background(color, RoundedCornerShape(2.dp)))
        Text(label, color = Color.White.copy(alpha = 0.7f), fontSize = 10.sp)
    }
}

@Composable
private fun FrameRow(frame: FrameResult) {
    Row(
        modifier          = Modifier
            .fillMaxWidth()
            .padding(vertical = 2.dp)
            .clip(RoundedCornerShape(8.dp))
            .background(CardPurple)
            .padding(horizontal = 12.dp, vertical = 8.dp),
        verticalAlignment = Alignment.CenterVertically
    ) {
        Text(
            "#%-5d".format(frame.index + 1),
            color    = Color.White.copy(alpha = 0.6f),
            fontSize = 11.sp,
            modifier = Modifier.width(50.dp)
        )
        Text(
            "%.1f ms".format(frame.timestampMs),
            color    = Color.White.copy(alpha = 0.75f),
            fontSize = 11.sp,
            modifier = Modifier.weight(1f)
        )
        Text(
            "μ %.1f".format(frame.motionValue),
            color    = Color.White.copy(alpha = 0.55f),
            fontSize = 10.sp,
            modifier = Modifier.width(52.dp)
        )
        StatusBadge(frame.status, frame.dropReason)
    }
}

@Composable
private fun StatusBadge(status: FrameStatus, dropReason: DropReason = DropReason.NONE) {
    val (label, color) = when (status) {
        FrameStatus.NORMAL      -> "NORMAL" to NormalColor
        FrameStatus.FRAME_DROP  -> "DROP"   to DropColor
        FrameStatus.FRAME_MERGE -> "MERGE"  to MergeColor
    }
    val reasonLabel = when (dropReason) {
        DropReason.HIGH_SPIKE -> "motion spike"
        DropReason.FRAME_FREEZE -> "motion merge"
        DropReason.NONE -> null
    }
    Column(
        horizontalAlignment = Alignment.End,
        verticalArrangement = Arrangement.spacedBy(2.dp)
    ) {
        Box(
            modifier         = Modifier
                .clip(RoundedCornerShape(4.dp))
                .background(color.copy(alpha = 0.2f))
                .padding(horizontal = 6.dp, vertical = 2.dp),
            contentAlignment = Alignment.Center
        ) {
            Text(label, color = color, fontSize = 10.sp, fontWeight = FontWeight.Bold)
        }
        if (reasonLabel != null) {
            Text(
                reasonLabel,
                color    = color.copy(alpha = 0.8f),
                fontSize = 9.sp,
                fontWeight = FontWeight.Normal
            )
        }
    }
}

@Composable
private fun ErrorCard(message: String, onRetry: () -> Unit) {
    Card(
        modifier = Modifier.fillMaxWidth(),
        shape    = RoundedCornerShape(16.dp),
        colors   = CardDefaults.cardColors(containerColor = CardPurple)
    ) {
        Column(
            modifier            = Modifier.padding(20.dp),
            horizontalAlignment = Alignment.CenterHorizontally,
            verticalArrangement = Arrangement.spacedBy(12.dp)
        ) {
            Text("⚠️  Analysis Failed", color = DropColor, fontWeight = FontWeight.Bold)
            Text(message, color = Color.White.copy(alpha = 0.8f), fontSize = 13.sp, textAlign = TextAlign.Center)
            Button(onClick = onRetry, colors = ButtonDefaults.buttonColors(containerColor = Color.White, contentColor = BrandPurple)) {
                Text("Try Again")
            }
        }
    }
}

// Extension: FrameStatus → display colour
private fun FrameStatus.color() = when (this) {
    FrameStatus.NORMAL      -> NormalColor
    FrameStatus.FRAME_DROP  -> DropColor
    FrameStatus.FRAME_MERGE -> MergeColor
}
