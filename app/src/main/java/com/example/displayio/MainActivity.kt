package com.displayio

import android.os.Bundle
import androidx.activity.ComponentActivity
import androidx.activity.compose.setContent
import androidx.activity.viewModels
import com.displayio.ui.AppNavigation
import com.displayio.viewmodel.DisplayViewModel

class MainActivity : ComponentActivity() {

    private val viewModel: DisplayViewModel by viewModels()

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        setContent {
            AppNavigation(viewModel = viewModel)
        }
    }
}