package com.example.anticenter.ui.components

import androidx.compose.material3.ExperimentalMaterial3Api
import androidx.compose.material3.Text
import androidx.compose.material3.TopAppBar
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.tooling.preview.Preview
import com.example.compose.AppTheme

@OptIn(ExperimentalMaterial3Api::class)
@Composable
fun TitleBar(title: String,modifier: Modifier = Modifier){
    TopAppBar(title = {Text(title)},modifier = modifier)
}

@Preview
@Composable
fun TitleBarPreview(){
    AppTheme {
        TitleBar("Allow To Access")
    }

}