package com.displayio.ui

import android.app.Activity
import android.content.pm.ActivityInfo
import androidx.activity.compose.rememberLauncherForActivityResult
import androidx.activity.result.contract.ActivityResultContracts
import androidx.compose.foundation.background
import androidx.compose.foundation.clickable
import androidx.compose.foundation.layout.*
import androidx.compose.foundation.rememberScrollState
import androidx.compose.foundation.shape.RoundedCornerShape
import androidx.compose.foundation.verticalScroll
import androidx.compose.material.icons.Icons
import androidx.compose.material.icons.filled.*
import androidx.compose.material3.*
import androidx.compose.runtime.*
import androidx.compose.ui.Alignment
import androidx.compose.ui.Modifier
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.platform.LocalContext
import androidx.compose.ui.text.font.FontWeight
import androidx.compose.ui.text.style.TextAlign
import androidx.compose.ui.unit.dp
import androidx.compose.ui.unit.sp
import com.displayio.viewmodel.DisplayViewModel
import java.text.SimpleDateFormat
import java.util.Date
import java.util.Locale
import androidx.compose.runtime.saveable.rememberSaveable

@Composable
fun AppNavigation(viewModel: DisplayViewModel) {
    if (viewModel.currentSequence == null) {
        HomeListScreen(viewModel)
    } else {
        BigDisplayScreen(viewModel)
    }
}

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun HomeListScreen(viewModel: DisplayViewModel) {
    val context = LocalContext.current
    val launcher = rememberLauncherForActivityResult(ActivityResultContracts.GetContent()) { uri ->
        if (uri != null) viewModel.importZipFile(context, uri)
    }
    val dateFormat = remember { SimpleDateFormat("yyyy-MM-dd HH:mm", Locale.getDefault()) }

    // 主页始终锁定为竖屏
    DisposableEffect(Unit) {
        val activity = context as? Activity
        activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        onDispose {}
    }

    Scaffold(
        topBar = {
            Column {
                Spacer(modifier = Modifier.height(36.dp))
                TopAppBar(
                    title = { Text("Display 监视端", fontWeight = FontWeight.Black, fontSize = 28.sp) },
                    colors = TopAppBarDefaults.topAppBarColors(containerColor = Color(0xFFF2F2F7))
                )
            }
        },
        floatingActionButton = {
            FloatingActionButton(onClick = { launcher.launch("application/zip") }, containerColor = Color.Black, contentColor = Color.White) {
                Icon(Icons.Default.Add, contentDescription = "Import")
            }
        },
        containerColor = Color(0xFFF2F2F7)
    ) { padding ->
        Column(modifier = Modifier.padding(padding).fillMaxSize().verticalScroll(rememberScrollState())) {
            if (viewModel.importedSequences.isEmpty()) {
                Box(modifier = Modifier.fillMaxWidth().padding(top = 100.dp), contentAlignment = Alignment.Center) {
                    Text("请导入动作包，提取配置与音频", color = Color.Gray, fontSize = 16.sp)
                }
            }
            viewModel.importedSequences.forEach { info ->
                Card(
                    modifier = Modifier.fillMaxWidth().padding(horizontal = 20.dp, vertical = 10.dp).clickable { viewModel.openSequence(info.dir) },
                    elevation = CardDefaults.cardElevation(4.dp),
                    colors = CardDefaults.cardColors(containerColor = Color.White),
                    shape = RoundedCornerShape(20.dp)
                ) {
                    Row(modifier = Modifier.fillMaxWidth().padding(20.dp), verticalAlignment = Alignment.CenterVertically) {
                        Column(modifier = Modifier.weight(1f)) {
                            Text(text = info.name, fontSize = 22.sp, fontWeight = FontWeight.Bold)
                            Spacer(modifier = Modifier.height(8.dp))
                            Text(text = "导入于 ${dateFormat.format(Date(info.addDate))}", fontSize = 14.sp, color = Color.Gray)
                        }
                        IconButton(onClick = { viewModel.deleteSequence(info) }, modifier = Modifier.size(48.dp)) {
                            Icon(Icons.Default.Delete, contentDescription = "Delete", tint = Color.Red.copy(alpha = 0.6f))
                        }
                    }
                }
            }
            Spacer(modifier = Modifier.height(100.dp))
        }
    }
}

@Composable
fun BigDisplayScreen(viewModel: DisplayViewModel) {
    val context = LocalContext.current
    val sequence = viewModel.currentSequence ?: return
    var isLandscape by rememberSaveable { mutableStateOf(false) }
    val activity = context as? Activity

    // 动态控制屏幕旋转
    LaunchedEffect(isLandscape) {
        activity?.requestedOrientation = if (isLandscape) {
            ActivityInfo.SCREEN_ORIENTATION_LANDSCAPE
        } else {
            ActivityInfo.SCREEN_ORIENTATION_PORTRAIT
        }
    }

    Box(modifier = Modifier.fillMaxSize().background(Color.Black)) {
        Column(modifier = Modifier.fillMaxSize()) {

            // 顶部操作栏
            Row(
                modifier = Modifier.fillMaxWidth().padding(top = 36.dp, start = 16.dp, end = 16.dp, bottom = 16.dp),
                verticalAlignment = Alignment.CenterVertically
            ) {
                IconButton(onClick = {
                    activity?.requestedOrientation = ActivityInfo.SCREEN_ORIENTATION_UNSPECIFIED
                    viewModel.closeSequence()
                }) {
                    Icon(Icons.Default.ArrowBack, contentDescription = "Back", tint = Color.White, modifier = Modifier.size(32.dp))
                }
                Spacer(modifier = Modifier.weight(1f))

                Text(
                    text = viewModel.usbConnectionState,
                    color = if (viewModel.usbConnectionState.contains("已就绪") || viewModel.usbConnectionState.contains("已连接")) Color.Green else Color.Gray,
                    fontSize = 14.sp,
                    fontWeight = FontWeight.Bold
                )

                IconButton(onClick = { viewModel.connectToEsp32(context) }) {
                    Icon(Icons.Default.Usb, contentDescription = "USB Connect", tint = Color.White, modifier = Modifier.size(28.dp))
                }

                // 语言切换按钮：点击时不触发语音播放，仅变更显示文字
                if (!sequence.languages.isNullOrEmpty() && sequence.languages.size > 1) {
                    Spacer(modifier = Modifier.width(8.dp))
                    Button(
                        onClick = {
                            val currentIndex = sequence.languages.indexOf(viewModel.currentLanguage)
                            viewModel.currentLanguage = sequence.languages[(currentIndex + 1) % sequence.languages.size]
                            viewModel.refreshDisplayForLanguage()
                        },
                        colors = ButtonDefaults.buttonColors(containerColor = Color.DarkGray, contentColor = Color.White),
                        shape = RoundedCornerShape(12.dp)
                    ) {
                        Icon(Icons.Default.Language, contentDescription = null, modifier = Modifier.size(18.dp))
                        Spacer(modifier = Modifier.width(6.dp))
                        Text(viewModel.currentLanguage, fontWeight = FontWeight.Bold)
                    }
                }
            }

            // 大字显示区域：支持自动换行与横竖屏自动缩放
            Box(
                modifier = Modifier.weight(1f).fillMaxWidth().padding(horizontal = 32.dp),
                contentAlignment = Alignment.Center
            ) {
                Text(
                    text = viewModel.currentDisplayText,
                    color = Color.White,
                    fontSize = if (isLandscape) 110.sp else 64.sp,
                    fontWeight = FontWeight.Black,
                    textAlign = TextAlign.Center,
                    lineHeight = if (isLandscape) 130.sp else 80.sp
                )
            }

            Spacer(modifier = Modifier.height(64.dp))
        }

        // 💡 左下角横竖屏切换悬浮按钮
        IconButton(
            onClick = { isLandscape = !isLandscape },
            modifier = Modifier
                .align(Alignment.BottomStart)
                .padding(24.dp)
                .size(56.dp)
                .background(Color.DarkGray.copy(alpha = 0.5f), RoundedCornerShape(16.dp))
        ) {
            Icon(Icons.Default.ScreenRotation, contentDescription = "Rotate Screen", tint = Color.White, modifier = Modifier.size(32.dp))
        }
    }
}